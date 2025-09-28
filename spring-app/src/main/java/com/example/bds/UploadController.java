package com.example.bds;

import com.example.bds.ml.MemorySampler;
import com.example.bds.pdf.PdfFeatureExtractor;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.ByteBuffer;

/**
 * Reactive HTTP controller for PDF uploads.
 *
 * <h2>Purpose</h2>
 * <p>
 * Accepts a single PDF file via {@code multipart/form-data}, extracts deterministic features,
 * predicts peak memory usage (either via a tiny in-process model or the ML sidecar),
 * optionally trains on a measured label, and returns a rich JSON payload with both
 * prediction and training diagnostics.
 * </p>
 *
 * <h2>Endpoint</h2>
 * <ul>
 *   <li><b>POST</b> {@code /v1/upload/pdf}</li>
 *   <li><b>Consumes:</b> {@code multipart/form-data}</li>
 *   <li><b>Produces:</b> {@code application/json}</li>
 *   <li>Parts:
 *     <ul>
 *       <li>{@code file}: the PDF file (required)</li>
 *       <li>{@code train}: optional string flag; training occurs when the value is
 *           {@code null}, {@code "true"}, or {@code "on"} (case-insensitive).
 *           Any other value disables training for this request.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h2>Processing flow</h2>
 * <ol>
 *   <li><b>Validation:</b> verify content type is compatible with {@code application/pdf},
 *       enforce byte-size cap, and perform a quick PDF header check ({@code %PDF-}).</li>
 *   <li><b>Feature extraction:</b> offloaded to {@code boundedElastic} to avoid blocking
 *       event-loop threads; duration is recorded in {@code bds.pdf.extract.duration}.</li>
 *   <li><b>Predict-before:</b> call {@link MemorySpikeService#predictOnly} (no side effects).</li>
 *   <li><b>Measure label:</b> run a representative workload wrapped by
 *       {@link MemorySampler#measure(java.util.function.Supplier, long)} to record peak heap MB.</li>
 *   <li><b>Train (optional):</b> append features + measured label to CSV and maybe retrain the
 *       local linear model (see {@link MemorySpikeService#train}).</li>
 *   <li><b>Predict-after:</b> call {@link MemorySpikeService#predictOnly} again (no side effects);
 *       return a response that includes decisions, measured label, sample counts, and model usage flags.</li>
 * </ol>
 *
 * <h2>Metrics</h2>
 * <ul>
 *   <li>{@code bds.upload.bytes} — Distribution summary of uploaded payload sizes (bytes).</li>
 *   <li>{@code bds.pdf.extract.duration} — Timer for feature extraction latency (ms recorded).</li>
 *   <li>Additional metrics for routing and sidecar latency are emitted by {@link MemorySpikeService}.</li>
 * </ul>
 *
 * <h2>Threading &amp; back-pressure</h2>
 * <ul>
 *   <li>Large/CPU or blocking operations are offloaded using {@code subscribeOn(Schedulers.boundedElastic())}.</li>
 *   <li>File bytes are aggregated via {@link DataBufferUtils#join}, then the buffer is released promptly.</li>
 *   <li>All steps return non-blocking {@link Mono} chains.</li>
 * </ul>
 *
 * <h2>Error handling</h2>
 * <ul>
 *   <li>Invalid content type, empty payloads, files over the size cap, and non-PDF headers
 *       produce {@link IllegalArgumentException} which is expected to be mapped by your
 *       global exception handler to a 4xx response.</li>
 *   <li>Unexpected exceptions are propagated as error signals; your
 *       {@code @ControllerAdvice} / global handler should map them to 5xx.</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * curl -s -F "file=@/path/to/sample.pdf;type=application/pdf" \
 *      -F "train=on" \
 *      http://127.0.0.1:8033/v1/upload/pdf | jq
 * }</pre>
 *
 * @since 1.0
 */
@RestController
@RequestMapping("/v1/upload")
@RequiredArgsConstructor
@Validated
public class UploadController {

    /**
     * Maximum allowed upload size (bytes). Default is 50 MiB.
     * <p>Bound from property {@code bds.max-bytes}; keep aligned with any reverse proxy limits.</p>
     */
    @Value("${bds.max-bytes:52428800}") // default 50 MiB
    private long maxBytes;

    /** Orchestrates prediction + optional training + model lifecycle. */
    private final MemorySpikeService memorySpikeService;

    /** Stateless, reusable feature extractor. */
    private final PdfFeatureExtractor extractor = new PdfFeatureExtractor();

    /** Micrometer registry for upload/extraction metrics. */
    private final MeterRegistry meterRegistry;

    /**
     * Handle a PDF upload, optionally train on a measured label, and return a detailed outcome.
     *
     * @param file      the uploaded PDF (multipart part named {@code file})
     * @param trainFlag optional training flag; training occurs when {@code null}, {@code "true"},
     *                  or {@code "on"} (case-insensitive). Any other value disables training.
     * @return a {@link Mono} emitting {@link UploadResponse} on success
     */
    @PostMapping(
            path = "/pdf",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Mono<UploadResponse> uploadAndRoute(@RequestPart("file") FilePart file,
                                               @RequestPart(name = "train", required = false) String trainFlag) {
        var ct = file.headers().getContentType();
        if (ct == null || !MediaType.APPLICATION_PDF.isCompatibleWith(ct)) {
            return Mono.error(new IllegalArgumentException("Only application/pdf is supported"));
        }

        final boolean doTrain =
                trainFlag == null ||
                        "true".equalsIgnoreCase(trainFlag) ||
                        "on".equalsIgnoreCase(trainFlag);

        return DataBufferUtils.join(file.content())
                .flatMap(buf -> {
                    try {
                        byte[] bytes = toByteArrayAndRelease(buf);

                        // Quick signature guardrail (ASCII "%PDF-")
                        if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F' || bytes[4] != '-') {
                            throw new IllegalArgumentException("Not a valid PDF header");
                        }
                        if (bytes.length == 0) return Mono.error(new IllegalArgumentException("Empty upload"));
                        if (bytes.length > maxBytes) return Mono.error(new IllegalArgumentException("File too large"));

                        // Record size metric
                        meterRegistry.summary("bds.upload.bytes").record(bytes.length);

                        // Extract features off the event loop
                        return Mono.fromCallable(() -> extractor.extract(bytes))
                                .subscribeOn(Schedulers.boundedElastic())
                                .elapsed()
                                .map(tuple -> {
                                    meterRegistry.timer("bds.pdf.extract.duration").record(tuple.getT1(), java.util.concurrent.TimeUnit.MILLISECONDS);
                                    return tuple.getT2();
                                })
                                .flatMap(features -> {
                                    final boolean usedLocalBefore = memorySpikeService.hasLocalModel();
                                    final int samplesBefore = memorySpikeService.sampleCount();

                                    // 1) predict (no side-effects)
                                    return memorySpikeService.predictOnly(features)
                                            .flatMap(predBefore ->
                                                    // 2) measure real processing peak (do heavy work off the event loop)
                                                    Mono.fromCallable(() -> MemorySampler.measure(() -> {
                                                                // TODO: replace with actual PDF processing (e.g., watermark via PDFBox)
                                                                int s = 0;
                                                                for (byte b : bytes) s += (b & 0xFF);
                                                                return s;
                                                            }, 25)).subscribeOn(Schedulers.boundedElastic())
                                                            .flatMap(sampled -> {
                                                                // 3) train on measured label (only if requested)
                                                                Mono<Void> trainMono = doTrain
                                                                        ? memorySpikeService.train(features, sampled.peakMb())
                                                                        : Mono.empty();

                                                                // 4) predict again after (no side-effects)
                                                                return trainMono.then(memorySpikeService.predictOnly(features))
                                                                        .map(predAfter -> new UploadResponse(
                                                                                predAfter.decision(),
                                                                                Math.max(0.0, predAfter.predicted_peak_mb()),
                                                                                memorySpikeService.threshold(),
                                                                                usedLocalBefore,
                                                                                samplesBefore,
                                                                                memorySpikeService.hasLocalModel(),
                                                                                memorySpikeService.sampleCount(),
                                                                                sampled.peakMb(),
                                                                                doTrain
                                                                        ));
                                                            })
                                            );
                                });
                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                })
                .doOnCancel(() -> System.out.println("Upload cancelled by client; aborting processing"));
    }

    /**
     * Response payload with prediction + training diagnostics for the demo.
     * <p>
     * Notes:
     * <ul>
     *   <li>{@code predicted_peak_mb} may be {@code 0.0} if the sidecar failed and a conservative
     *       fallback was used (the negative sentinel is clamped to non-negative here).</li>
     *   <li>{@code measured_peak_mb} is from {@link MemorySampler} around a representative workload,
     *       not from full production processing (replace the TODO with real work).</li>
     *   <li>{@code used_local_model_*} indicate whether an in-process model was available before/after
     *       this request (training may have produced one).</li>
     * </ul>
     */
    public record UploadResponse(
            String decision,
            double predicted_peak_mb,
            double threshold_mb,
            boolean used_local_model_before,
            int samples_before,
            boolean used_local_model_after,
            int samples_after,
            double measured_peak_mb,
            boolean trained_this_upload
    ) {
    }

    /**
     * Copy the contents of a {@link DataBuffer} into a new byte array and
     * <em>always</em> release the buffer to avoid leaks.
     *
     * @param buf the joined data buffer for this upload
     * @return a fresh {@code byte[]} with the same contents
     */
    private static byte[] toByteArrayAndRelease(DataBuffer buf) {
        try {
            ByteBuffer nio = buf.toByteBuffer();
            byte[] out = new byte[nio.remaining()];
            nio.get(out);
            return out;
        } finally {
            DataBufferUtils.release(buf);
        }
    }
}

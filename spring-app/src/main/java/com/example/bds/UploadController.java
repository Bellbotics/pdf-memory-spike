package com.example.bds;

import com.example.bds.ml.MemorySampler;
import com.example.bds.pdf.PdfFeatureExtractor;
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

@RestController
@RequestMapping("/v1/upload")
@RequiredArgsConstructor
@Validated
public class UploadController {

    /**
     * Configurable upload size cap; keep in sync with application.yaml.
     */
    @Value("${bds.max-bytes:52428800}") // default 50 MiB
    private long maxBytes;

    private final MemorySpikeService memorySpikeService;
    private final PdfFeatureExtractor extractor = new PdfFeatureExtractor();

    @PostMapping(
            path = "/pdf",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Mono<UploadResponse> uploadAndRoute(@RequestPart("file") FilePart file,
                                               @RequestPart(name = "train", required = false) String trainFlag) {
        var ct = file.headers().getContentType();
        if (ct != null && !MediaType.APPLICATION_PDF.includes(ct)) {
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
                    if (bytes.length == 0) return Mono.error(new IllegalArgumentException("Empty upload"));
                    if (bytes.length > maxBytes) return Mono.error(new IllegalArgumentException("File too large"));
                    // Extract features off the event loop
                    return Mono.fromCallable(() -> extractor.extract(bytes))
                        .subscribeOn(Schedulers.boundedElastic())
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
                                                    predAfter.predicted_peak_mb(),
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
     * Response payload with training model info for the demo.
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
     * Copy DataBuffer to byte[] and release it.
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
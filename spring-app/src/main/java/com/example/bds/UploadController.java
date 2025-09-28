package com.example.bds;

import com.example.bds.ml.MemorySampler;
import com.example.bds.ml.PredictionService;
import lombok.RequiredArgsConstructor;
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

import java.nio.ByteBuffer;

@RestController
@RequestMapping("/v1/upload")
@RequiredArgsConstructor
@Validated
public class UploadController {

    private final PredictionService predictionService;

    private static final long MAX_BYTES = 50L * 1024L * 1024L; // 50 MB

    @PostMapping(
            path = "/pdf",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Mono<UploadResponse> uploadAndRoute(@RequestPart("file") FilePart file,
                                               @RequestPart(name = "train", required = false) String trainFlag) {

        // best-effort content-type check (some browsers omit it)
        var ct = file.headers().getContentType();
        if (ct != null && !MediaType.APPLICATION_PDF.includes(ct)) {
            return Mono.error(new IllegalArgumentException("Only application/pdf is supported"));
        }

        final boolean doTrain =
                trainFlag == null || // treat missing as "checked" so the demo trains by default
                        "true".equalsIgnoreCase(trainFlag) ||
                        "on".equalsIgnoreCase(trainFlag);

        return DataBufferUtils.join(file.content())
                .flatMap(buf -> {
                    try {
                        byte[] bytes = toByteArrayAndRelease(buf);

                        if (bytes.length == 0) {
                            return Mono.error(new IllegalArgumentException("Empty upload"));
                        }
                        if (bytes.length > MAX_BYTES) {
                            return Mono.error(new IllegalArgumentException("File too large (max 50MB)"));
                        }

                        // 1) get a prediction (local model if present, otherwise sidecar)
                        return predictionService.predict(bytes)
                                .flatMap(predBefore -> {
                                    // 2) run your real processing under the memory sampler to get a label
                                    var sampled = MemorySampler.measure(() -> {
                                        // TODO replace with your real PDF processing (e.g., watermark with PDFBox)
                                        int s = 0; for (byte b : bytes) s += (b & 0xFF);
                                        return s;
                                    }, 25);

                                    // 3) optionally train on the measured peak
                                    Mono<Void> trainMono = doTrain
                                            ? predictionService.train(bytes, sampled.peakMb())
                                            : Mono.empty();

                                    // 4) after training (or not), predict again to show effect and respond
                                    return trainMono.then(predictionService.predict(bytes))
                                            .map(predAfter -> new UploadResponse(
                                                    predAfter.decision(),
                                                    predAfter.predictedPeakMb(),
                                                    predictionService.threshold(),
                                                    predBefore.usingLocalModel(),
                                                    predBefore.samples(),
                                                    predAfter.usingLocalModel(),
                                                    predAfter.samples(),
                                                    sampled.peakMb(),
                                                    doTrain
                                            ));
                                });

                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                });
    }

    /** Response payload with training + model info for the demo. */
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
    ) {}

    /** Copy DataBuffer to byte[] and release it. */
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
package com.example.bds;

import com.example.bds.dto.PdfFeatures;
import com.example.bds.dto.RouteDecision;
import com.example.bds.pdf.PdfFeatureExtractor;
import jakarta.validation.constraints.NotNull;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;
import lombok.RequiredArgsConstructor;

import java.io.ByteArrayInputStream;


@RestController
@RequestMapping("/v1/upload")
@RequiredArgsConstructor
@Validated
public class UploadController {
    private final MemorySpikeService memorySpikeService;
    private static final long MAX_BYTES = 50L * 1024L * 1024L; // 50 MB

    @PostMapping(
            path = "/pdf",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Mono<RouteDecision> uploadAndRoute(@RequestPart("file") FilePart file) {
        // quick content-type guard (best-effort; some browsers omit it)
        var ct = file.headers().getContentType();
        if (ct != null && !MediaType.APPLICATION_PDF.includes(ct)) {
            return Mono.error(new IllegalArgumentException("Only application/pdf is supported"));
        }

        // Join the reactive stream into memory (OK for small demo; for big files, stream to temp file)
        return DataBufferUtils.join(file.content())
                .flatMap(buf -> {
                    try {
                        var bytes = new byte[buf.readableByteCount()];
                        buf.read(bytes);
                        DataBufferUtils.release(buf);

                        if (bytes.length == 0) return Mono.error(new IllegalArgumentException("Empty upload"));
                        if (bytes.length > MAX_BYTES) return Mono.error(new IllegalArgumentException("File too large (max 50MB)"));

                        var features = PdfFeatureExtractor.extract(new ByteArrayInputStream(bytes), bytes.length);
                        return memorySpikeService.decide(features);
                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                });
    }
}
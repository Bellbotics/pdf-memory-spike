package com.example.bds;


import com.example.bds.dto.PdfFeatures;
import com.example.bds.dto.RouteDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
@AutoConfigureWebTestClient
class UploadControllerTest {

    @Autowired WebTestClient web;

    @MockBean
    MemorySpikeService memorySpikeService;

    @TempDir
    static Path tmp; // per-test-run unique directory

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        // force MemorySpikeService to use a fresh, empty data dir
        r.add("bds.data-dir", () -> tmp.resolve("bds").toString());
        r.add("bds.train-csv", () -> tmp.resolve("bds/training.csv").toString());
        r.add("bds.model-file", () -> tmp.resolve("bds/model.json").toString());
        // make retrain unlikely to trigger during this unit test
        r.add("bds.retrain-every", () -> "9999");
        // if anything accidentally hits the sidecar, point to a non-routable address
        r.add("triage.base-url", () -> "http://127.0.0.1:65535");
        // cap upload size at 5 MB so we can exercise an "oversized" case deterministically
        r.add("bds.max-bytes", () -> String.valueOf(5 * 1024 * 1024));
    }

    @Test
    void uploadPdf_scoresAndReturnsDecision() {
        // Controller flow:
        // 1) predictOnly (before)
        // 2) train (optional)
        // 3) predictOnly (after)
        // plus threshold/hasLocalModel/sampleCount queries

        when(memorySpikeService.threshold()).thenReturn(3500.0);
        when(memorySpikeService.hasLocalModel()).thenReturn(false, true); // before/after
        when(memorySpikeService.sampleCount()).thenReturn(0, 1);          // before/after

        // predict before training
        when(memorySpikeService.predictOnly(any(PdfFeatures.class)))
                .thenReturn(Mono.just(new RouteDecision("STANDARD_PATH", 1234.5)))
                // predict after training
                .thenReturn(Mono.just(new RouteDecision("STANDARD_PATH", 1234.5)));

        // training completes successfully
        when(memorySpikeService.train(any(PdfFeatures.class), anyDouble()))
                .thenReturn(Mono.empty());

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("samples/text.pdf"))
                .contentType(MediaType.APPLICATION_PDF);

        web.post().uri("/v1/upload/pdf")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(builder.build())
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.decision").isEqualTo("STANDARD_PATH")
                .jsonPath("$.predicted_peak_mb").isNumber()
                .jsonPath("$.threshold_mb").isEqualTo(3500.0)
                .jsonPath("$.used_local_model_before").isEqualTo(false)
                .jsonPath("$.samples_before").isEqualTo(0)
                .jsonPath("$.used_local_model_after").isEqualTo(true)
                .jsonPath("$.samples_after").isEqualTo(1)
                .jsonPath("$.measured_peak_mb").isNumber()
                .jsonPath("$.trained_this_upload").isEqualTo(true);
    }

    @Test
    void nonPdfContentType_rejected4xx() {
        // Build a plain-text payload and mark the *part* as text/plain.
        byte[] txt = "not a pdf".getBytes(StandardCharsets.UTF_8);
        ByteArrayResource res = new ByteArrayResource(txt) {
            @Override public String getFilename() { return "note.txt"; }
        };

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", res)
                .filename("note.txt")
                .contentType(MediaType.TEXT_PLAIN);

        web.post().uri("/v1/upload/pdf")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(builder.build())
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void emptyUpload_rejected4xx() {
        // Zero-length "PDF" content; controller should reject empty uploads.
        byte[] empty = new byte[0];
        ByteArrayResource res = new ByteArrayResource(empty) {
            @Override public String getFilename() { return "empty.pdf"; }
        };

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", res)
                .filename("empty.pdf")
                .contentType(MediaType.APPLICATION_PDF);

        web.post().uri("/v1/upload/pdf")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(builder.build())
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void oversizedUpload_rejected4xx() {
        // Craft a payload larger than bds.max-bytes (5 MB set above): use 6 MB.
        byte[] big = new byte[6 * 1024 * 1024];
        ByteArrayResource res = new ByteArrayResource(big) {
            @Override public String getFilename() { return "big.pdf"; }
        };

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", res)
                .filename("big.pdf")
                .contentType(MediaType.APPLICATION_PDF);

        web.post().uri("/v1/upload/pdf")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(builder.build())
                .exchange()
                .expectStatus().is4xxClientError();
    }
}
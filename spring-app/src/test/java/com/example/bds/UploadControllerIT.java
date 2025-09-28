package com.example.bds;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.ByteArrayOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Full integration test for the /v1/upload/pdf endpoint.
 * Starts a WireMock server that stubs the sidecar's /predict API.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "management.datadog.metrics.export.enabled=false"
})
@AutoConfigureWebTestClient
class UploadControllerIT {

    // Start WireMock on a dynamic port to avoid collisions with other tests.
    private static final WireMockServer wm = new WireMockServer(options().dynamicPort());

    @Autowired WebTestClient web;

    /**
     * Register dynamic properties BEFORE the Spring context is created.
     * We start WireMock here so we can inject its port into the app property.
     */
    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        if (!wm.isRunning()) {
            wm.start();
        }
        r.add("triage.base-url", () -> "http://127.0.0.1:" + wm.port());
    }

    @BeforeAll
    static void stubSidecar() {
        wm.stubFor(post(urlEqualTo("/predict"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"predicted_peak_mb\":4800.0," +
                                "\"decision\":\"ROUTE_BIG_MEMORY\"," +
                                "\"threshold_mb\":3500}")
                        .withStatus(200)));
    }

    @AfterAll
    static void stopWiremock() {
        if (wm.isRunning()) wm.stop();
    }

    @Test
    void uploadPdf_hitsSidecarAndReturnsDecision() throws Exception {
        byte[] pdf = tinyPdf(); // no filesystem dependency
        ByteArrayResource res = new ByteArrayResource(pdf) {
            @Override public String getFilename() { return "test.pdf"; }
        };
        MultipartBodyBuilder mb = new MultipartBodyBuilder();
        mb.part("file", res).contentType(MediaType.APPLICATION_PDF);

        web.post().uri("/v1/upload/pdf")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(mb.build())
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.decision").isEqualTo("ROUTE_BIG_MEMORY")
                .jsonPath("$.predicted_peak_mb").isEqualTo(4800.0);

        wm.verify(postRequestedFor(urlEqualTo("/predict")));
        System.out.println("Serve events:\n" + wm.getAllServeEvents());
    }

    private static byte[] tinyPdf() throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }
}

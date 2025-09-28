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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.ByteArrayOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;     // <-- WireMock statics
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Full integration test for the /v1/upload/pdf endpoint.
 * Starts a WireMock server that stubs the sidecar's /predict API.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.profiles.active=test",
        "management.metrics.export.datadog.enabled=false",
        "triage.base-url=http://localhost:18080" // your app should read this for the sidecar base URL
})
class UploadControllerIT {

    private static WireMockServer wm;

    @Autowired WebTestClient web;

    @BeforeAll
    static void startWiremock() {
        wm = new WireMockServer(options().port(18080));
        wm.start();
        wm.stubFor(post(urlEqualTo("/predict"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"predicted_peak_mb\":4800.0,\"decision\":\"ROUTE_BIG_MEMORY\",\"threshold_mb\":3500}")
                        .withStatus(200)));
    }

    @AfterAll
    static void stopWiremock() {
        if (wm != null) wm.stop();
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

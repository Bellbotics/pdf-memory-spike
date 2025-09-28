package com.example.bds;


import com.example.bds.dto.RouteDecision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureWebTestClient
class UploadControllerTest {

    @Autowired WebTestClient web;

    @MockBean
    MemorySpikeService memorySpikeService;

    @Test
    void uploadPdf_scoresAndReturnsDecision() {
        when(memorySpikeService.decide(any()))
                .thenReturn(Mono.just(new RouteDecision("STANDARD_PATH", 1234.5)));

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
                .jsonPath("$.predicted_peak_mb").isNumber();
    }
}

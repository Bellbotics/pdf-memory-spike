package com.example.bds.ml;

import com.example.bds.dto.PdfFeatures;
import com.example.bds.dto.RouteDecision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Sidecar client. POSTs PdfFeatures to /predict and maps the response.
 */
@Service
public class PredictionService {

    private final WebClient webClient;

    public PredictionService(
            WebClient.Builder builder,
            @Value("${triage.base-url:http://localhost:18080}") String baseUrl
    ) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    public Mono<RouteDecision> predictViaSidecar(PdfFeatures features) {
        return webClient.post()
                .uri("/predict")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(features)
                .retrieve()
                .bodyToMono(RouteDecision.class);
    }

}

package com.example.bds.ml;

import com.example.bds.dto.PdfFeatures;
import com.example.bds.dto.RouteDecision;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Sidecar client. POSTs PdfFeatures to /predict and maps the response.
 */
@Service
public class PredictionService {
    /** Sidecar request envelope. */
    public record PredictRequest(PdfFeatures features, Double big_mem_threshold_mb) {}


    private final WebClient webClient;

    public PredictionService(WebClient memoryScoreWebClient) {
        this.webClient = memoryScoreWebClient;
    }

    public Mono<RouteDecision> predictViaSidecar(PdfFeatures features) {
        return webClient.post()
                .uri("/predict")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(new PredictRequest(features, null)) // null → use sidecar default threshold
                .retrieve()
                .bodyToMono(RouteDecision.class);
    }

}

package com.example.bds.triage;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class TriageClient {

    private final WebClient web;

    public TriageClient(WebClient.Builder builder, TriageProps props) {
        this.web = builder.baseUrl(props.baseUrl()).build();
    }

    /** POST /predict → { predicted_peak_mb, decision, threshold_mb } */
    public Mono<TriageResponse> predict(byte[] pdfBytes) {
        return web.post()
                .uri("/predict")
                .contentType(MediaType.APPLICATION_PDF)
                .bodyValue(pdfBytes)
                .retrieve()
                .bodyToMono(TriageResponse.class)
                .onErrorResume(ex -> Mono.just(new TriageResponse(-1.0, "UNKNOWN", 3500.0)));
    }

    public record TriageResponse(double predicted_peak_mb, String decision, double threshold_mb) {}
}

package com.example.bds;

import com.example.bds.dto.PdfFeatures;
import com.example.bds.dto.RouteDecision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Service responsible for interacting with the Memory Spike Predictor
 * sidecar service to estimate the peak memory consumption of PDF documents.
 *
 * <p>
 * This class encapsulates the logic for calling the predictor API,
 * handling its response, and mapping it into a {@link RouteDecision}
 * object that downstream components (e.g., controllers) can consume.
 * </p>
 *
 * <h2>Responsibilities:</h2>
 * <ul>
 *   <li>Serialize {@link PdfFeatures} and threshold configuration into
 *       the request payload.</li>
 *   <li>Send a POST request to the predictor endpoint {@code /predict}
 *       using a {@link WebClient} instance provided by Spring.</li>
 *   <li>Deserialize the response and map it into a {@link RouteDecision} DTO.</li>
 *   <li>Provide fail-safe behavior: if the predictor is unavailable or
 *       an error occurs, default to {@code ROUTE_BIG_MEMORY} with a
 *       sentinel prediction value (-1).</li>
 * </ul>
 *
 * <h2>Configuration:</h2>
 * <ul>
 *   <li>The predictor base URL is configured via
 *       {@code triage.base-url} in {@code application.yaml}.</li>
 *   <li>The default memory threshold is injected from
 *       {@code memSpike.thresholdMb} (defaults to 3500 MB).</li>
 * </ul>
 *
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * PdfFeatures features = new PdfFeatures(
 *     18.0, 420, 0.92, 300, 850.0, 0.35, 2, 1, "Unknown"
 * );
 *
 * memorySpikeService.decide(features)
 *     .subscribe(decision -> {
 *         System.out.println("Decision: " + decision.decision());
 *         System.out.println("Predicted MB: " + decision.predicted_peak_mb());
 *     });
 * }</pre>
 */
@Service
public class MemorySpikeService {
    private final WebClient client;
    private final double thresholdMb;

    /**
     * Constructs a new {@code MemorySpikeService}.
     *
     * @param memoryScoreWebClient the {@link WebClient} configured to
     *                             call the predictor sidecar service
     * @param thresholdMb          the memory threshold in megabytes used
     *                             to decide between {@code STANDARD_PATH}
     *                             and {@code ROUTE_BIG_MEMORY}
     */
    public MemorySpikeService(WebClient memoryScoreWebClient,
                              @Value("${memSpike.thresholdMb:3500}") double thresholdMb) {
        this.client = memoryScoreWebClient;
        this.thresholdMb = thresholdMb;
    }

    /**
     * Submits the given {@link PdfFeatures} to the Memory Spike Predictor
     * service and returns a reactive {@link Mono} containing a
     * {@link RouteDecision}.
     *
     * <p>
     * The method wraps the features and threshold into a JSON body, performs
     * a POST request to {@code /predict}, and maps the response into
     * a {@link RouteDecision}. If the request fails, a fallback decision
     * {@code ROUTE_BIG_MEMORY} is returned with a prediction of -1 MB.
     * </p>
     *
     * @param f the {@link PdfFeatures} describing the PDF to be analyzed
     * @return a {@link Mono} that emits the routing decision and predicted
     *         peak memory usage
     */
    public Mono<RouteDecision> decide(PdfFeatures f) {
        var body = Map.of("features", f, "big_mem_threshold_mb", thresholdMb);
        return client.post().uri("/predict")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> new RouteDecision(
                        (String) resp.getOrDefault("decision", "ROUTE_BIG_MEMORY"),
                        ((Number) resp.getOrDefault("predicted_peak_mb", -1)).doubleValue()))
                .onErrorResume(ex -> Mono.just(new RouteDecision("ROUTE_BIG_MEMORY", -1)));
    }
}

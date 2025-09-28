package com.example.bds.ml;

import com.example.bds.dto.PdfFeatures;
import com.example.bds.dto.RouteDecision;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Reactive HTTP client for the ML sidecar's <code>/predict</code> endpoint.
 * <p>
 * This service serializes a {@link PdfFeatures} payload into the sidecar's expected
 * JSON envelope and returns a {@link Mono} of {@link RouteDecision}. It is designed
 * to be used from WebFlux (non-blocking) handler code and is thread-safe as long as
 * the provided {@link WebClient} is thread-safe (Spring's default {@code WebClient}
 * is immutable and thread-safe).
 *
 * <h2>Endpoint contract</h2>
 * <ul>
 *   <li><b>HTTP method:</b> POST</li>
 *   <li><b>Path:</b> <code>/predict</code> (relative to the WebClient's base URL)</li>
 *   <li><b>Request content-type:</b> <code>application/json</code></li>
 *   <li><b>Response content-type:</b> <code>application/json</code></li>
 * </ul>
 *
 * <h3>Request body shape</h3>
 * The sidecar expects a 2-field JSON object:
 * <pre>{@code
 * {
 *   "features": {
 *     "size_mb": 2.1,
 *     "pages": 3,
 *     "image_page_ratio": 0.0,
 *     "dpi_estimate": 150,
 *     "avg_image_size_kb": 0.0,
 *     "fonts_embedded_pct": 1.0,
 *     "xref_error_count": 0,
 *     "ocr_required": 0,
 *     "producer": "UnitTest"
 *   },
 *   "big_mem_threshold_mb": null   // optional; null => use sidecar default
 * }
 * }</pre>
 *
 * <h3>Response body mapping</h3>
 * The JSON response from the sidecar (e.g., <code>{"predicted_peak_mb": ..., "decision": "...", "threshold_mb": ...}</code>)
 * is deserialized into {@link RouteDecision}.
 *
 * <h2>Error semantics</h2>
 * <ul>
 *   <li>Non-2xx responses cause {@link org.springframework.web.reactive.function.client.WebClientResponseException}
 *       to be emitted on the returned {@link Mono}.</li>
 *   <li>Connection timeouts, I/O errors, or JSON mapping issues are surfaced as error signals accordingly.</li>
 *   <li>No retries are performed here; compose {@code retryWhen}, {@code onErrorResume}, etc. at the call site if desired.</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * This service expects a preconfigured {@link WebClient} bean (e.g., named or qualified) with:
 * <ul>
 *   <li>Base URL pointing at the sidecar (e.g., <code>http://127.0.0.1:8000</code> when co-located in the same Pod).</li>
 *   <li>Reasonable connect/read timeouts.</li>
 *   <li>Optional codecs customizations if very large payloads are anticipated.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Mono<RouteDecision> decisionMono = predictionService.predictViaSidecar(features);
 *
 * // Example composition:
 * return decisionMono
 *     .timeout(Duration.ofSeconds(2))
 *     .onErrorResume(err -> Mono.just(new RouteDecision("STANDARD_PATH", -1.0, 3500.0)));
 * }</pre>
 *
 * @since 1.0
 */
@Service
public class PredictionService {

    /**
     * Serializable request envelope sent to the sidecar's <code>/predict</code> endpoint.
     * <p>
     * The {@code big_mem_threshold_mb} field is optional; if {@code null}, the sidecar
     * applies its own default threshold for routing decisions.
     *
     * @param features            validated PDF features to score (must not be {@code null})
     * @param big_mem_threshold_mb optional override for the decision threshold in MB
     */
    public record PredictRequest(PdfFeatures features, Double big_mem_threshold_mb) {}

    private final WebClient webClient;

    /**
     * Create a new {@link PredictionService}.
     *
     * @param memoryScoreWebClient a preconfigured {@link WebClient} whose base URL points
     *                             to the ML sidecar and which has appropriate timeouts/codecs.
     *                             The client is typically provided via Spring configuration,
     *                             e.g.:
     *                             <pre>{@code
     * @Bean
     * @Qualifier("memoryScoreWebClient")
     * WebClient memoryScoreWebClient(@Value("${triage.base-url}") String baseUrl, WebClient.Builder builder) {
     *     return builder.baseUrl(baseUrl).build();
     * }
     *                             }</pre>
     */
    public PredictionService(WebClient memoryScoreWebClient) {
        this.webClient = memoryScoreWebClient;
    }

    /**
     * POST the given {@link PdfFeatures} to the sidecar's <code>/predict</code> endpoint and
     * map the JSON response to a {@link RouteDecision}.
     * <p>
     * The request sets both <em>Content-Type</em> and <em>Accept</em> headers to
     * {@code application/json}. The threshold override is sent as {@code null} so the sidecar
     * uses its configured default.
     *
     * @param features the PDF features to score; must not be {@code null}
     * @return a {@link Mono} that emits a single {@link RouteDecision} on success, or propagates
     *         an error (e.g., {@link org.springframework.web.reactive.function.client.WebClientResponseException})
     *         if the HTTP call fails or the body cannot be decoded
     */
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

package com.example.bds;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * Configuration class for application-wide beans.
 * <p>
 * This class defines and configures reusable Spring beans
 * that can be injected into other components. In particular,
 * it provides a {@link WebClient} instance that will be used
 * to call the PDF Memory Spike Predictor sidecar service.
 * </p>
 *
 * <h2>Responsibilities:</h2>
 * <ul>
 *   <li>Provide a {@link WebClient} configured with a base URL
 *       defined via external configuration (application.yaml).</li>
 *   <li>Configure a Reactor Netty {@link HttpClient} with
 *       a response timeout to avoid long-hanging requests.</li>
 *   <li>Expose the configured {@code WebClient} as a Spring
 *       bean so that it can be injected into services that
 *       communicate with the ML sidecar.</li>
 * </ul>
 *
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * @Service
 * public class MemorySpikeService {
 *     private final WebClient client;
 *
 *     public MemorySpikeService(WebClient memoryScoreWebClient) {
 *         this.client = memoryScoreWebClient;
 *     }
 *
 *     public Mono<ScoreResponse> predict(PdfFeatures f) {
 *         return client.post()
 *             .uri("/predict")
 *             .bodyValue(Map.of("features", f))
 *             .retrieve()
 *             .bodyToMono(ScoreResponse.class);
 *     }
 * }
 * }</pre>
 */
@Configuration
public class DefaultConfiguration {
    /**
     * Creates and configures a {@link WebClient} bean for communicating
     * with the PDF Memory Spike Predictor sidecar.
     *
     * <p>
     * The base URL for the sidecar is injected from the application’s
     * configuration properties (e.g., {@code application.yaml}) using
     * the key {@code triage.base-url}. The underlying HTTP client is
     * set up with a 2-second response timeout to fail fast on slow
     * or unresponsive requests.
     * </p>
     *
     * @param baseUrl the base URL of the ML sidecar service,
     *                typically {@code http://127.0.0.1:8000}
     *                when running as a sidecar in Kubernetes
     * @return a configured {@link WebClient} instance ready to make
     *         requests to the sidecar service
     */
    @Bean
    WebClient memoryScoreWebClient(@Value("${triage.base-url}") String baseUrl) {
        var http = HttpClient.create().responseTimeout(Duration.ofSeconds(2));
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(http))
                .build();
    }
}

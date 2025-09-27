package com.example.bds;

import com.example.bds.dto.PdfFeatures;
import com.example.bds.dto.RouteDecision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.TimeUnit;

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
    private final MeterRegistry meter;

    public MemorySpikeService(WebClient memScoreWebClient,
                              @Value("${memSpike.thresholdMb:3500}") double thresholdMb,
                              MeterRegistry meterRegistry) {
        this.client = memScoreWebClient;
        this.thresholdMb = thresholdMb;
        this.meter = meterRegistry;
    }

    public Mono<RouteDecision> decide(PdfFeatures f) {
        var body = Map.of("features", f, "big_mem_threshold_mb", thresholdMb);
        long t0 = System.nanoTime();
        return client.post().uri("/predict")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> new RouteDecision(
                        (String) resp.getOrDefault("decision", "ROUTE_BIG_MEMORY"),
                        ((Number) resp.getOrDefault("predicted_peak_mb", -1)).doubleValue()))
                .doOnNext(dec -> {
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    Timer.builder("memspike.predict.latency")
                            .tag("decision", dec.decision())
                            .register(meter).record(ms, TimeUnit.MILLISECONDS);
                    DistributionSummary.builder("memspike.predicted.peak_mb")
                            .baseUnit("megabytes")
                            .register(meter).record(dec.predicted_peak_mb());
                    Counter.builder("memspike.decision.count")
                            .tag("decision", dec.decision())
                            .register(meter).increment();
                })
                .onErrorResume(ex -> {
                    Counter.builder("memspike.predict.errors").register(meter).increment();
                    return Mono.just(new RouteDecision("ROUTE_BIG_MEMORY", -1));
                });
    }
}
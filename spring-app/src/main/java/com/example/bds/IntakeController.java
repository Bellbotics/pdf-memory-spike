package com.example.bds;


import com.example.bds.dto.PdfFeatures;
import com.example.bds.dto.RouteDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * REST controller that exposes endpoints for PDF intake routing.
 *
 * <p>
 * The controller acts as the entry point for clients that need
 * to determine how a given PDF should be processed, based on
 * its predicted peak memory usage. Internally, it delegates
 * the prediction logic to the {@link MemorySpikeService}.
 * </p>
 *
 * <h2>Responsibilities:</h2>
 * <ul>
 *   <li>Accept {@link PdfFeatures} JSON payloads describing an uploaded PDF.</li>
 *   <li>Invoke the {@link MemorySpikeService} to obtain a {@link RouteDecision}.</li>
 *   <li>Return the decision to the client as a reactive {@link Mono}.</li>
 * </ul>
 *
 * <h2>Endpoint:</h2>
 * <ul>
 *   <li><b>POST /v1/intake/route</b></li>
 *   <li><b>Consumes:</b> {@code application/json}</li>
 *   <li><b>Produces:</b> {@code application/json}</li>
 * </ul>
 *
 * <h2>Example Request:</h2>
 * <pre>{@code
 * POST /v1/intake/route
 * Content-Type: application/json
 *
 * {
 *   "size_mb": 18.0,
 *   "pages": 420,
 *   "image_page_ratio": 0.92,
 *   "dpi_estimate": 300,
 *   "avg_image_size_kb": 850.0,
 *   "fonts_embedded_pct": 0.35,
 *   "xref_error_count": 2,
 *   "ocr_required": 1,
 *   "producer": "Unknown"
 * }
 * }</pre>
 *
 * <h2>Example Response:</h2>
 * <pre>{@code
 * {
 *   "decision": "ROUTE_BIG_MEMORY",
 *   "predicted_peak_mb": 4820.5
 * }
 * }</pre>
 */
@RestController
@RequestMapping("/v1/intake")
@Slf4j
public class IntakeController {
    private final MemorySpikeService service;

    /**
     * Creates a new {@code IntakeController}.
     *
     * @param service the {@link MemorySpikeService} used to evaluate
     *                PDF features and produce routing decisions
     */
    public IntakeController(MemorySpikeService service) { this.service = service; }

    /**
     * Analyzes the given PDF features and determines the routing
     * decision based on the predicted peak memory usage.
     *
     * <p>
     * This method accepts a {@link PdfFeatures} object as JSON,
     * calls the {@link MemorySpikeService}, and returns a reactive
     * {@link Mono} containing the {@link RouteDecision}.
     * </p>
     *
     * @param features the features of the PDF being analyzed
     * @return a {@link Mono} that emits the routing decision and
     *         predicted peak memory usage in megabytes
     */
    @PostMapping("/route")
    public Mono<RouteDecision> route(@RequestBody PdfFeatures features) {
        long t0 = System.nanoTime();
        return service.decide(features)
                .doOnNext(dec -> {
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    log.info("memSpike decision: decision={}, predicted_peak_mb={}, latency_ms={} pages={}, size_mb={}, producer={}",
                            dec.decision(),
                            dec.predicted_peak_mb(),
                            ms,
                            features.pages(),
                            features.size_mb(),
                            features.producer());
                });
    }

    // tiny helper for kv logging
    private Map<String,Object> kv(String k, Object v) {
        return Map.of(k, v);
    }
}

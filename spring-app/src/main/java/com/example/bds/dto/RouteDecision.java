package com.example.bds.dto;

/**
 * Immutable Data Transfer Object (DTO) representing the
 * outcome of a memory spike prediction for a PDF document.
 *
 * <p>
 * This record is produced after invoking the Memory Spike
 * Predictor (either through the ML sidecar service or an
 * in-process scorer). It captures both the model’s predicted
 * peak memory usage and the routing decision derived from
 * that prediction.
 * </p>
 *
 * <h2>Fields:</h2>
 * <ul>
 *   <li><b>decision</b> – The routing outcome for the document.
 *       Common values include:
 *       <ul>
 *         <li>{@code "ROUTE_BIG_MEMORY"} – indicates the document
 *             should be processed on a high-memory pod or alternate path.</li>
 *         <li>{@code "STANDARD_PATH"} – indicates the document
 *             can be safely processed on a standard pod.</li>
 *       </ul>
 *       The actual string values may be tuned to match the
 *       application’s orchestration logic.</li>
 *
 *   <li><b>predicted_peak_mb</b> – The estimated peak memory
 *       consumption in megabytes (MB) for processing this PDF.
 *       This numeric value comes directly from the regression
 *       model’s output and can be logged, monitored, or compared
 *       against thresholds.</li>
 * </ul>
 *
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * RouteDecision decision = new RouteDecision(
 *     "ROUTE_BIG_MEMORY",
 *     4820.5
 * );
 *
 * System.out.printf("Decision: %s, Predicted Peak MB: %.1f%n",
 *     decision.decision(),
 *     decision.predicted_peak_mb());
 * }</pre>
 */
public record RouteDecision(
        String decision,
        double predicted_peak_mb) {}

package com.example.bds;

import com.example.bds.dto.PdfFeatures;
import com.example.bds.dto.RouteDecision;
import com.example.bds.ml.PredictionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Service that predicts and (optionally) learns a simple “memory spike” model for PDF processing.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li><b>Prediction path:</b> {@link #predictOnly(PdfFeatures)} returns a {@link RouteDecision}.
 *       If a tiny local model is available, it is used; otherwise the request is proxied to the ML
 *       sidecar via {@link PredictionService}.</li>
 *   <li><b>Online training:</b> {@link #train(PdfFeatures, double)} appends labeled rows to a CSV
 *       and retrains a tiny linear model in-process every {@code bds.retrain-every} samples.</li>
 *   <li><b>Model lifecycle:</b> loads a persisted JSON model at startup (async) and persists new
 *       weights after retraining.</li>
 *   <li><b>Observability:</b> emits counters/timers for predictions and sidecar latency.</li>
 * </ul>
 *
 * <h2>Metrics (Micrometer)</h2>
 * <ul>
 *   <li><code>bds.route.decision</code> (counter) — tags:
 *       <ul>
 *         <li><code>decision</code>: {@code STANDARD_PATH} or {@code ROUTE_BIG_MEMORY}</li>
 *         <li><code>source</code>: {@code local}|{@code sidecar}|{@code fallback}</li>
 *       </ul>
 *   </li>
 *   <li><code>bds.sidecar.predict.duration</code> (timer) — latency of calls to sidecar <code>/predict</code></li>
 * </ul>
 *
 * <h2>CSV schema</h2>
 * Rows appended by {@link #appendTrainingRow(PdfFeatures, double)}:
 * <pre>
 * size_mb,pages,image_page_ratio,dpi_estimate,avg_image_size_kb,fonts_embedded_pct,
 * xref_error_count,ocr_required,producer,label_mb
 * </pre>
 *
 * <h2>Thread-safety</h2>
 * <ul>
 *   <li>All public methods are safe to call from reactive chains; file I/O and CPU work are
 *       offloaded to {@code boundedElastic} where noted.</li>
 *   <li>Model reads use a volatile reference; writes are synchronized within retrain/persist logic.</li>
 * </ul>
 *
 * <h2>Error handling</h2>
 * <ul>
 *   <li>Sidecar failures are logged and mapped to a conservative fallback decision
 *       (decision {@code STANDARD_PATH}, predicted {@code -1.0}).</li>
 *   <li>Training I/O errors are logged and skipped; the server continues to run.</li>
 * </ul>
 *
 * <h2>Configuration properties</h2>
 * <ul>
 *   <li><code>bds.data-dir</code> (default <code>data</code>)</li>
 *   <li><code>bds.train-csv</code> (default <code>data/training.csv</code>)</li>
 *   <li><code>bds.model-file</code> (default <code>data/model.json</code>)</li>
 *   <li><code>bds.retrain-every</code> (default <code>5</code>)</li>
 *   <li><code>bds.route-threshold-mb</code> (default <code>3500</code>)</li>
 * </ul>
 *
 * @since 1.0
 */
@Service
@Slf4j
public class MemorySpikeService {

    /** HTTP client to the FastAPI sidecar (/predict). */
    private final PredictionService sidecar;

    /** Jackson mapper for persisting/reading the tiny local model as JSON. */
    private final ObjectMapper mapper;

    /** Base data directory for CSV/model artifacts. */
    private final Path dataDir;

    /** Path to the labeled training CSV. */
    private final Path csvPath;

    /** Path to the persisted local model JSON. */
    private final Path modelPath;

    /** Retrain frequency (every N samples). */
    private final int retrainEvery;

    /** Threshold (MB) to decide routing: >= threshold → ROUTE_BIG_MEMORY. */
    private final double routeThresholdMb;

    /** In-memory model cache; set after async load or retrain. */
    private volatile Model model;

    /** Disposable for the async startup load. */
    private Disposable initDisposable;

    /** Micrometer registry for metrics emission. */
    private final MeterRegistry meterRegistry;

    /**
     * Construct the service with filesystem locations, thresholds, and dependencies.
     * <p>
     * Lightweight filesystem operations (ensuring directories and CSV header) are performed here.
     * Model loading (which may involve disk I/O) is deferred to {@link #initAsyncModelLoad()} on
     * a bounded elastic scheduler.
     *
     * @param sidecar           client to call the sidecar {@code /predict}
     * @param dataDir           base directory for artifacts (property {@code bds.data-dir})
     * @param csvPath           training CSV path (property {@code bds.train-csv})
     * @param modelPath         persisted model path (property {@code bds.model-file})
     * @param retrainEvery      retrain cadence (property {@code bds.retrain-every})
     * @param routeThresholdMb  routing threshold in MB (property {@code bds.route-threshold-mb})
     * @param meterRegistry     (unused) Micrometer registry — see note below
     * @param meterRegistry1    Micrometer registry actually assigned to the field
     *                          <br><b>Note:</b> the constructor accepts two {@link MeterRegistry}
     *                          parameters; only {@code meterRegistry1} is used. Consider removing
     *                          the unused parameter and wiring a single registry.
     * @throws IOException if the data directory or CSV header cannot be created
     */
    public MemorySpikeService(
            PredictionService sidecar,
            @Value("${bds.data-dir:data}") String dataDir,
            @Value("${bds.train-csv:data/training.csv}") String csvPath,
            @Value("${bds.model-file:data/model.json}") String modelPath,
            @Value("${bds.retrain-every:5}") int retrainEvery,
            @Value("${bds.route-threshold-mb:3500}") double routeThresholdMb, MeterRegistry meterRegistry, MeterRegistry meterRegistry1
    ) throws IOException {
        this.sidecar = sidecar;
        this.retrainEvery = retrainEvery;
        this.routeThresholdMb = routeThresholdMb;

        this.dataDir = Paths.get(dataDir);
        this.csvPath = Paths.get(csvPath);
        this.modelPath = Paths.get(modelPath);
        this.meterRegistry = meterRegistry1; // NOTE: only the second registry is used

        // These are quick FS ops; OK to do here.
        Files.createDirectories(this.dataDir);
        ensureCsvHeader();

        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        // Defer potentially heavier disk I/O for model loading to a boundedElastic thread.
        // See @PostConstruct below.
    }

    /**
     * Asynchronously load a previously persisted local model (if present) after the bean is constructed.
     * <p>
     * Uses {@code boundedElastic} to avoid blocking event-loop threads during file I/O.
     * Stores the resulting subscription so it can be disposed in {@link #close()}.
     */
    @PostConstruct
    void initAsyncModelLoad() {
        initDisposable = Mono.fromRunnable(this::loadModelIfExists)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSubscribe(s -> log.info("Initializing local model (async)… path={}", modelPath.toAbsolutePath()))
                .doOnError(e -> log.warn("Async model init failed: {}", e.toString()))
                .subscribe();
    }

    /**
     * Dispose of the async initialization subscription (if any) during bean shutdown.
     */
    @PreDestroy
    void close() {
        if (initDisposable != null) {
            initDisposable.dispose();
        }
    }

    /* ===================== PREDICTION ===================== */

    /**
     * Side-effect-free prediction of a routing decision.
     * <p>
     * If a local model has been loaded/trained, it is used to compute the predicted peak (MB)
     * and corresponding decision. Otherwise, the method calls the sidecar {@code /predict}
     * endpoint. In both flows, metrics are emitted:
     * <ul>
     *   <li><code>bds.route.decision{decision=...,source=local|sidecar|fallback}</code></li>
     *   <li><code>bds.sidecar.predict.duration</code> — only for sidecar calls</li>
     * </ul>
     * If the sidecar call fails, a conservative fallback is returned:
     * <pre>decision=STANDARD_PATH, predicted_peak_mb=-1.0</pre>
     *
     * @param f features for a single document
     * @return a {@link Mono} emitting the {@link RouteDecision}; errors are mapped to fallback
     */
    public Mono<RouteDecision> predictOnly(PdfFeatures f) {
        double local = predictLocalOrNa(f);
        if (!Double.isNaN(local)) {
            final String decision = makeDecision(local);
            meterRegistry.counter("bds.route.decision",
                    "decision", decision,
                    "source", "local").increment();
            return Mono.just(new RouteDecision(decision, round1(local)));
        }
        // No local model → log clearly and use sidecar.
        log.info("No local model loaded or available at {} — using sidecar for prediction.",
                modelPath.toAbsolutePath());
        var sample = Timer.start(meterRegistry);
        return sidecar.predictViaSidecar(f)
                .doOnTerminate(() -> sample.stop(meterRegistry.timer("bds.sidecar.predict.duration")))
                .doOnSubscribe(s -> log.debug("Calling sidecar /predict…"))
                .map(rd -> {
                    meterRegistry.counter("bds.route.decision",
                            "decision", rd.decision(),
                            "source", "sidecar").increment();
                    return rd;
                })
                .onErrorResume(err -> {
                    log.warn("Sidecar predict failed; returning conservative default: {}", err.toString());
                    double fallback = -1.0; // “unknown” sentinel
                    final String decision = "STANDARD_PATH";
                    meterRegistry.counter("bds.route.decision",
                            "decision", decision,
                            "source", "fallback").increment();
                    return Mono.just(new RouteDecision("STANDARD_PATH", fallback));
                });
    }

    /**
     * Convenience alias for {@link #predictOnly(PdfFeatures)}.
     *
     * @param f features for a single document
     * @return a {@link Mono} emitting the decision
     */
    public Mono<RouteDecision> decide(PdfFeatures f) {
        return predictOnly(f);
    }

    /**
     * @return {@code true} if a local model has been loaded or trained in this process
     */
    public boolean hasLocalModel() {
        return model != null;
    }

    /**
     * @return the current routing threshold in megabytes
     */
    public double threshold() {
        return routeThresholdMb;
    }

    /**
     * Count how many labeled rows exist in the training CSV (label &gt;= 0).
     *
     * @return number of usable samples; returns 0 on errors
     */
    public int sampleCount() {
        if (!Files.exists(csvPath)) return 0;
        try (Stream<String> lines = Files.lines(csvPath, StandardCharsets.UTF_8)) {
            long n = lines
                    .skip(1)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(l -> l.split(",", -1))
                    .filter(cols -> cols.length >= 10)
                    .mapToDouble(cols -> parse(cols[9]))
                    .filter(lbl -> lbl >= 0.0)
                    .count();
            return (int) n;
        } catch (IOException e) {
            log.warn("sampleCount failed: {}", e.toString());
            return 0;
        }
    }

    /* ===================== TRAINING ===================== */

    /**
     * Append a labeled row and retrain the tiny local model every N samples.
     * <p>
     * File I/O and CSV scanning are offloaded to {@code boundedElastic} to avoid blocking
     * reactive event-loop threads.
     *
     * @param f               features used as the row's predictors
     * @param measuredPeakMb  observed label (peak memory in MB) to store
     * @return a {@link Mono} completing when the append/retrain flow finishes
     */
    public Mono<Void> train(PdfFeatures f, double measuredPeakMb) {
        // Offload file writes and CSV scanning to boundedElastic to avoid blocking event-loop threads.
        return Mono.fromRunnable(() -> {
                    appendTrainingRow(f, measuredPeakMb);
                    maybeRetrain();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /* ===================== INTERNAL: local model predict ===================== */

    /**
     * Predict using the in-process linear model if available; otherwise return NaN.
     *
     * @param f features
     * @return predicted peak MB, or {@link Double#NaN} if no local model is loaded
     */
    private double predictLocalOrNa(PdfFeatures f) {
        Model m = model;
        if (m == null) return Double.NaN;
        double[] x = com.example.bds.pdf.PdfFeatureExtractor.toVector(f);
        double y = m.bias;
        for (int i = 0; i < m.weights.length; i++) {
            y += m.weights[i] * x[i];
        }
        return y;
    }

    /**
     * Map a predicted peak (MB) to a routing decision using the configured threshold.
     * Negative predictions (e.g., fallbacks) are treated conservatively as {@code STANDARD_PATH}.
     *
     * @param predictedMb predicted peak memory in MB
     * @return {@code ROUTE_BIG_MEMORY} if {@code predictedMb >= threshold()}, else {@code STANDARD_PATH}
     */
    private String makeDecision(double predictedMb) {
        if (predictedMb < 0) return "STANDARD_PATH"; // unknown → conservative
        return (predictedMb >= routeThresholdMb) ? "ROUTE_BIG_MEMORY" : "STANDARD_PATH";
    }

    /* ===================== DATA & RETRAIN ===================== */

    /**
     * Append a single labeled row to the training CSV using a stable column order.
     * Values are rounded/clamped where appropriate; the producer string is CSV-escaped.
     *
     * @param f       features for the predictors
     * @param labelMb observed peak memory (MB)
     */
    private synchronized void appendTrainingRow(PdfFeatures f, double labelMb) {
        try (BufferedWriter w = Files.newBufferedWriter(
                csvPath, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            // CSV: size_mb,pages,image_page_ratio,dpi_estimate,avg_image_size_kb,fonts_embedded_pct,xref_error_count,ocr_required,producer,label_mb
            w.write(String.format("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                    num(f.size_mb()), f.pages(), num(f.image_page_ratio()), f.dpi_estimate(),
                    num(f.avg_image_size_kb()), num(f.fonts_embedded_pct()), f.xref_error_count(), f.ocr_required(),
                    safeCsv(f.producer()), num(labelMb)));
        } catch (IOException e) {
            log.warn("Failed to append training row: {}", e.toString());
        }
    }

    /**
     * If the number of rows is a multiple of {@link #retrainEvery}, retrain the local model
     * from the CSV and persist it.
     * <p>
     * Errors are logged and ignored; no exception is thrown to the caller.
     */
    private synchronized void maybeRetrain() {
        try (Stream<String> lines = Files.lines(csvPath, StandardCharsets.UTF_8)) {
            long rows = lines.skip(1).count();
            if (rows > 0 && rows % retrainEvery == 0) {
                // Train + persist on boundedElastic from caller (train()).
                Model newModel = trainFromCsv(csvPath);
                persistModel(newModel);
                this.model = newModel;
                log.info("Retrained model on {} rows. Weights={}, bias={}", rows, arr(newModel.weights), newModel.bias);
            }
        } catch (IOException e) {
            log.warn("Retrain check failed: {}", e.toString());
        }
    }

    /**
     * Train a tiny linear model from the CSV using batch gradient descent.
     * <p>
     * The model is intentionally simple (no external ML deps) and expects 8 numeric features,
     * excluding the categorical {@code producer}.
     *
     * @param path path to the CSV file
     * @return a new {@link Model} instance
     * @throws IOException if the CSV cannot be read
     */
    private Model trainFromCsv(Path path) throws IOException {
        List<double[]> X = new ArrayList<>();
        List<Double> y = new ArrayList<>();

        try (Stream<String> stream = Files.lines(path, StandardCharsets.UTF_8).skip(1)) {
            stream.forEach(line -> {
                String[] cols = line.split(",", -1);
                if (cols.length < 10) return;
                try {
                    double[] x = new double[8];
                    x[0] = parse(cols[0]); // size_mb
                    x[1] = parse(cols[1]); // pages
                    x[2] = parse(cols[2]); // image_page_ratio
                    x[3] = parse(cols[3]); // dpi_estimate
                    x[4] = parse(cols[4]); // avg_image_size_kb
                    x[5] = parse(cols[5]); // fonts_embedded_pct
                    x[6] = parse(cols[6]); // xref_error_count
                    x[7] = parse(cols[7]); // ocr_required
                    double label = parse(cols[9]); // label_mb
                    if (label >= 0) {
                        X.add(x);
                        y.add(label);
                    }
                } catch (Exception ignore) { }
            });
        }

        if (X.isEmpty()) {
            // no usable rows → zero model
            return new Model(new double[8], 0.0);
        }

        // tiny batch GD (no deps)
        int nFeat = 8;
        double[] w = new double[nFeat];
        double b = 0.0;

        double lr = 1e-5;
        int epochs = 8;

        for (int ep = 0; ep < epochs; ep++) {
            double[] gw = new double[nFeat];
            double gb = 0.0;

            for (int i = 0; i < X.size(); i++) {
                double pred = b;
                double[] xi = X.get(i);
                for (int j = 0; j < nFeat; j++) pred += w[j] * xi[j];
                double err = pred - y.get(i);
                gb += err;
                for (int j = 0; j < nFeat; j++) gw[j] += err * xi[j];
            }
            double invN = 1.0 / X.size();
            gb *= invN;
            for (int j = 0; j < nFeat; j++) gw[j] *= invN;

            b -= lr * gb;
            for (int j = 0; j < nFeat; j++) w[j] -= lr * gw[j];
        }

        return new Model(w, b);
    }

    /* ===================== MODEL IO ===================== */

    /**
     * Load a previously persisted model from {@link #modelPath}, if present.
     * <p>
     * On success, assigns {@link #model}. On any error, logs a warning and keeps
     * running without a local model (sidecar will be used).
     */
    private void loadModelIfExists() {
        if (Files.exists(modelPath)) {
            try {
                this.model = mapper.readValue(Files.readString(modelPath), Model.class);
                log.info("Loaded local model from {}", modelPath.toAbsolutePath());
            } catch (Exception e) {
                log.warn("Failed to read model; starting without one: {}", e.toString());
            }
        } else {
            log.info("No existing local model at {}. Sidecar will be used until a model is trained or provided.",
                    modelPath.toAbsolutePath());
        }
    }

    /**
     * Persist the model to {@link #modelPath} as JSON, overwriting any existing file.
     *
     * @param m model to write
     */
    private synchronized void persistModel(Model m) {
        try {
            Files.createDirectories(modelPath.getParent());
            Files.writeString(
                    modelPath,
                    mapper.writeValueAsString(m),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            log.warn("Failed to persist model: {}", e.toString());
        }
    }

    /**
     * Ensure the training CSV exists and contains a header row. Creates parent
     * directories as needed.
     *
     * @throws IOException if the file cannot be created
     */
    private void ensureCsvHeader() throws IOException {
        if (!Files.exists(csvPath)) {
            Files.createDirectories(csvPath.getParent());
            Files.writeString(
                    csvPath,
                    "size_mb,pages,image_page_ratio,dpi_estimate,avg_image_size_kb,fonts_embedded_pct,xref_error_count,ocr_required,producer,label_mb\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW
            );
        }
    }

    /* ===================== helpers ===================== */

    /**
     * CSV-escape a string value (double-quote quoting).
     *
     * @param s input
     * @return escaped value wrapped in quotes; empty string if null
     */
    private static String safeCsv(String s) {
        if (s == null) return "";
        String v = s.replace("\"", "\"\"");
        return "\"" + v + "\"";
    }

    /**
     * Format a double for CSV with 4 decimal places; returns {@code 0.0} for NaN/Infinity.
     */
    private static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0.0";
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    /**
     * Parse a string to double; returns {@code 0.0} if blank or unparsable.
     */
    private static double parse(String s) {
        if (s == null || s.isBlank()) return 0.0;
        try { return Double.parseDouble(s); } catch (Exception e) { return 0.0; }
    }

    /** Round to 1 decimal place. */
    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    /** Round to 2 decimal places. */
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    /**
     * Pretty-print a double array as <code>[a, b, c]</code> with 2-decimal rounding.
     */
    private static String arr(double[] w) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < w.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(round2(w[i]));
        }
        return sb.append("]").toString();
    }

    /**
     * Immutable, minimal linear model: {@code y = dot(weights, x) + bias}.
     * <p>
     * <b>Dimensions:</b> weights length is 8 (matching the numeric features).
     */
    @Getter
    public static final class Model {
        private final double[] weights; // length 8
        private final double bias;

        public Model(double[] weights, double bias) {
            this.weights = (weights == null ? new double[8] : weights);
            this.bias = bias;
        }

        /** Alias for stats/ML convention. */
        public double[] beta() { return weights; }
        /** Alias for stats/ML convention. */
        public double intercept() { return bias; }
    }

    /**
     * Snapshot of the current in-memory model and dataset state for diagnostics.
     *
     * @param beta         a copy of the current weights (may be empty if no model)
     * @param samples      number of labeled samples known
     * @param thresholdMb  active routing threshold
     */
    public record ModelSnapshot(double[] beta, int samples, double thresholdMb) {}

    /**
     * Create a diagnostic snapshot of the current model, sample count, and threshold.
     *
     * @return a {@link ModelSnapshot} with defensive copying of weights
     */
    public ModelSnapshot snapshot() {
        double[] beta = (this.model != null && this.model.beta() != null)
                ? this.model.beta().clone()
                : new double[0];
        return new ModelSnapshot(beta, sampleCount(), this.routeThresholdMb);
    }
}

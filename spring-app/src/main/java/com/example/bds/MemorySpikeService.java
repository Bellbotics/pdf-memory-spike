package com.example.bds;

import com.example.bds.dto.PdfFeatures;
import com.example.bds.dto.RouteDecision;
import com.example.bds.ml.PredictionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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
 * Clean demo:
 *  • predictOnly(features) → NO training side-effects
 *  • train(features, measuredLabelMb) → append to CSV and retrain every N samples
 *  • decide(features) remains available, but now calls predictOnly() (no side-effects)
 */
@Service
@Slf4j
public class MemorySpikeService {

    private final PredictionService sidecar;     // HTTP client to /predict
    private final ObjectMapper mapper;

    private final Path dataDir;
    private final Path csvPath;
    private final Path modelPath;
    private final int retrainEvery;
    private final double routeThresholdMb;

    // in-memory model cache (lazy-loaded)
    private volatile Model model;

    public MemorySpikeService(
            PredictionService sidecar,
            @Value("${bds.data-dir:data}") String dataDir,
            @Value("${bds.train-csv:data/training.csv}") String csvPath,
            @Value("${bds.model-file:data/model.json}") String modelPath,
            @Value("${bds.retrain-every:5}") int retrainEvery,
            @Value("${bds.route-threshold-mb:3500}") double routeThresholdMb
    ) throws IOException {
        this.sidecar = sidecar;
        this.retrainEvery = retrainEvery;
        this.routeThresholdMb = routeThresholdMb;

        this.dataDir = Paths.get(dataDir);
        this.csvPath = Paths.get(csvPath);
        this.modelPath = Paths.get(modelPath);

        // These are quick FS ops; OK to do here.
        Files.createDirectories(this.dataDir);
        ensureCsvHeader();

        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        // Defer potentially heavier disk I/O for model loading to a boundedElastic thread.
        // See @PostConstruct below.
    }

    @PostConstruct
    void initAsyncModelLoad() {
        Mono.fromRunnable(this::loadModelIfExists)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSubscribe(s -> log.info("Initializing local model (async)… path={}", modelPath.toAbsolutePath()))
                .doOnError(e -> log.warn("Async model init failed: {}", e.toString()))
                .subscribe();
    }

    /* ===================== PREDICTION ===================== */

    /** Side-effect free prediction: uses local model if present, otherwise the sidecar. */
    public Mono<RouteDecision> predictOnly(PdfFeatures f) {
        double local = predictLocalOrNa(f);
        if (!Double.isNaN(local)) {
            return Mono.just(new RouteDecision(makeDecision(local), round1(local)));
        }
        // No local model → log clearly and use sidecar.
        log.info("No local model loaded or available at {} — using sidecar for prediction.",
                modelPath.toAbsolutePath());
        return sidecar.predictViaSidecar(f)
                .doOnSubscribe(s -> log.debug("Calling sidecar /predict…"))
                .onErrorResume(err -> {
                    log.warn("Sidecar predict failed; returning conservative default: {}", err.toString());
                    double fallback = -1.0; // “unknown” sentinel
                    return Mono.just(new RouteDecision("STANDARD_PATH", fallback));
                });
    }

    /** Kept for convenience – calls predictOnly() now (no training). */
    public Mono<RouteDecision> decide(PdfFeatures f) {
        return predictOnly(f);
    }

    /** True if a local model is available. */
    public boolean hasLocalModel() {
        return model != null;
    }

    /** Current routing threshold (MB). */
    public double threshold() {
        return routeThresholdMb;
    }

    /** Current sample count (rows with a non-negative label in CSV). */
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

    /** Public training entry: append row with measured label and retrain every N rows. */
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

    private String makeDecision(double predictedMb) {
        if (predictedMb < 0) return "STANDARD_PATH"; // unknown → conservative
        return (predictedMb >= routeThresholdMb) ? "ROUTE_BIG_MEMORY" : "STANDARD_PATH";
    }

    /* ===================== DATA & RETRAIN ===================== */

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

    private static String safeCsv(String s) {
        if (s == null) return "";
        String v = s.replace("\"", "\"\"");
        return "\"" + v + "\"";
    }

    private static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0.0";
        return String.valueOf(round2(v));
    }

    private static double parse(String s) {
        if (s == null || s.isBlank()) return 0.0;
        try { return Double.parseDouble(s); } catch (Exception e) { return 0.0; }
    }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private static String arr(double[] w) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < w.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(round2(w[i]));
        }
        return sb.append("]").toString();
    }

    @Getter
    public static final class Model {
        private final double[] weights; // length 8
        private final double bias;

        public Model(double[] weights, double bias) {
            this.weights = (weights == null ? new double[8] : weights);
            this.bias = bias;
        }

        // aliases for stats/ML convention (optional)
        public double[] beta() { return weights; }
        public double intercept() { return bias; }
    }

    public record ModelSnapshot(double[] beta, int samples, double thresholdMb) {}

    public ModelSnapshot snapshot() {
        double[] beta = (this.model != null && this.model.beta() != null)
                ? this.model.beta().clone()
                : new double[0];
        return new ModelSnapshot(beta, sampleCount(), this.routeThresholdMb);
    }
}
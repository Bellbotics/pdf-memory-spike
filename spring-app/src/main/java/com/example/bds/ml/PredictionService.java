package com.example.bds.ml;

import com.example.bds.dto.PdfFeatures;
import com.example.bds.pdf.PdfFeatureExtractor;
import com.example.bds.triage.TriageClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class PredictionService {

    private static final double THRESHOLD_MB = 3500.0;

    private final PdfFeatureExtractor extractor = new PdfFeatureExtractor();
    private final OnlineLinearRegression ols = new OnlineLinearRegression();
    private final TrainingDataRepository csv = new TrainingDataRepository();
    private final ModelRepository modelRepo = new ModelRepository();
    private final TriageClient triage;

    public PredictionService(TriageClient triage) {
        this.triage = triage;
        try {
            var mf = modelRepo.load();
            if (mf != null && mf.beta() != null) {
                // warm model
                // (we don’t rebuild X; just reuse coefficients for prediction)
                // no setter on OLS: we "cheat" by predicting with beta via wrapper below
                cachedBeta = mf.beta();
                cachedSamples = mf.samples();
            }
        } catch (Exception ignored) {}
    }

    private volatile double[] cachedBeta; // for /v1/model and cold-start predictions
    private volatile int cachedSamples = 0;

    public record PredictView(double predictedPeakMb, String decision,
                              boolean usingLocalModel, int samples){}

    /** Predict with local model if present; else hit sidecar. */
    public Mono<PredictView> predict(byte[] pdfBytes) {
        return Mono.fromCallable(() -> extractor.extract(pdfBytes))
                .flatMap(f -> {
                    double[] x = PdfFeatureExtractor.toVector(f);
                    double yHat;
                    boolean local = false;
                    double[] beta = ols.trained() ? ols.coefficients() : cachedBeta;

                    if (beta != null) {
                        yHat = beta[0];
                        for (int i=0;i<x.length;i++) yHat += beta[i+1]*x[i];
                        local = true;
                        return Mono.just(new PredictView(yHat, decide(yHat), true, sampleCount()));
                    } else {
                        return triage.predict(pdfBytes)
                                .map(t -> new PredictView(t.predicted_peak_mb(),
                                        decide(t.predicted_peak_mb()), false, sampleCount()));
                    }
                });
    }

    /** Train from measured label; retrain every 5 samples; persist model.json */
    public Mono<Void> train(byte[] pdfBytes, double measuredPeakMb) {
        return Mono.fromCallable(() -> {
            PdfFeatures f = extractor.extract(pdfBytes);
            int n = csv.append(f, measuredPeakMb);
            ols.add(PdfFeatureExtractor.toVector(f), measuredPeakMb);
            if (n % 5 == 0) {
                ols.refitIf(1);
                if (ols.trained()) {
                    cachedBeta = ols.coefficients();
                    cachedSamples = ols.samples();
                    modelRepo.save(cachedBeta, cachedSamples);
                }
            }
            return 0;
        }).then();
    }

    public int sampleCount(){ return Math.max(cachedSamples, ols.samples()); }
    public double[] coefficients(){ return ols.trained()? ols.coefficients() : cachedBeta; }
    public double threshold(){ return THRESHOLD_MB; }

    private String decide(double mb){ return mb >= THRESHOLD_MB ? "ROUTE_BIG_MEMORY" : "STANDARD_PATH"; }
}

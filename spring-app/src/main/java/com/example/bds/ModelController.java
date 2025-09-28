package com.example.bds;

import com.example.bds.ml.PredictionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/model")
public class ModelController {

    private final PredictionService svc;
    public ModelController(PredictionService svc){ this.svc = svc; }

    @GetMapping
    public ModelView view() {
        return new ModelView(svc.coefficients(), svc.sampleCount(), svc.threshold());
    }

    public record ModelView(double[] beta, int samples, double threshold_mb) {}
}

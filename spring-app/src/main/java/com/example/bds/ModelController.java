package com.example.bds;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/model")
public class ModelController {

    private final MemorySpikeService svc;
    public ModelController(MemorySpikeService svc){ this.svc = svc; }

    @GetMapping
    public ModelView view() {
        var snap = svc.snapshot();
        return new ModelView(snap.beta(), snap.samples(), snap.thresholdMb());
    }

    public record ModelView(double[] beta, int samples, double threshold_mb) {}
}
package com.example.bds.ml;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ModelRepository {
    private final Path path = Paths.get("data", "model.json");
    private final ObjectMapper om = new ObjectMapper();

    public record ModelFile(double[] beta, int samples){}

    public void save(double[] beta, int samples) throws IOException {
        Files.createDirectories(path.getParent());
        om.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), new ModelFile(beta, samples));
    }

    public ModelFile load() throws IOException {
        if (Files.notExists(path)) return null;
        return om.readValue(path.toFile(), ModelFile.class);
    }
}

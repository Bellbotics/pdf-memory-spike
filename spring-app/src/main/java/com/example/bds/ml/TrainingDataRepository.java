package com.example.bds.ml;

import com.example.bds.dto.PdfFeatures;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

public class TrainingDataRepository {
    private final Path path = Paths.get("data", "training.csv");
    private final AtomicInteger count = new AtomicInteger(0);

    public TrainingDataRepository() {
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) {
                Files.writeString(path, header()+"\n");
            } else {
                // count rows minus header
                try (var br = Files.newBufferedReader(path)) {
                    int lines = (int) br.lines().count();
                    count.set(Math.max(0, lines - 1));
                }
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public int append(PdfFeatures f, double labelMb) {
        String row = String.join(",",
                d(f.size_mb()), i(f.pages()), d(f.image_page_ratio()), i(f.dpi_estimate()),
                d(f.avg_image_size_kb()), d(f.fonts_embedded_pct()), i(f.xref_error_count()),
                i(f.ocr_required()), s(f.producer()), d(labelMb)
        );
        try (var fw = new FileWriter(path.toFile(), true);
             var bw = new BufferedWriter(fw)) {
            bw.write(row); bw.newLine();
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return count.incrementAndGet();
    }

    public int count() { return count.get(); }

    private static String header() {
        return "size_mb,pages,image_page_ratio,dpi_estimate,avg_image_size_kb,fonts_embedded_pct," +
                "xref_error_count,ocr_required,producer,label_peak_mb";
    }

    private static String d(double v){ return String.format(java.util.Locale.ROOT, "%.6f", v); }
    private static String i(int v){ return Integer.toString(v); }
    private static String s(String v){ return "\""+ v.replace("\"","\"\"") +"\""; }
}

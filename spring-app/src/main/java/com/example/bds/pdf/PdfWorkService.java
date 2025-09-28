package com.example.bds.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;

@Service
public class PdfWorkService {
    /** Simple processing to create memory pressure for the demo. */
    public void processForDemo(byte[] pdfBytes) {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            if (doc.getNumberOfPages() > 0) {
                var renderer = new PDFRenderer(doc);
                BufferedImage img = renderer.renderImageWithDPI(0, 150); // allocate pixels
                // touch pixels lightly so JIT doesn't drop them (no-op)
                int w = img.getWidth(), h = img.getHeight();
                if (w + h == -1) throw new IllegalStateException("never happens");
            }
        } catch (Exception e) {
            // For demo we swallow; in real code, propagate or log.
        }
    }
}

package com.example.bds.pdf;

import com.example.bds.dto.PdfFeatures;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.IOException;

public class PdfFeatureExtractor {

    /**
     * Simple, robust extractor that fills every field with a reasonable value (no NaNs).
     * Heuristics are stable so uploads are reproducible across runs.
     */
    public PdfFeatures extract(byte[] pdfBytes) throws IOException {
        double sizeMb = (pdfBytes == null ? 0 : pdfBytes.length) / (1024.0 * 1024.0);

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            if (doc.isEncrypted()) throw new IllegalArgumentException("Encrypted PDFs are not supported");
            if (doc.getNumberOfPages() > 5000) throw new IllegalArgumentException("PDF too large (pages)");

            int pages = Math.max(0, doc.getNumberOfPages());
            int imagePages = 0;
            int imagesSeen = 0;
            long imageBytesApprox = 0;

            // Very defensive defaults
            int dpiSum = 0;
            double fontsEmbeddedPct = 0.0;

            for (int i = 0; i < pages; i++) {
                PDPage page = doc.getPage(i);
                PDResources res = page.getResources();

                boolean pageHasImage = false;
                if (res != null) {
                    // images
                    for (var name : res.getXObjectNames()) {
                        PDXObject xo = res.getXObject(name);
                        if (xo instanceof PDImageXObject img) {
                            pageHasImage = true;
                            imagesSeen++;
                            // PDF doesn’t store original image file size directly; use width*height as a stable proxy.
                            imageBytesApprox += Math.max(1L, (long) img.getWidth() * Math.max(1, img.getHeight()));
                        }
                    }

                    // crude "embedded fonts" heuristic: if page declares fonts, treat half as embedded on average
                    int fontCount = 0;
                    for (var fn : res.getFontNames()) fontCount++;
                    if (fontCount > 0) {
                        // heuristically: more declared fonts -> more likely some are embedded
                        fontsEmbeddedPct += Math.min(1.0, 0.5 + 0.05 * Math.min(10, fontCount));
                    }
                }

                if (pageHasImage) imagePages++;

                // give pages with images higher “dpi” heuristic
                dpiSum += pageHasImage ? 300 : 150;
            }

            double imagePageRatio = pages == 0 ? 0.0 : (imagePages / (double) pages);
            int dpiEstimate = pages == 0 ? 0 : (dpiSum / Math.max(1, pages));

            // average image "size" proxy => normalize the proxy counts back into KB scale
            double avgImageSizeKb;
            if (imagesSeen == 0) {
                avgImageSizeKb = 0.0;
            } else {
                // scale the proxy (pixels) to a rough KB number to keep values sane
                double proxyAvg = imageBytesApprox / (double) imagesSeen;
                avgImageSizeKb = Math.max(8.0, proxyAvg / 2048.0);
            }

            // page-level average of our heuristic embedded-fonts score
            double fontsPct = pages == 0 ? 0.0 : clamp01(fontsEmbeddedPct / pages);

            // keep 0 for xref_error_count unless the parser threw (we’re in the success path now)
            int xrefErrorCount = 0;

            // OCR likely if most pages look like images
            int ocrRequired = imagePageRatio > 0.6 ? 1 : 0;

            String producer = "Unknown";
            PDDocumentInformation info = doc.getDocumentInformation();
            if (info != null && info.getProducer() != null) producer = info.getProducer();

            return new PdfFeatures(
                    round2(sizeMb),
                    pages,
                    clamp01(imagePageRatio),
                    dpiEstimate,
                    round2(avgImageSizeKb),
                    clamp01(fontsPct),
                    xrefErrorCount,
                    ocrRequired,
                    producer
            );
        } catch (IOException e) {
            throw new IllegalArgumentException("Unreadable PDF (corrupt or truncated)");
        }
    }

    public static double[] toVector(PdfFeatures f) {
        // 8 numeric inputs (exclude 'producer' in this minimal demo)
        return new double[] {
                f.size_mb(),
                f.pages(),
                f.image_page_ratio(),
                f.dpi_estimate(),
                f.avg_image_size_kb(),
                f.fonts_embedded_pct(),
                f.xref_error_count(),
                f.ocr_required()
        };
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
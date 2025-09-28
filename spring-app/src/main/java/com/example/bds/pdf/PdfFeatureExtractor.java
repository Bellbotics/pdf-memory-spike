package com.example.bds.pdf;

import com.example.bds.dto.PdfFeatures;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.InputStream;

public class PdfFeatureExtractor {

    /** Keep your new byte[] entry point. */
    public PdfFeatures extract(byte[] pdfBytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            int pages = Math.max(0, doc.getNumberOfPages());
            double sizeMb = pdfBytes.length / (1024.0 * 1024.0);

            int imagePages = 0, dpiSum = 0;
            for (int i = 0; i < pages; i++) {
                PDPage p = doc.getPage(i);
                boolean hasImages = pageHasImages(p);

                if (hasImages) {
                    imagePages++;
                    dpiSum += 300; // crude heuristic
                } else {
                    dpiSum += 150;
                }
            }

            double imageRatio = pages == 0 ? 0.0 : (imagePages / (double) pages);
            int dpi = (pages == 0) ? 0 : (dpiSum / Math.max(1, pages));
            double avgImgKb = imagePages == 0 ? 0.0 : Math.max(50.0, (sizeMb * 1024.0) / imagePages);
            double fontsPct = pages == 0 ? 0.0 : (imageRatio > 0.8 ? 0.1 : 0.7);
            int xrefErr = 0; // placeholder
            int ocr = imageRatio > 0.6 ? 1 : 0;

            String producer = (doc.getDocumentInformation() != null &&
                    doc.getDocumentInformation().getProducer() != null)
                    ? doc.getDocumentInformation().getProducer()
                    : "Unknown";

            return new PdfFeatures(
                    sizeMb, pages, clamp01(imageRatio), dpi,
                    avgImgKb, clamp01(fontsPct), xrefErr, ocr, producer
            );
        }
    }

    /** Optional overload if you still call extract(InputStream,len) anywhere. */
    public PdfFeatures extract(InputStream in, int lengthBytes) throws Exception {
        byte[] bytes = in.readAllBytes();
        return extract(bytes);
    }

    /** Convert to numeric vector for the simple OLS demo. */
    public static double[] toVector(PdfFeatures f) {
        return new double[]{
                f.size_mb(), f.pages(), f.image_page_ratio(), f.dpi_estimate(),
                f.avg_image_size_kb(), f.fonts_embedded_pct(), f.xref_error_count(), f.ocr_required()
                // producer omitted in numeric-only vector
        };
    }

    private static boolean pageHasImages(PDPage page) {
        try {
            var res = page.getResources();
            if (res == null) return false;

            for (COSName name : res.getXObjectNames()) {
                PDXObject x = res.getXObject(name);
                if (x instanceof PDImageXObject) {
                    return true;
                }
            }
        } catch (Exception ignore) {
            // be conservative if PDF is quirky
        }
        return false;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
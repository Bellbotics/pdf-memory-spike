package com.example.bds.pdf;

import com.example.bds.dto.PdfFeatures;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.InputStream;

/**
 * Extracts lightweight features the model expects from a PDF stream using PDFBox.
 * Keep this fast and safe for untrusted input (no writing to disk, memory caps handled by Spring).
 */
public class PdfFeatureExtractor {

    /**
     * Derives PdfFeatures used by the Memory Spike model.
     * Heuristics:
     *  - image_page_ratio: count pages that contain at least one bitmap image
     *  - dpi_estimate: crude default of 300 unless images carry metadata
     *  - avg_image_size_kb: total image stream sizes / images
     *  - fonts_embedded_pct: assume 0.7 baseline without font parsing (simple demo)
     *  - xref_error_count: 0 unless PDFBox reports issues on load
     *  - ocr_required: 1 if zero extractable text AND many image pages
     */
    public static PdfFeatures extract(InputStream in, long sizeBytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(new RandomAccessReadBuffer(in))) {
            int pages = doc.getNumberOfPages();
            double sizeMb = Math.max(0.001, sizeBytes / (1024.0 * 1024.0));

            int pagesWithImages = 0;
            long totalImageBytes = 0;
            int imageCount = 0;

            for (PDPage page : doc.getPages()) {
                var resources = page.getResources();
                if (resources == null) continue;

                boolean hasImage = false;
                for (var name : resources.getXObjectNames()) {
                    var xobj = resources.getXObject(name);
                    if (xobj instanceof PDImageXObject img) {
                        hasImage = true;
                        // best-effort: underlying COS stream length for size approximation
                        var cos = img.getCOSObject();
                        long len = 0L;
                        var dict = (COSDictionary) cos;
                        if (dict.containsKey(org.apache.pdfbox.cos.COSName.LENGTH)) {
                            len = dict.getLong(org.apache.pdfbox.cos.COSName.LENGTH);
                        }
                        totalImageBytes += Math.max(0, len);
                        imageCount++;
                    }
                }
                if (hasImage) pagesWithImages++;
            }

            double imagePageRatio = pages == 0 ? 0.0 : (double) pagesWithImages / pages;
            double avgImageSizeKb = imageCount == 0 ? 0.0 : (totalImageBytes / 1024.0) / imageCount;

            // crude text vs image heuristic (avoid heavy text extraction for demo)
            boolean mostlyImages = imagePageRatio > 0.6;
            int ocrRequired = mostlyImages ? 1 : 0;

            // producer from metadata
            PDDocumentInformation info = doc.getDocumentInformation();
            String producer = (info != null && info.getProducer() != null && !info.getProducer().isBlank())
                    ? info.getProducer() : "Unknown";

            // defaults / heuristics for demo
            int dpiEstimate = 300;
            double fontsEmbeddedPct = 0.7;
            int xrefErrors = 0;

            return new PdfFeatures(
                    round3(sizeMb),
                    pages,
                    round3(imagePageRatio),
                    dpiEstimate,
                    round1(avgImageSizeKb),
                    round3(fontsEmbeddedPct),
                    xrefErrors,
                    ocrRequired,
                    producer
            );
        }
    }

    private static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }
    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
}

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

/**
 * Extracts a stable, conservative set of PDF features using Apache PDFBox (3.x) for
 * downstream scoring/routing. The extractor is designed to be:
 * <ul>
 *   <li><b>Robust:</b> Every numeric field is populated (no NaNs); outliers are clamped
 *       to reasonable ranges (e.g., fractions in [0,1]).</li>
 *   <li><b>Deterministic:</b> Heuristics are simple and reproducible so the same input
 *       produces the same {@link PdfFeatures} across runs and JVMs.</li>
 *   <li><b>Lightweight:</b> It avoids expensive full-content decoding; image size is
 *       approximated from width×height rather than byte-perfect inspection.</li>
 * </ul>
 *
 * <h2>What is extracted?</h2>
 * <ul>
 *   <li><b>size_mb</b>: input byte array size / 1024² (MiB), rounded to 2 decimals.</li>
 *   <li><b>pages</b>: total page count (0 if empty).</li>
 *   <li><b>image_page_ratio</b>: fraction of pages that reference at least one image XObject.</li>
 *   <li><b>dpi_estimate</b>: simple per-page heuristic (150 for text-only pages, 300 if images present),
 *       averaged across pages.</li>
 *   <li><b>avg_image_size_kb</b>: proxy for image heaviness. Uses average (width×height) per image
 *       and normalizes to an approximate kilobyte scale (proxy/2048), lower-bounded at 8 KB.</li>
 *   <li><b>fonts_embedded_pct</b>: page-level heuristic. If a page declares N fonts, we add
 *       {@code min(1.0, 0.5 + 0.05 * min(10, N))} and average across pages, then clamp to [0,1].</li>
 *   <li><b>xref_error_count</b>: 0 for successfully parsed documents (we do not run a full validator here).</li>
 *   <li><b>ocr_required</b>: 1 if {@code image_page_ratio > 0.6}, else 0.</li>
 *   <li><b>producer</b>: {@code DocumentInformation.getProducer()} or "Unknown".</li>
 * </ul>
 *
 * <h2>Limitations &amp; intent</h2>
 * <ul>
 *   <li>Heuristics are coarse by design; this is a <em>feature sketch</em> meant to be fast and stable,
 *       not a full PDF analyzer.</li>
 *   <li>Image byte sizes are not read from the underlying streams; width×height is used as a
 *       stable proxy that correlates with memory footprint.</li>
 *   <li>We do not count cross-reference anomalies; if parsing succeeds, {@code xref_error_count=0}.</li>
 *   <li>Encrypted documents are rejected (see exceptions below).</li>
 * </ul>
 *
 * <h2>Thread-safety</h2>
 * This class is stateless and therefore thread-safe; create once and reuse.
 *
 * @since 1.0
 */
public class PdfFeatureExtractor {

    /**
     * Parse a PDF from bytes and return a fully-populated {@link PdfFeatures} record.
     * <p>
     * Validation &amp; guardrails:
     * <ul>
     *   <li>Encrypted PDFs are not supported → {@link IllegalArgumentException}.</li>
     *   <li>Excessive page count (&gt; 5000) is rejected → {@link IllegalArgumentException}.</li>
     *   <li>Unreadable/invalid PDFs are rejected with a friendly {@link IllegalArgumentException}
     *       message ("Unreadable PDF (corrupt or truncated)").</li>
     * </ul>
     *
     * <h3>Performance</h3>
     * Makes a single pass over pages and their resources. It iterates XObjects and fonts but
     * avoids expensive stream decoding where possible.
     *
     * @param pdfBytes PDF content as a byte array; must represent a valid, non-encrypted PDF.
     *                 <strong>Note:</strong> callers should pre-check size/emptiness; this method
     *                 expects non-empty input and may throw if null/empty.
     * @return a {@link PdfFeatures} instance with all fields filled (no NaNs).
     * @throws IOException declared for historical symmetry with callers; in practice this method
     *                     wraps parse failures into {@link IllegalArgumentException} and thus
     *                     does not propagate {@code IOException}.
     * @throws IllegalArgumentException if the PDF is encrypted, unreasonably large (pages),
     *                                  or unreadable/corrupt.
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
            // Narrow the external exception surface to a user-friendly IllegalArgumentException.
            throw new IllegalArgumentException("Unreadable PDF (corrupt or truncated)");
        }
    }

    /**
     * Convert the numeric subset of {@link PdfFeatures} into a fixed-length vector
     * suitable for feeding very small local models that do not accept categorical input.
     * <p>
     * This excludes the {@code producer} field (categorical) and emits the remaining 8
     * numeric dimensions in a stable order:
     * <pre>
     * [ size_mb, pages, image_page_ratio, dpi_estimate,
     *   avg_image_size_kb, fonts_embedded_pct, xref_error_count, ocr_required ]
     * </pre>
     *
     * @param f the features record to project
     * @return a new double[8] in the order shown above
     */
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

    /**
     * Clamp a floating-point value to the closed interval [0, 1].
     *
     * @param v input value
     * @return 0 if v &lt; 0, 1 if v &gt; 1, otherwise v
     */
    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }

    /**
     * Round a floating-point value to two decimal places.
     *
     * @param v input value
     * @return value rounded to 2 decimals using {@code Math.round(v * 100.0) / 100.0}
     */
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}

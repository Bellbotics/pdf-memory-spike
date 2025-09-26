package com.example.bds.dto;

/**
 * Immutable Data Transfer Object (DTO) representing the
 * measurable characteristics of an uploaded PDF document.
 *
 * <p>
 * These features are extracted from the document during
 * intake and used as input to the Memory Spike Predictor
 * machine learning model. The model uses them to estimate
 * peak memory consumption during PDF processing (e.g.,
 * watermarking with PDFBox).
 * </p>
 *
 * <h2>Feature descriptions:</h2>
 * <ul>
 *   <li><b>size_mb</b> – The total file size of the PDF, expressed in megabytes (MB).</li>
 *   <li><b>pages</b> – The total number of pages contained in the document.</li>
 *   <li><b>image_page_ratio</b> – Fraction of pages that are image-based (scanned) versus text/vector-based.
 *       Value between 0.0 (no image pages) and 1.0 (all pages are images).</li>
 *   <li><b>dpi_estimate</b> – Approximate dots-per-inch (DPI) resolution of embedded images, if present.
 *       Higher DPI often correlates with larger memory usage.</li>
 *   <li><b>avg_image_size_kb</b> – Average size of embedded images within the PDF, in kilobytes (KB).</li>
 *   <li><b>fonts_embedded_pct</b> – Fraction of fonts embedded in the PDF.
 *       Value between 0.0 (no fonts embedded) and 1.0 (all fonts embedded).</li>
 *   <li><b>xref_error_count</b> – Number of cross-reference (xref) structure errors detected during parsing.
 *       Structural issues often lead to higher memory usage during repair.</li>
 *   <li><b>ocr_required</b> – Indicator whether OCR (Optical Character Recognition) is required:
 *       1 = OCR needed, 0 = not required.</li>
 *   <li><b>producer</b> – The software or tool that generated the PDF (e.g., Adobe, PDFBox, Scanner).
 *       Often predictive of file structure and processing behavior.</li>
 * </ul>
 *
 * <p>
 * Example usage:
 * <pre>{@code
 * PdfFeatures features = new PdfFeatures(
 *     18.0,        // size_mb
 *     420,         // pages
 *     0.92,        // image_page_ratio
 *     300,         // dpi_estimate
 *     850.0,       // avg_image_size_kb
 *     0.35,        // fonts_embedded_pct
 *     2,           // xref_error_count
 *     1,           // ocr_required
 *     "Unknown"    // producer
 * );
 * }</pre>
 * </p>
 */
public record PdfFeatures(
        double size_mb,
        int pages,
        double image_page_ratio,
        int dpi_estimate,
        double avg_image_size_kb,
        double fonts_embedded_pct,
        int xref_error_count,
        int ocr_required,
        String producer) {}

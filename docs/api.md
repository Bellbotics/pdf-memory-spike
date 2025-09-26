# PDF Memory Spike Predictor API

This document describes the REST API exposed by the **Intake Controller** in the `pdf-memory-spike` project.  
It enables clients to estimate the peak memory usage of an uploaded PDF and decide which processing path to use.

---

## Base URL
```
http://<host>:8033/v1/intake
```

In local development (with `kubectl port-forward`):
```
http://localhost:8033/v1/intake
```

---

## Endpoints

### `POST /route`

Analyze PDF features and return a routing decision.

- **Consumes:** `application/json`
- **Produces:** `application/json`

#### Request Body
A JSON object representing the features of the PDF:

| Field                | Type    | Description                                                                 |
|---------------------|---------|-----------------------------------------------------------------------------|
| `size_mb`           | number  | Total file size of the PDF (in MB).                                         |
| `pages`             | integer | Total number of pages.                                                      |
| `image_page_ratio`  | number  | Fraction of pages that are image-based (0.0 to 1.0).                        |
| `dpi_estimate`      | integer | Approximate DPI of embedded images.                                         |
| `avg_image_size_kb` | number  | Average size of embedded images (in KB).                                    |
| `fonts_embedded_pct`| number  | Fraction of fonts embedded (0.0 to 1.0).                                    |
| `xref_error_count`  | integer | Number of cross-reference (xref) errors detected.                           |
| `ocr_required`      | integer | Whether OCR is required (`1 = yes`, `0 = no`).                              |
| `producer`          | string  | The software that produced the PDF (e.g., `Adobe`, `PDFBox`, `Scanner`).    |

#### Example Request
```http
POST /v1/intake/route
Content-Type: application/json

{
  "size_mb": 18.0,
  "pages": 420,
  "image_page_ratio": 0.92,
  "dpi_estimate": 300,
  "avg_image_size_kb": 850.0,
  "fonts_embedded_pct": 0.35,
  "xref_error_count": 2,
  "ocr_required": 1,
  "producer": "Unknown"
}
```

---

#### Response
A JSON object containing the predicted peak memory usage and the routing decision.

| Field               | Type    | Description                                                                 |
|---------------------|---------|-----------------------------------------------------------------------------|
| `decision`          | string  | Routing decision (`STANDARD_PATH` or `ROUTE_BIG_MEMORY`).                   |
| `predicted_peak_mb` | number  | Predicted peak memory usage in MB.                                          |

#### Example Response
```json
{
  "decision": "ROUTE_BIG_MEMORY",
  "predicted_peak_mb": 4820.5
}
```

---

## Error Handling
- If the predictor sidecar is unreachable or an error occurs:
    - The fallback decision will be `ROUTE_BIG_MEMORY`.
    - The predicted value will be `-1`.

Example:
```json
{
  "decision": "ROUTE_BIG_MEMORY",
  "predicted_peak_mb": -1.0
}
```

---

## Notes for Developers
- The threshold for deciding between `STANDARD_PATH` and `ROUTE_BIG_MEMORY` is configurable via:
```yaml
memSpike:
  thresholdMb: 3500
```
- Actual threshold tuning should be based on observed pod memory envelopes in production.

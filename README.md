# PDF Memory Spike Demo (Spring WebFlux + FastAPI Sidecar)

Predict and route heavy PDF processing before it happens.

This project demonstrates a production-style pattern for memory-aware routing:

1) A user uploads a PDF to a Spring WebFlux service.
2) The service extracts fast, cheap features (size, pages, image ratio, etc.) using Apache PDFBox.
3) It calls a Python FastAPI sidecar to predict peak RAM needed.
4) Based on the prediction, it routes the job to a standard or big-memory path.
5) It measures actual peak memory (demo workload), logs Micrometer metrics, and (optionally) trains a tiny local model online.

---

## Why this matters

- Avoid OOMs: predict spikes before work starts; route outliers.
- Control cost: keep defaults on cheaper nodes; use big-mem only when needed.
- Resilience: sidecar model + local model + conservative fallback.
- Observability: Micrometer + Actuator (Datadog optional).

---

## Architecture

```mermaid
flowchart LR
  A[Browser / Client] -->|multipart/form-data (PDF)| B[/Spring WebFlux\nUploadController/]
  B -->|extract features (PDFBox)| C[PdfFeatureExtractor]
  C -->|PdfFeatures| D[MemorySpikeService]

  subgraph Routing & Learning
    D -->|predict| E[PredictionService\nWebClient -> Sidecar]
    E -->|POST /predict\nJSON { features }| F[FastAPI Sidecar\nscikit-learn Pipeline]
    F -->|predicted_peak_mb, decision| D
    D -->|execute small workload & sample RSS| G[MemorySampler]
    G -->|measured_peak_mb| D
    D -->|append| H[spring-app/data/training.csv]
    D -->|retrain every N| I[OnlineLinearRegression]
    I -->|persist| J[spring-app/data/model.json]
  end

  D -->|decision + telemetry| K[(Micrometer/Actuator)]
  D -->|JSON response| A
```

---

## Project structure (abridged)

```
.
├── k8s/
│   ├── deployment.yaml
│   ├── k8s.yaml
│   └── service.yaml
├── kind/
│   └── kind-config.yaml
├── notebooks/
│   └── memory_spike_predictor.ipynb
├── sidecar/
│   ├── models/
│   │   ├── metrics.json
│   │   ├── pipeline.pkl
│   │   └── sample_data.csv
│   ├── .dockerignore
│   ├── app.py
│   ├── Dockerfile.sidecar
│   └── requirements.txt
├── spring-app/
│   ├── src/
│   │   ├── main/
│   │   └── test/
│   ├── .dockerignore
│   ├── Dockerfile.spring
│   ├── pom.xml
│   └── README.md
├── tools/
│   ├── build_dataset.sh
│   ├── pdf-features-extract.sh
│   └── pdf_features_py.py
├── training/
│   ├── memory_spike_train.py
│   ├── README.md
│   └── requirements.txt
├── .gitignore
├── docker-compose.yml
├── Makefile
├── skaffold.yaml
└── Tiltfile
```

---

## Components

### Spring WebFlux service (spring-app/)
- UploadController — POST /v1/upload/pdf
- IntakeController — POST /v1/intake/route
- ModelController — GET /v1/model
- PdfFeatureExtractor — PDFBox feature extraction
- PredictionService — calls sidecar with body { "features": { ... } }
- MemorySpikeService — local/sidecar/fallback prediction, CSV append, periodic retrain, metrics
- Observability — Micrometer + Actuator (bds.route.decision, bds.pdf.extract.duration, etc.)

### FastAPI sidecar (sidecar/)
- POST /predict — expects an enveloped body: { "features": ..., "big_mem_threshold_mb": 3500.0 }
- Model artifacts under sidecar/models/ (pipeline.pkl, metrics.json)

### Training (training/)
- memory_spike_train.py — builds/refreshes sidecar model artifacts

---

## Getting Started (local)

### Option A — Docker Compose
```bash
# from repo root
docker compose up --build
# Spring UI      -> http://127.0.0.1:8033/
# Sidecar health -> http://127.0.0.1:8000/health
```

### Option B — Local processes (Python + Maven)

1) Sidecar on :8000
```bash
cd sidecar
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn app:app --host 127.0.0.1 --port 8000 --reload
curl -s http://127.0.0.1:8000/health
```

2) Spring on :8033
```bash
cd spring-app
./mvnw -q spring-boot:run   -Dspring-boot.run.profiles=local   -Dspring-boot.run.arguments="--triage.base-url=http://127.0.0.1:8000 --bds.retrain-every=1"
curl -s http://127.0.0.1:8033/actuator/health
```

3) Upload via the browser UI
- Open http://127.0.0.1:8033/
- Choose a small PDF (e.g., spring-app/src/test/resources/samples/text.pdf) and click Upload.
- The response JSON includes trained_this_upload, measured_peak_mb, and model usage flags.

4) CLI alternative
```bash
FILE="spring-app/src/test/resources/samples/text.pdf"
curl -s -F "file=@${FILE};type=application/pdf"   http://127.0.0.1:8033/v1/upload/pdf | jq
```

5) Inspect training/model (after one upload)
```bash
tail -n 5 spring-app/data/training.csv
cat spring-app/data/model.json | jq
```

6) Metrics
```bash
curl -s http://127.0.0.1:8033/actuator/metrics/bds.route.decision | jq
curl -s http://127.0.0.1:8033/actuator/metrics/bds.pdf.extract.duration | jq
curl -s http://127.0.0.1:8033/actuator/metrics/bds.sidecar.predict.duration | jq
```

---

## How the ML works

Two loops:

1) Sidecar model (Python / scikit-learn) — trained offline/periodically (e.g., Gradient Boosting). Returns predicted_peak_mb; decision by threshold (default 3500 MB).
2) Local model (Java / tiny linear regression) — each upload appends (features, measured MB) to CSV; every N rows retrains and persists model.json.

Features (from PdfFeatureExtractor): size_mb, pages, image_page_ratio, dpi_estimate, avg_image_size_kb, fonts_embedded_pct, xref_error_count, ocr_required, producer.

---

## Configuration (keys used)

- triage.base-url — sidecar URL (required)
- bds.max-bytes — size cap (default 50 MiB)
- bds.data-dir, bds.train-csv, bds.model-file
- bds.retrain-every — e.g., 1 for demos
- bds.route-threshold-mb — default 3500
- Actuator exposure:
    - application.yaml: health,metrics,prometheus
    - application-local.yaml: adds info,env (dev only)
- Datadog export: disabled in local; enable in prod as needed

You may also pass:
```
-Dtriage.base-url=http://127.0.0.1:8000
```

---

## Testing

```bash
cd spring-app
./mvnw -q test
```

Integration tests (WireMock) verify:
- body shape to /predict is { "features": Ellipsis }
- response mapping and status

Manual:
```bash
FILE="spring-app/src/test/resources/samples/text.pdf"
curl -s -F "file=@${FILE};type=application/pdf" http://127.0.0.1:8033/v1/upload/pdf | jq
```

---

## Troubleshooting

- Port 8033 in use: lsof -nP -iTCP:8033 -sTCP:LISTEN -> kill PID or run with --server.port=8040.
- Datadog Unauthorized: use local profile or disable: --management.metrics.export.datadog.enabled=false.
- source=fallback & predicted_peak_mb=-1.0: sidecar unreachable and no local model yet. Check :8000/health and triage.base-url.
- /actuator/env missing: exposed only in local profile.

---

## License

MIT (or your org's standard)

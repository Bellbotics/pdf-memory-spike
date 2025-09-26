# Training: Memory Spike Predictor

This folder contains the **training source code** for building the PDF Memory Spike Predictor model.  
It produces a scikit-learn `Pipeline` (`pipeline.pkl`) that estimates the **peak memory usage (MB)** of PDF processing jobs.

---

## Files

- `memory_spike_train.py` → main training script
- `requirements.txt` → Python dependencies
- `sample_data.csv` → small sample dataset for quick testing (generated automatically)
- `pipeline.pkl` → trained model pipeline (binary artifact)
- `metrics.json` → evaluation metrics for the latest model

---

## Quickstart

```bash
cd training
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

Train the model with synthetic data:

```bash
python memory_spike_train.py --out-dir ../sidecar/models --seed 42
```

This will create:

- `../sidecar/models/pipeline.pkl`
- `../sidecar/models/metrics.json`
- `../sidecar/models/sample_data.csv`

---

## Command-line Arguments

| Argument      | Default      | Description                                                                 |
|---------------|--------------|-----------------------------------------------------------------------------|
| `--data`      | *(none)*     | Path to a CSV containing features + `peak_mem_mb`. If not provided, synthetic data is generated. |
| `--out-dir`   | `./models`   | Directory to save `pipeline.pkl`, `metrics.json`, and `sample_data.csv`.    |
| `--seed`      | `42`         | Random seed for reproducibility.                                            |

---

## Metrics Produced

- **MAE** → Mean Absolute Error
- **RMSE** → Root Mean Squared Error
- **R²** → Coefficient of determination
- **MAPE %** → Mean Absolute Percentage Error

Metrics are written to `metrics.json` after training.

Example:

```json
{
  "mae": 55.2,
  "rmse": 69.7,
  "r2": 0.874,
  "mape_pct": 17.3,
  "n_train": 5600,
  "n_test": 1400,
  "seed": 42
}
```
---

## 🧪 Testing the Model Interactively

Once you’ve trained a model, you can test it directly from a Python REPL or script:

```python
import pickle
import pandas as pd

# Load pipeline
with open("../sidecar/models/pipeline.pkl", "rb") as f:
    pipe = pickle.load(f)

# Example input features (dict)
features = {
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

# Convert to DataFrame and predict
predicted_peak_mb = pipe.predict(pd.DataFrame([features]))[0]
print("Predicted peak memory (MB):", predicted_peak_mb)
```

---

## Extending

- Replace `load_data()` in `memory_spike_train.py` with your own loader to bring in real, sanitized PDF feature datasets.
- Adjust `GradientBoostingRegressor` hyperparameters in `train()`.
- Consider exporting to ONNX for JVM-native inference if you want to avoid Python in production.

---

## Best Practices

- **Version models**: keep `pipeline_v1.pkl`, `pipeline_v2.pkl`, etc.
- **Track metrics**: check if each new version improves MAE/RMSE before promoting.
- **Avoid committing big binaries**: consider Git LFS or storing models in S3 / artifact storage.
- **Keep training code in Git**: ensures full reproducibility.  

---

## 🚚 Deploying to Spring API

This section shows how to serve `pipeline.pkl` behind a small **FastAPI sidecar** and call it from your **Spring Boot** app.

### 1) Sidecar (FastAPI) — local dev

**Run the predictor locally (no Docker needed):**
```bash
# from repo root (make sure ../sidecar/models/pipeline.pkl exists)
cd sidecar
python -m pip install fastapi uvicorn scikit-learn==1.4.2 pandas==2.2.2 pydantic==2.8.2
uvicorn app:app --reload --host 127.0.0.1 --port 8000
```

**Healthcheck:**
```bash
curl http://127.0.0.1:8000/healthz
```

**Predict (example):**
```bash
curl -s -X POST http://127.0.0.1:8000/predict \
  -H "Content-Type: application/json" \
  -d '{
    "features": {
      "size_mb": 18.0,
      "pages": 420,
      "image_page_ratio": 0.92,
      "dpi_estimate": 300,
      "avg_image_size_kb": 850.0,
      "fonts_embedded_pct": 0.35,
      "xref_error_count": 2,
      "ocr_required": 1,
      "producer": "Unknown"
    },
    "big_mem_threshold_mb": 3500
  }' | jq
```

You should see a payload like:
```json
{ "predicted_peak_mb": 4800.4, "decision": "ROUTE_BIG_MEMORY", "threshold_mb": 3500.0 }
```

---

### 2) Spring Boot wiring

**application.yaml**
```yaml
server:
  port: 8033
triage:
  base-url: http://127.0.0.1:8000
memSpike:
  thresholdMb: 3500
```

**WebClient bean**
```java
@Bean
WebClient memScoreWebClient(@Value("${triage.base-url}") String baseUrl) {
  var http = reactor.netty.http.client.HttpClient.create()
      .responseTimeout(java.time.Duration.ofSeconds(2));
  return WebClient.builder()
      .baseUrl(baseUrl)
      .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(http))
      .build();
}
```

**Service call (returns decision for routing)**
```java
@PostMapping("/v1/intake/route")
public reactor.core.publisher.Mono<com.example.bds.dto.RouteDecision>
route(@RequestBody com.example.bds.dto.PdfFeatures features) {
  return memorySpikeService.decide(features);
}
```

**End-to-end test (Spring → sidecar)**
```bash
curl -s -X POST http://localhost:8033/v1/intake/route \
  -H "Content-Type: application/json" \
  -d '{
    "size_mb": 18.0,
    "pages": 420,
    "image_page_ratio": 0.92,
    "dpi_estimate": 300,
    "avg_image_size_kb": 850.0,
    "fonts_embedded_pct": 0.35,
    "xref_error_count": 2,
    "ocr_required": 1,
    "producer": "Unknown"
  }' | jq
```

---

### 3) Containerize the sidecar (optional)

**Dockerfile (sidecar)**
```dockerfile
FROM python:3.11-slim
WORKDIR /app
COPY models/pipeline.pkl /models/pipeline.pkl
COPY app.py /app/app.py
RUN pip install --no-cache-dir fastapi uvicorn scikit-learn==1.4.2 pandas==2.2.2 pydantic==2.8.2
EXPOSE 8000
CMD ["uvicorn","app:app","--host","127.0.0.1","--port","8000","--workers","1"]
```

**Build & run**
```bash
docker build -t mem-spike-scorer:dev -f sidecar/Dockerfile.sidecar sidecar
docker run --rm -p 8000:8000 mem-spike-scorer:dev
```

Point Spring at `http://127.0.0.1:8000` (as above).

---

### 4) Kubernetes (dev with kind)

**Two containers in one Pod** (Spring + sidecar). The Spring container calls the sidecar at `127.0.0.1:8000` (shared network namespace).

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: bds
  namespace: pdf-ml
spec:
  replicas: 1
  selector: { matchLabels: { app: bds } }
  template:
    metadata: { labels: { app: bds } }
    spec:
      containers:
        - name: bds-app
          image: localhost:5001/bds-app:dev
          ports: [{ containerPort: 8033, name: http }]
          env:
            - name: TRIAGE_BASE_URL
              value: http://127.0.0.1:8000
            - name: MEMSPIKE_THRESHOLD_MB
              value: "3500"
          readinessProbe:
            httpGet: { path: /actuator/health, port: http }
            initialDelaySeconds: 5
        - name: mem-spike-scorer
          image: localhost:5001/mem-spike-scorer:dev
          ports: [{ containerPort: 8000, name: memscore }]
          readinessProbe:
            httpGet: { path: /healthz, port: memscore }
            initialDelaySeconds: 3
```

Expose Spring:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: bds
  namespace: pdf-ml
spec:
  selector: { app: bds }
  ports:
    - port: 8033
      targetPort: http
      name: http
```

Port-forward and test:
```bash
kubectl -n pdf-ml port-forward svc/bds 8033:8033
curl -s -X POST http://localhost:8033/v1/intake/route -H "Content-Type: application/json" -d @request.json | jq
```

---

### 5) Rollout tips

- **Shadow mode first**: log `predicted_peak_mb` vs real pod peak memory; don’t change routing yet.
- **Threshold tuning**: start at `3500 MB`, adjust based on your pod class envelopes (p95/p99).
- **Versioning**: keep `models/pipeline_vX.pkl` and log `model_version` with predictions.
- **Fallback**: if sidecar is unavailable, default to `ROUTE_BIG_MEMORY` to be safe.

---

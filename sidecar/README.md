# FastAPI Memory Spike Sidecar

Predicts peak RAM (MB) for a PDF from its features and returns a routing decision.

## Run

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn app:app --host 127.0.0.1 --port 8000 --reload
```

Health: http://127.0.0.1:8000/health

## API

POST /predict
- Request body (features must be enveloped):
```json
{
  "features": {
    "size_mb": 2.1,
    "pages": 3,
    "image_page_ratio": 0.0,
    "dpi_estimate": 150,
    "avg_image_size_kb": 0.0,
    "fonts_embedded_pct": 1.0,
    "xref_error_count": 0,
    "ocr_required": 0,
    "producer": "UnitTest"
  },
  "big_mem_threshold_mb": 3500.0
}
```
- Response:
```json
{
  "predicted_peak_mb": 150.9,
  "decision": "STANDARD_PATH",
  "threshold_mb": 3500.0,
  "model_hash": "..."
}
```

Model artifacts: sidecar/models/ -> pipeline.pkl, metrics.json, sample_data.csv

To refresh:
```bash
cd ..
python training/memory_spike_train.py --out-dir sidecar/models --seed 7
```

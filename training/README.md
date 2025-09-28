# Training (scikit-learn)

Offline training for the sidecar model.

## Run

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python memory_spike_train.py --out-dir ../sidecar/models --seed 42
```

Outputs
- pipeline.pkl — scikit-learn Pipeline
- metrics.json — metrics summary (e.g., MAE, R2)

The feature schema mirrors spring-app/src/main/java/com/example/bds/dto/PdfFeatures.java.

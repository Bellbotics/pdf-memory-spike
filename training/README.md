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

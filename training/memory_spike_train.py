#!/usr/bin/env python3
"""
Train Memory Spike Predictor (scikit-learn Pipeline) and export:
  - pipeline.pkl
  - metrics.json
  - model_manifest.json
  - sample_data.csv

Usage:
  python memory_spike_train.py --out-dir ../sidecar/models --seed 7

Description
-----------
This script builds a regression model to predict the *peak memory usage (MB)*
for processing PDF documents. It uses a scikit-learn Pipeline with:

  - ColumnTransformer:
      • OneHotEncoder for the categorical "producer" feature
      • passthrough for numeric features
  - GradientBoostingRegressor for nonlinear regression

Artifacts produced:
  - pipeline.pkl          (trained scikit-learn Pipeline)
  - metrics.json          (evaluation metrics on holdout test set)
  - model_manifest.json   (created_at + metrics + feature columns)
  - sample_data.csv       (random 500-row sample of training data, for quick testing)

How to extend
-------------
- Replace `load_data()` with a function that loads your real features dataset
  (CSV/Parquet/etc.), ensuring the schema matches the PdfFeatures your app sends.
- Tune model hyperparameters (e.g., n_estimators, max_depth) in `train()`.
- Integrate model retraining into CI/CD for versioning and reproducibility.
"""

import os, json, argparse
from datetime import datetime
from pathlib import Path
import numpy as np, pandas as pd
from sklearn.model_selection import train_test_split
from sklearn.compose import ColumnTransformer
from sklearn.preprocessing import OneHotEncoder
from sklearn.pipeline import Pipeline
from sklearn.ensemble import GradientBoostingRegressor
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score


def synthesize_data(n=7000, seed=7) -> pd.DataFrame:
    """
    Synthesize a dataset that *resembles* PDF feature distributions and a plausible
    relationship to peak memory usage.

    Args:
        n   : number of rows to generate
        seed: RNG seed for reproducibility

    Returns:
        DataFrame with columns:
          - size_mb, pages, image_page_ratio, dpi_estimate, avg_image_size_kb,
            fonts_embedded_pct, xref_error_count, ocr_required, producer, peak_mem_mb

    Notes:
        • Distributions (lognormal/beta/etc.) are chosen to mimic skew often seen in real PDFs.
        • `peak_mem_mb` is generated via a latent function + noise, then clipped to [150, 12000].
        • `producer` adds a categorical bias to reflect differences among authoring tools.
    """
    rng = np.random.default_rng(seed)

    # -----------------------------
    # Numeric feature generators
    # -----------------------------
    size_mb = rng.lognormal(mean=1.3, sigma=0.7, size=n)                        # heavy right tail
    pages = np.clip((rng.normal(30, 30, size=n)).round().astype(int), 1, 1500)  # many short, some long
    image_page_ratio = np.clip(rng.beta(2.2, 3.5, size=n), 0, 1)                # mostly text-like
    dpi_estimate = np.clip((rng.normal(220, 80, size=n)).round().astype(int), 72, 600)
    avg_image_size_kb = np.clip(rng.lognormal(mean=4.8, sigma=0.6, size=n), 20, 5000)
    fonts_embedded_pct = np.clip(rng.normal(0.75, 0.18, size=n), 0, 1)
    xref_error_count = rng.poisson(0.06, size=n)
    ocr_required = rng.integers(0, 2, size=n)                                   # 0/1 flag

    # Categorical feature — typical PDF producers (toy distribution)
    producer = rng.choice(
        ["Adobe","iText","PDFBox","Ghostscript","Unknown","Scanner"],
        size=n, p=[0.35,0.12,0.12,0.08,0.18,0.15]
    )

    # -----------------------------
    # Latent relationship (toy)
    # -----------------------------
    latent = (
            60
            + 22*np.log1p(size_mb)                              # memory grows ~log with size
            + 0.08*pages                                        # more pages -> more data/objects
            + 900*(image_page_ratio**1.7)                       # image-heavy pages drive spikes
            + 0.006*np.clip(dpi_estimate-150,0,None)*pages**0.3 # hi-DPI amplifies per-page cost
            + 0.03*avg_image_size_kb                            # bigger images -> more RAM
            + 240*(1-fonts_embedded_pct)                        # missing embedded fonts hinder pipeline
            + 65*np.tanh(xref_error_count)                      # xref issues cost some overhead
            + 120*ocr_required*(image_page_ratio > 0.5)         # OCR needed on image-y docs
    )

    # Per-producer bias — pretend some tools yield larger memory footprints
    producer_bias = {
        "Adobe": -40.0,
        "iText": -10.0,
        "PDFBox": 0.0,
        "Ghostscript": 20.0,
        "Unknown": 25.0,
        "Scanner": 70.0
    }
    latent += np.vectorize(producer_bias.get)(producer)

    # Add heteroscedastic noise (variance rises with latent), then clip to sane range
    noise = rng.normal(0, 60 + 0.6*np.sqrt(np.maximum(latent,1)), size=n)
    peak_mem_mb = np.clip(latent + noise, 150, 12000)

    # Assemble frame (rounded for compactness/realism)
    return pd.DataFrame(dict(
        size_mb=np.round(size_mb, 3),
        pages=pages,
        image_page_ratio=np.round(image_page_ratio, 3),
        dpi_estimate=dpi_estimate,
        avg_image_size_kb=np.round(avg_image_size_kb, 1),
        fonts_embedded_pct=np.round(fonts_embedded_pct, 3),
        xref_error_count=xref_error_count,
        ocr_required=ocr_required.astype(int),
        producer=producer,
        peak_mem_mb=np.round(peak_mem_mb, 1),
    ))


def load_data(path=None, seed=7) -> pd.DataFrame:
    """
    Load dataset from a CSV file (if provided) or synthesize new data.

    Args:
        path: optional file path to CSV containing the full schema (including peak_mem_mb)
        seed: RNG seed for synthetic data

    Returns:
        DataFrame ready for training
    """
    if path and os.path.exists(path):
        return pd.read_csv(path)
    return synthesize_data(seed=seed)


def train(df: pd.DataFrame, seed=42):
    """
    Train the Memory Spike Predictor pipeline and compute evaluation metrics.

    Pipeline:
      - ColumnTransformer:
          * OneHotEncoder for ['producer'] (handle_unknown='ignore' to keep inference robust)
          * passthrough numeric features (no scaling here; GBDT is tree-based)
      - GradientBoostingRegressor (default params; tune as needed)

    Args:
        df  : DataFrame with features + target column `peak_mem_mb`
        seed: RNG seed used for the train/test split

    Returns:
        (pipe, metrics) where:
          - pipe: fitted scikit-learn Pipeline
          - metrics: dict with MAE, RMSE, R2, MAPE, split sizes, and seed
    """
    # Split features/target
    X = df.drop(columns=["peak_mem_mb"])
    y = df["peak_mem_mb"].values

    # Feature schema (must match what the Spring app/sidecar expects)
    num_features = [
        "size_mb","pages","image_page_ratio","dpi_estimate",
        "avg_image_size_kb","fonts_embedded_pct",
        "xref_error_count","ocr_required"
    ]
    cat_features = ["producer"]

    # Preprocess: OneHot encode categorical, pass numeric through unchanged
    pre = ColumnTransformer([
        ("cat", OneHotEncoder(handle_unknown="ignore"), cat_features),
        ("num", "passthrough", num_features),
    ])

    # Regressor: gradient boosting is a strong baseline for tabular data
    pipe = Pipeline([
        ("pre", pre),
        ("gbr", GradientBoostingRegressor(random_state=0))
    ])

    # Deterministic split; keep test set stable across runs
    Xtr, Xte, ytr, yte = train_test_split(X, y, test_size=0.2, random_state=seed)

    # Fit pipeline on the training portion only
    pipe.fit(Xtr, ytr)

    # Evaluate on holdout
    ypred = pipe.predict(Xte)
    metrics = {
        "mae": float(mean_absolute_error(yte, ypred)),                          # mean |error|
        "rmse": float(mean_squared_error(yte, ypred, squared=False)),           # sqrt(MSE)
        "r2": float(r2_score(yte, ypred)),                                      # coefficient of determination
        "mape_pct": float(np.mean(np.abs((yte - ypred) / np.maximum(yte, 1e-6))) * 100.0),  # robust MAPE
        "n_train": int(len(Xtr)),
        "n_test": int(len(Xte)),
        "seed": int(seed),
    }
    return pipe, metrics


def main():
    """
    CLI entry point.

    Flags:
      --data     : optional CSV path; if absent, synthetic data is generated
      --out-dir  : output directory for artifacts (created if missing)
      --seed     : RNG seed for reproducibility (train/test split & synthesis)
    """
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", help="CSV with features + peak_mem_mb (optional)")
    ap.add_argument("--out-dir", default="./models",
                    help="Where to write pipeline.pkl/metrics.json/model_manifest.json")
    ap.add_argument("--seed", type=int, default=42,
                    help="Random seed for reproducibility")
    args = ap.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    # -----------------------------
    # Load or synthesize data
    # -----------------------------
    df = load_data(args.data, seed=args.seed)

    # -----------------------------
    # Train + compute metrics
    # -----------------------------
    pipe, metrics = train(df, seed=args.seed)

    # -----------------------------
    # Persist artifacts
    # -----------------------------
    pk_path = out_dir / "pipeline.pkl"
    with open(pk_path, "wb") as f:
        import pickle
        pickle.dump(pipe, f)  # Pickled Pipeline is what the FastAPI sidecar loads

    with open(out_dir / "metrics.json", "w") as f:
        json.dump(metrics, f, indent=2)

    # Manifest: captures creation time, metrics, and feature columns used
    X_cols = list(df.drop(columns=["peak_mem_mb"]).columns)
    manifest = {
        "created_at": datetime.utcnow().isoformat() + "Z",  # UTC timestamp
        "metrics": metrics,                                  # copy of metrics.json content
        "features": X_cols,                                  # model-ready input columns (order matters)
    }
    (out_dir / "model_manifest.json").write_text(json.dumps(manifest, indent=2))

    # -----------------------------
    # Smoke test the pickle
    # -----------------------------
    # Rationale:
    #   - Catch serialization/version issues early (before shipping to prod).
    #   - Ensures ColumnTransformer can accept a 1-row DataFrame with the right columns.
    try:
        import pickle
        sample_row = df.drop(columns=["peak_mem_mb"]).iloc[0]
        sample_df = pd.DataFrame([sample_row.to_dict()])
        _ = pickle.load(open(pk_path, "rb")).predict(sample_df)
        print("Smoke test: OK — loaded pipeline.pkl and predicted on 1-row sample.")
    except Exception as e:
        # Fail fast if the pickle is broken or incompatible
        raise RuntimeError(f"Smoke test FAILED: {e}") from e

    # Provide a small sample for human inspection / quick manual calls
    df.sample(min(500, len(df)), random_state=1).to_csv(out_dir / "sample_data.csv", index=False)

    print("Saved:", str(pk_path))
    print("Metrics:", json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()

"""
FastAPI sidecar service for Memory Spike Predictor.

This service loads a pre-trained scikit-learn Pipeline (pipeline.pkl) and
exposes a REST API to score PDF feature sets. The model predicts the
peak memory usage (in MB) of processing a given PDF and returns a
routing decision based on a configurable threshold.

Endpoints:
  - GET  /healthz   → health check
  - POST /predict   → run inference and return predicted MB + decision
"""
from __future__ import annotations

from fastapi import FastAPI
from pydantic import BaseModel, Field
import pickle
import pandas as pd

# -------------------------------------------------------------------
# Constants and model loading
# -------------------------------------------------------------------

# Path inside the container where the trained pipeline.pkl is mounted.
PIPELINE_PATH = "/models/pipeline.pkl"

# Load the trained scikit-learn Pipeline into memory at startup.
# This keeps inference fast because the model is only unpickled once.
pipe = pickle.load(open(PIPELINE_PATH, "rb"))

# Default threshold (MB) for routing.
# If predicted peak >= threshold → "ROUTE_BIG_MEMORY"
# else → "STANDARD_PATH".
DEFAULT_THRESHOLD_MB = 3500.0


# -------------------------------------------------------------------
# Request models (Pydantic for validation & schema generation)
# -------------------------------------------------------------------

class PdfFeatures(BaseModel):
    """
    Input feature set describing a PDF document.

    Attributes:
        size_mb (float): File size in megabytes.
        pages (int): Total number of pages.
        image_page_ratio (float): Fraction of pages that are image-based (0.0–1.0).
        dpi_estimate (int): Estimated DPI of embedded images.
        avg_image_size_kb (float): Average image size in kilobytes.
        fonts_embedded_pct (float): Fraction of fonts embedded (0.0–1.0).
        xref_error_count (int): Number of cross-reference errors.
        ocr_required (int): 1 if OCR is required, 0 otherwise.
        producer (str): Software that produced the PDF (e.g., "Adobe", "PDFBox").
    """
    size_mb: float
    pages: int
    image_page_ratio: float = Field(ge=0, le=1)
    dpi_estimate: int
    avg_image_size_kb: float
    fonts_embedded_pct: float = Field(ge=0, le=1)
    xref_error_count: int
    ocr_required: int = Field(ge=0, le=1)
    producer: str


class ScoreRequest(BaseModel):
    """
    Request payload for the /predict endpoint.

    Attributes:
        features (PdfFeatures): The PDF features to score.
        big_mem_threshold_mb (float | None): Optional threshold override.
            If not provided, DEFAULT_THRESHOLD_MB is used.
    """
    features: PdfFeatures
    big_mem_threshold_mb: float | None = None


# -------------------------------------------------------------------
# FastAPI app setup
# -------------------------------------------------------------------

# Initialize FastAPI application.
# By default, docs will be available at /docs (Swagger) and /redoc.
app = FastAPI()


# -------------------------------------------------------------------
# Endpoints
# -------------------------------------------------------------------

@app.get("/healthz")
def healthz():
    """
    Liveness/health check endpoint.

    Returns:
        dict: {"status": "ok"} if the service is up.
    """
    return {"status": "ok"}


@app.post("/predict")
def predict(req: ScoreRequest):
    """
    Score a PDF feature set using the pre-trained pipeline.

    Args:
        req (ScoreRequest): Request containing PdfFeatures and optional threshold.

    Returns:
        dict: JSON response with:
            - predicted_peak_mb (float): predicted peak memory usage in MB
            - decision (str): routing decision ("STANDARD_PATH" or "ROUTE_BIG_MEMORY")
            - threshold_mb (float): threshold used for the decision
    """
    # Convert Pydantic model to a DataFrame (scikit-learn expects tabular input).
    df = pd.DataFrame([req.features.model_dump()])

    # Run model prediction (single float).
    y = float(pipe.predict(df)[0])

    # Use provided threshold or fall back to default.
    thr = req.big_mem_threshold_mb or DEFAULT_THRESHOLD_MB

    # Decision logic: route to big-memory path if predicted usage >= threshold.
    decision = "ROUTE_BIG_MEMORY" if y >= thr else "STANDARD_PATH"

    # Construct response payload.
    return {
        "predicted_peak_mb": y,
        "decision": decision,
        "threshold_mb": thr,
    }

"""Partie H ADRIA : apprentissage et évaluation d'un détecteur de fraude.

Exécution depuis fraud-detection/ :
    python fraud_detection.py --data data/bank_transactions.csv
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import sys

# Évite la surallocation des threads BLAS sur ce petit jeu et certains
# blocages d'initialisation natifs Windows. Respecte un réglage utilisateur.
os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")
os.environ.setdefault("OMP_NUM_THREADS", "1")

import joblib
import matplotlib

matplotlib.use("Agg")  # Fonctionne également sans interface graphique.
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import scipy
import sklearn
from sklearn.compose import ColumnTransformer
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    ConfusionMatrixDisplay,
    PrecisionRecallDisplay,
    average_precision_score,
    classification_report,
    confusion_matrix,
    f1_score,
    precision_score,
    recall_score,
)
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler


BASE_DIR = Path(__file__).resolve().parent
NUMERIC_FEATURES = [
    "amount", "hour", "transactions_last_24h", "average_amount_last_7d"
]
CATEGORICAL_FEATURES = ["country", "merchant_category"]
FEATURES = NUMERIC_FEATURES + CATEGORICAL_FEATURES
REQUIRED_COLUMNS = ["transaction_id", "account_id", *FEATURES, "is_fraud"]
RANDOM_STATE = 42
THRESHOLD = 0.5  # Fixé a priori ; aucune optimisation sur le test.


def load_and_validate(path: Path) -> pd.DataFrame:
    """Vérifie le schéma et la possibilité d'une séparation stratifiée."""
    if not path.is_file():
        raise ValueError(f"CSV introuvable : {path}")
    data = pd.read_csv(path)
    missing = sorted(set(REQUIRED_COLUMNS) - set(data.columns))
    if missing:
        raise ValueError(f"Colonnes obligatoires absentes : {missing}")
    if data.empty:
        raise ValueError("Le CSV ne contient aucune transaction.")
    target = data["is_fraud"]
    if target.isna().any() or not target.isin([0, 1]).all():
        raise ValueError("is_fraud doit contenir uniquement 0 et 1, sans valeur manquante.")
    counts = target.value_counts()
    if set(counts.index) != {0, 1}:
        raise ValueError("Les deux classes 0 et 1 doivent être présentes.")
    if counts.min() < 2:
        raise ValueError("Il faut au moins deux observations par classe pour stratifier.")
    n_test = math.ceil(len(data) * 0.2)
    if n_test < 2 or len(data) - n_test < 2:
        raise ValueError("Effectif insuffisant pour placer les deux classes dans train et test.")
    for column in NUMERIC_FEATURES:
        data[column] = pd.to_numeric(data[column], errors="raise")
        if np.isinf(data[column].to_numpy(dtype=float)).any():
            raise ValueError(f"Valeur infinie interdite dans {column}.")
    return data


def explore(data: pd.DataFrame, results_dir: Path) -> None:
    print("\nPremières lignes :\n", data.head().to_string(index=False))
    print("\nForme :", data.shape)
    print("\nTypes :\n", data.dtypes.to_string())
    print("\nValeurs manquantes :\n", data.isna().sum().to_string())
    counts = data["is_fraud"].value_counts().sort_index()
    print("\nClasses présentes :", counts.index.tolist())
    print("Effectifs :", counts.to_dict())
    print(f"Pourcentage de fraudes : {100 * data['is_fraud'].mean():.2f} %")
    fig, ax = plt.subplots(figsize=(6, 4))
    bars = ax.bar(["Légitime (0)", "Fraude (1)"], counts.values,
                  color=["#2471a3", "#c0392b"])
    ax.bar_label(bars)
    ax.set(title="Répartition des classes — CSV fourni", ylabel="Transactions")
    fig.tight_layout()
    fig.savefig(results_dir / "class_distribution.png", dpi=160)
    plt.close(fig)


def build_pipeline() -> Pipeline:
    preprocessing = ColumnTransformer([
        ("numeric", Pipeline([
            ("imputer", SimpleImputer(strategy="median")),
            ("scaler", StandardScaler()),
        ]), NUMERIC_FEATURES),
        ("categorical", Pipeline([
            ("imputer", SimpleImputer(strategy="most_frequent")),
            ("encoder", OneHotEncoder(handle_unknown="ignore")),
        ]), CATEGORICAL_FEATURES),
    ])
    return Pipeline([
        ("preprocessing", preprocessing),
        ("classifier", LogisticRegression(
            class_weight="balanced", max_iter=1000, random_state=RANDOM_STATE
        )),
    ])


def run(data_path: Path, output_dir: Path) -> dict:
    data = load_and_validate(data_path)
    results_dir = output_dir / "results"
    models_dir = output_dir / "models"
    results_dir.mkdir(parents=True, exist_ok=True)
    models_dir.mkdir(parents=True, exist_ok=True)
    explore(data, results_dir)

    # Les identifiants et la cible sont exclus explicitement des variables.
    X = data[FEATURES]
    y = data["is_fraud"].astype(int)
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, random_state=RANDOM_STATE, stratify=y
    )
    for name, subset in [("train", y_train), ("test", y_test)]:
        if set(subset.unique()) != {0, 1}:
            raise ValueError(
                f"La séparation {name} ne contient pas les deux classes ; "
                "davantage d'exemples minoritaires sont nécessaires."
            )
    # Une colonne entièrement vide ne permet pas de calculer son imputation.
    empty_features = X_train.columns[X_train.isna().all()].tolist()
    if empty_features:
        raise ValueError(f"Variables entièrement manquantes dans le train : {empty_features}")
    print(f"\nTrain : {len(y_train)} lignes, classes {y_train.value_counts().to_dict()}")
    print(f"Test : {len(y_test)} lignes, classes {y_test.value_counts().to_dict()}")

    pipeline = build_pipeline()
    pipeline.fit(X_train, y_train)  # Tous les ajustements se font sur le train.
    scores = pipeline.predict_proba(X_test)[:, 1]
    predictions = (scores >= THRESHOLD).astype(int)
    matrix = confusion_matrix(y_test, predictions, labels=[0, 1])
    report = classification_report(
        y_test, predictions, labels=[0, 1],
        target_names=["legitime", "fraude"], zero_division=0
    )
    metrics = {
        "precision_fraud": float(precision_score(y_test, predictions, zero_division=0)),
        "recall_fraud": float(recall_score(y_test, predictions, zero_division=0)),
        "f1_fraud": float(f1_score(y_test, predictions, zero_division=0)),
        "average_precision": float(average_precision_score(y_test, scores)),
        "confusion_matrix": matrix.tolist(),
        "confusion_matrix_order": ["legitime (0)", "fraude (1)"],
        "threshold": THRESHOLD,
        "random_state": RANDOM_STATE,
        "train_size": len(y_train),
        "test_size": len(y_test),
        "train_class_counts": {str(k): int(v) for k, v in y_train.value_counts().items()},
        "test_class_counts": {str(k): int(v) for k, v in y_test.value_counts().items()},
        "fraud_rate": float(y.mean()),
        "test_fraud_rate": float(y_test.mean()),
        "classification_report": classification_report(
            y_test, predictions, labels=[0, 1],
            target_names=["legitime", "fraude"], output_dict=True, zero_division=0
        ),
        "dataset": {
            "filename": data_path.name,
            "sha256": hashlib.sha256(data_path.read_bytes()).hexdigest(),
            "rows": len(data),
            "provenance": "CSV fourni avec le sujet ; origine réelle ou synthétique non documentée",
        },
        "versions": {
            "python": platform.python_version(), "numpy": np.__version__,
            "pandas": pd.__version__, "scikit-learn": sklearn.__version__,
            "scipy": scipy.__version__,
            "matplotlib": matplotlib.__version__, "joblib": joblib.__version__,
        },
    }
    print(f"\nÉvaluation sur le test — seuil fixé à {THRESHOLD}")
    for key in ["precision_fraud", "recall_fraud", "f1_fraud", "average_precision"]:
        print(f"{key} : {metrics[key]:.6f}")
    print("\nMatrice de confusion (lignes = réel, colonnes = prédit ; ordre 0, 1) :\n", matrix)
    print("\nClassification report :\n", report)
    print("Origine : CSV fourni ; provenance non documentée. Résultats de démonstration.")

    fig, ax = plt.subplots(figsize=(6, 5))
    ConfusionMatrixDisplay(matrix, display_labels=["Légitime", "Fraude"]).plot(
        ax=ax, cmap="Blues", colorbar=False
    )
    ax.set(title=f"Matrice de confusion — seuil {THRESHOLD}",
           xlabel="Classe prédite", ylabel="Classe réelle")
    fig.tight_layout()
    fig.savefig(results_dir / "confusion_matrix.png", dpi=160)
    plt.close(fig)

    fig, ax = plt.subplots(figsize=(7, 5))
    PrecisionRecallDisplay.from_predictions(y_test, scores, ax=ax, name="Régression logistique")
    ax.axhline(y_test.mean(), color="gray", linestyle="--", label="Prévalence fraude (test)")
    ax.set(title="Courbe Precision-Recall — test", xlabel="Rappel", ylabel="Précision")
    ax.legend(loc="best")
    fig.tight_layout()
    fig.savefig(results_dir / "precision_recall_curve.png", dpi=160)
    plt.close(fig)

    (results_dir / "metrics.json").write_text(
        json.dumps(metrics, indent=2, ensure_ascii=False, allow_nan=False) + "\n", encoding="utf-8"
    )
    (results_dir / "classification_report.txt").write_text(report, encoding="utf-8")
    model_path = models_dir / "fraud_pipeline.joblib"
    joblib.dump(pipeline, model_path)
    print(f"\nPipeline enregistré : {model_path}")
    print(f"Résultats enregistrés : {results_dir}")
    return metrics


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=BASE_DIR / "data/bank_transactions.csv")
    parser.add_argument("--output-dir", type=Path, default=BASE_DIR,
                        help="Dossier contenant models/ et results/ (par défaut : dossier du script)")
    args = parser.parse_args()
    try:
        run(args.data.resolve(), args.output_dir.resolve())
    except (ValueError, OSError, pd.errors.ParserError) as exc:
        print(f"Erreur : {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""배포 임계를 바꾸면 화면이 어떻게 달라지는가 — 정밀도·재현율·금액을 한 표로.

train.py 는 임계를 하나 골라 박아 넣는다. 그런데 그 선택은 **제품 판단**이지 통계 판단이
아니다 — 틀리게 지목하는 비용(신뢰 상실)과 놓치는 비용(목록이 짧아짐) 중 무엇이 큰지는
화면이 무엇을 말하느냐에 달렸다. 그래서 고르기 전에 표를 본다.

    python3 ml/threshold-sweep.py

학습된 ebm.pkl 을 재사용하므로 재학습(5분)이 필요 없다.
"""
import json, os, pathlib, pickle, sys
import numpy as np, pandas as pd

ML = os.environ.get("FINNTECH_ML_DIR", str(pathlib.Path.home() / "Downloads" / "finntech-ml"))
REPO = pathlib.Path(__file__).resolve().parent.parent
_KM = json.loads((REPO / "backend" / "src" / "main" / "resources" / "industry-mid.json").read_text(encoding="utf-8"))
CODE2MID = _KM["midByIndustry"]; ESSENTIAL = set(_KM["essentialCategories"])

# ── train.py 와 **같은 순서로** 특징을 만든다. 어긋나면 점수가 통째로 달라진다. ──
us = pd.read_csv(f"{ML}/user_split.tsv", sep="\t")
ucol, scol, pcol = us.columns[:3]
codes = {u: i for i, u in enumerate(us[ucol])}
split_by_code = us[scol].to_numpy()
pcats = sorted(us[pcol].dropna().unique())
pidx = {p: i for i, p in enumerate(pcats)}
pcodes = np.array([pidx.get(p, -1) for p in us[pcol]])

cu = pd.read_csv(f"{ML}/card_user.tsv", sep="\t")
card2code = {c: codes[u] for c, u in zip(cu[cu.columns[0]], cu[cu.columns[1]]) if u in codes}

df = pd.read_csv(f"{ML}/payments.tsv", sep="\t",
                 dtype={"card_id": "string", "ksic": "string", "amount": "int32", "label": "string"},
                 parse_dates=["dt"])
df["cat2"] = df["ksic"].map(CODE2MID).fillna("카테고리없음").astype("category")
df["is_disc"] = (~df["cat2"].astype(str).isin(ESSENTIAL)).astype("int8")
df.drop(columns=["ksic"], inplace=True)
df["ucode"] = df["card_id"].map(card2code).astype("int32"); df.drop(columns=["card_id"], inplace=True)
df["split"] = split_by_code[df["ucode"].to_numpy()]
df = df[df["split"] != "SERVICE"].copy()
df["pcode"] = pcodes[df["ucode"].to_numpy()]
df["y"] = (df["label"] == "WASTE").astype("int8")
df["log_amount"] = np.log1p(df["amount"].astype("float32"))
hr = df["dt"].dt.hour.to_numpy(); dow = df["dt"].dt.dayofweek.to_numpy()
df["hour_sin"] = np.sin(2*np.pi*hr/24).astype("float32"); df["hour_cos"] = np.cos(2*np.pi*hr/24).astype("float32")
df["night"] = (((hr >= 22) | (hr < 4))).astype("int8")
df["dow_sin"] = np.sin(2*np.pi*dow/7).astype("float32"); df["dow_cos"] = np.cos(2*np.pi*dow/7).astype("float32")
df["weekend"] = (dow >= 5).astype("int8")
g = df.groupby(["ucode", "cat2"], observed=True)["amount"].transform("median")
df["amt_vs_typical"] = (df["amount"] / g.clip(lower=1)).astype("float32")
df["user_mean_log_amount"] = df.groupby("ucode")["log_amount"].transform("mean").astype("float32")
df["user_disc_ratio"] = df.groupby("ucode")["is_disc"].transform("mean").astype("float32")

FEATS = ["cat2", "log_amount", "hour_sin", "hour_cos", "night", "dow_sin", "dow_cos",
         "weekend", "amt_vs_typical", "user_mean_log_amount", "user_disc_ratio"]
te = df[df["split"] == "TEST"]
Xte, yte = te[FEATS], te["y"].to_numpy()
amt = te["amount"].to_numpy().astype("float64")

ebm = pickle.load(open(f"{ML}/ebm.pkl", "rb"))
proba = ebm.predict_proba(Xte)[:, 1]

cur = json.loads((REPO / "backend/src/main/resources/ml/ebm_model.json").read_text())["decision_threshold"]
base_n, base_w = yte.mean(), amt[yte == 1].sum() / amt.sum()
print(f"검정 {len(yte):,}건 · 정답 낭비 건수비 {base_n:.3f} · 금액비 {base_w:.3f}\n")
print(f"{'임계':>6} {'정밀도':>7} {'재현율':>7} {'F1':>6} {'F0.5':>6} "
      f"{'찍은건수비':>9} {'찍은금액비':>9} {'놓친낭비':>9}")
print("-" * 70)
for thr in (0.30, 0.35, 0.40, 0.45, 0.495, 0.55, 0.60):
    pred = proba >= thr
    tp = int((pred & (yte == 1)).sum()); fp = int((pred & (yte == 0)).sum())
    fn = int((~pred & (yte == 1)).sum())
    p = tp / max(1, tp + fp); r = tp / max(1, tp + fn)
    f1 = 2*p*r / max(1e-9, p + r); f05 = 1.25*p*r / max(1e-9, 0.25*p + r)
    mark = "  ← 현재" if abs(thr - cur) < 1e-9 else ""
    print(f"{thr:>6.3f} {p:>7.3f} {r:>7.3f} {f1:>6.3f} {f05:>6.3f} "
          f"{pred.mean():>9.3f} {amt[pred].sum()/amt.sum():>9.3f} {fn:>9,}{mark}")

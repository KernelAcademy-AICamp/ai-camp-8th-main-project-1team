#!/usr/bin/env python3
"""PDF 텍스트를 파싱해 카드 상품 정보를 추출한다.

- pdftotext -layout로 텍스트 변환
- 카드명, 발급사, 연회비, 전월실적 조건, 가맹점 목록, 스키마 외 예외조건을 추출
- mapped_categories는 항상 빈 배열로 남긴다.
"""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

BASE = Path(__file__).resolve().parent
PDF_DIR = BASE / "out" / "pdf"
MANUAL_DIR = BASE / "manual_pdf"
META = BASE / "out" / "pdf_meta.json"
META_ALT = BASE / "out" / "meta.json"
OUT = BASE / "out" / "pdf_cards.json"


def run_pdftotext(pdf_path: Path) -> str:
    cmd = ["pdftotext", "-layout", str(pdf_path), "-"]
    proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or "pdftotext 실패")
    return proc.stdout


def normalize_spaces(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def extract_basic(text: str) -> dict:
    lines = [normalize_spaces(line) for line in text.splitlines() if normalize_spaces(line)]
    joined = "\n".join(lines)
    title = None
    for line in lines[:20]:
        if len(line) > 4 and any(ch in line for ch in "카드"):
            title = line
            break
    issuer = None
    if "BC카드" in joined or "BC" in joined:
        issuer = "BC카드"
    elif "삼성카드" in joined:
        issuer = "삼성카드"
    elif "우리카드" in joined:
        issuer = "우리카드"
    elif "현대카드" in joined:
        issuer = "현대카드"
    elif "국민카드" in joined:
        issuer = "KB국민카드"

    annual_fee_local = None
    annual_fee_global = None
    m_local = re.search(r"(국내|LOCAL|국내연회비)[^\d]{0,20}(\d{1,3}(?:,\d{3})*|\d+)원", joined)
    if m_local:
        annual_fee_local = int(m_local.group(2).replace(",", ""))
    m_global = re.search(r"(해외|GLOBAL|해외연회비)[^\d]{0,20}(\d{1,3}(?:,\d{3})*|\d+)원", joined)
    if m_global:
        annual_fee_global = int(m_global.group(2).replace(",", ""))

    perf = []
    for m in re.finditer(r"(전월실적|전월 실적|전월 이용금액|실적조건|실적 유예|전월실적 채워드림|생일월 혜택)[^\n]{0,120}", joined):
        perf.append(normalize_spaces(m.group(0)))
    perf = perf[:8]

    merchants = []
    for m in re.finditer(r"([가-힣A-Za-z0-9·/()]+(?:,|, | 또는 | 및 |\s)+[가-힣A-Za-z0-9·/()]+)", joined):
        segment = normalize_spaces(m.group(1))
        if len(segment) > 4 and any(ch in segment for ch in "가맹점"):
            continue
        if len(segment) < 3:
            continue
        merchants.append(segment)
    merchants = sorted(set(merchants))[:20]

    conditions_unmapped = []
    for pat in [r"생일월 혜택", r"실적유예", r"전월실적 채워드림", r"연\s*\d+회", r"월\s*\d+회", r"제한", r"우대"]:
        if re.search(pat, joined):
            conditions_unmapped.append(pat)
    if not conditions_unmapped:
        conditions_unmapped = ["기타 조건 문구 확인 필요"]

    return {
        "card_name": title or "미확인",
        "issuer": issuer or "미확인",
        "annual_fee_local": annual_fee_local,
        "annual_fee_global": annual_fee_global,
        "performance_conditions": perf,
        "merchants_named": merchants,
        "conditions_unmapped": conditions_unmapped,
        "mapped_categories": [],
    }


def parse_pdf(pdf_path: Path) -> dict:
    text = run_pdftotext(pdf_path)
    return {
        "source_file": pdf_path.name,
        **extract_basic(text),
    }


def collect_pdf_files() -> list[Path]:
    files = []
    if PDF_DIR.exists():
        files.extend(sorted(PDF_DIR.glob("*.pdf")))
    if MANUAL_DIR.exists():
        files.extend(sorted(MANUAL_DIR.glob("*.pdf")))
    return sorted(set(files))


def main() -> None:
    meta_path = META if META.exists() else META_ALT
    if not meta_path.exists():
        raise SystemExit(f"pdf 메타 파일이 없습니다. 먼저 fetch_pdf.py를 실행하세요: {meta_path}")
    pdf_files = collect_pdf_files()
    if not pdf_files:
        raise SystemExit("파싱할 PDF가 없습니다.")

    results = []
    for pdf_path in pdf_files:
        try:
            parsed = parse_pdf(pdf_path)
            results.append(parsed)
            print(f"파싱 성공 · {pdf_path.name}")
        except Exception as e:  # noqa: BLE001
            print(f"파싱 실패 · {pdf_path.name} · {e}")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n총 {len(results)}건 파싱 완료 → {OUT}")


if __name__ == "__main__":
    main()

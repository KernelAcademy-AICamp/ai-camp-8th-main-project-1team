#!/usr/bin/env python3
"""카드사 상품공시 PDF 전문을 Gemini로 두 번 읽어 카드 JSON을 만든다.

PDF 원문은 저장소 밖에 두며, 이 프로그램도 원문이나 API 키를 로그에 남기지 않는다.
출력 JSON은 사람이 원문과 대조한 ``schema-draft.json``과 같은 입력 계약을 따른다.
"""

import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple


HERE = Path(__file__).resolve().parent
SCHEMA_DRAFT = HERE / "schema-draft.json"
DEFAULT_MODEL = "gemini-3.1-flash-lite"
GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"
MIN_CHARS_PER_PAGE = 200
# 글자를 벡터로 변환해 올린 상품안내장은 pdftotext로 읽을 것이 없다. 이때만 PDF 원본을
# 그대로 첨부해 모델이 화면대로 읽게 한다. 요청 한 건의 상한은 base64로 부푼 뒤 기준이다.
MAX_INLINE_PDF_BYTES = 14 * 1024 * 1024
TEMPERATURES = (0.0, 0.2)
MAX_ATTEMPTS = 5
# 요청 한도(429)·일시 장애(503)는 **분 단위로 풀린다.** 예전에는 2초·4초만 쉬고 세 번
# 만에 포기했는데, 그 6초는 한도 창(1분)보다 훨씬 짧아 세 번 다 같은 429를 맞았다 —
# KB 387장 중 183장이 그렇게 한꺼번에 실패했다(2026-08-14).
# 서버가 `Retry-After` 를 주면 그 값을 따르고, 없으면 아래 간격으로 물러선다.
RATE_LIMIT_STATUSES = {429, 500, 503}
RATE_LIMIT_BACKOFF = (20, 45, 90, 180)
MAX_RETRY_WAIT = 300
MAX_PRODUCT_ID_LENGTH = 30
ISSUER_PREFIX = {
    "삼성카드": "SAM-", "현대카드": "HYU-", "롯데카드": "LOT-",
    "하나카드": "HANA-", "KB국민카드": "KB-", "우리카드": "WOO-",
    "NH농협카드": "NH-",
}

EXCLUSION_CODES = [
    "TRANSIT", "PUBLIC_DUES", "UTILITY", "SOCIAL_INS", "TAX", "PENALTY",
    "HOUSING", "TUITION", "CASH_ADVANCE", "CARD_LOAN", "FEE", "GIFT_CARD",
    "INSTALLMENT_FREE", "PUBLIC_MERCHANT",
]


CARD_SCHEMA: Dict[str, Any] = {
    "type": "object",
    "properties": {
        "issuer": {"type": "string"},
        "name": {"type": "string"},
        "product_id": {"type": "string"},
        "status": {"type": "string", "enum": ["active", "stopped"]},
        "as_of": {
            "type": ["string", "null"],
            "description": "여신금융협회 심의필 문구에 적힌 날짜(YYYY-MM-DD). 출시일이나 PDF 수정일이 아님",
        },
        "review_no": {
            "type": ["string", "null"],
            "description": "여신금융협회 심의필 번호 전체",
        },
        "compliance_no": {"type": ["string", "null"]},
        "posted_at": {"type": ["string", "null"]},
        "card_type": {
            "type": ["string", "null"],
            "description": "CREDIT 또는 CHECK. 하이브리드 카드는 주 결제 방식인 CHECK",
        },
        "benefit_style": {
            "type": ["string", "null"],
            "description": "DISCOUNT_POINT, MILEAGE, PREMIUM 중 하나",
        },
        "policy_card": {"type": ["boolean", "null"]},
        "has_transit": {"type": ["boolean", "null"]},
        "annual_fee": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "scope": {"type": "string"},
                    "brand": {"type": "string"},
                    "total": {"type": "integer"},
                    "base": {"type": ["integer", "null"]},
                    "affiliate": {"type": ["integer", "null"]},
                },
                "required": ["scope", "brand", "total"],
            },
        },
        "annual_fee_notes": {"type": "array", "items": {"type": "string"}},
        "performance": {
            "anyOf": [
                {"type": "null"},
                {
                    "type": "object",
                    "properties": {
                        "period": {"type": "string"},
                        "basis": {"type": "string"},
                        "basis_exceptions": {
                            "type": ["object", "null"],
                            "properties": {
                                "basis": {"type": "string"},
                                "applies_to": {"type": "array", "items": {"type": "string"}},
                            },
                        },
                        "includes_family_card": {"type": ["boolean", "null"]},
                        "includes": {"type": "array", "items": {"type": "string"}},
                        "tiers": {"type": "array", "items": {"type": "integer"}},
                        "new_member_grace": {"type": ["object", "null"]},
                        "excluded": {
                            "type": "array",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "code": {"type": "string"},
                                    "label": {"type": "string"},
                                },
                                "required": ["code", "label"],
                            },
                        },
                    },
                    "required": ["period", "basis", "tiers", "excluded"],
                },
            ]
        },
        "benefit_unit": {"type": ["object", "null"]},
        "benefits": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "group": {"type": "string"},
                    "kind": {"type": "string", "enum": ["할인", "적립", "무이자할부"]},
                    "settle": {"type": ["string", "null"]},
                    "rate_percent": {"type": ["number", "null"]},
                    "rate_conditional": {"type": ["string", "null"]},
                    "amount_krw": {"type": ["integer", "null"]},
                    "min_amount": {"type": ["integer", "null"]},
                    "requires_tier": {"type": ["integer", "null"]},
                    "targets": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "category": {"type": "string"},
                                "brands": {"type": "array", "items": {"type": "string"}},
                                "industries": {"type": "array", "items": {"type": "string"}},
                                "scope": {"type": ["string", "null"]},
                                "channel": {"type": "array", "items": {"type": "string"}},
                                "exclude_place": {"type": "array", "items": {"type": "string"}},
                                "note": {"type": ["string", "null"]},
                            },
                            "required": ["category"],
                        },
                    },
                    "monthly_cap_by_tier": {
                        "type": ["object", "null"],
                        "additionalProperties": {"type": "integer"},
                    },
                    "combined_cap_group": {"type": ["string", "null"]},
                    "conditions": {"type": "array", "items": {"type": "string"}},
                    "exclusive_with": {"type": "array", "items": {"type": "string"}},
                    "targets_complete": {"type": ["boolean", "null"]},
                    "pay_channel": {"type": ["string", "null"]},
                    "is_headline": {"type": ["boolean", "null"]},
                },
                "required": ["group", "kind", "targets"],
            },
        },
        "combined_caps": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "group": {"type": "string"},
                    "members": {"type": "array", "items": {"type": "string"}},
                    "cap_by_tier": {
                        "type": "object",
                        "additionalProperties": {"type": "integer"},
                    },
                    "note": {"type": ["string", "null"]},
                },
                "required": ["group", "members", "cap_by_tier"],
            },
        },
        "benefit_excluded": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "code": {"type": "string"},
                    "label": {"type": "string"},
                },
                "required": ["code", "label"],
            },
        },
        "benefit_notes": {"type": "array", "items": {"type": "string"}},
        "benefit_conditions": {"type": "array", "items": {"type": "string"}},
        "non_monetary": {"type": "array", "items": {"type": "string"}},
        "schema_gaps": {"type": "array", "items": {"type": "string"}},
    },
    "required": [
        "issuer", "name", "product_id", "status", "as_of", "review_no", "annual_fee", "benefits",
        "combined_caps", "benefit_excluded", "non_monetary", "schema_gaps",
    ],
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="카드 상품공시 PDF 전문을 LLM 카드 JSON으로 추출")
    parser.add_argument("--src", required=True, type=Path, help="PDF 디렉터리")
    parser.add_argument("--out", required=True, type=Path, help="카드별 JSON 출력 디렉터리")
    parser.add_argument(
        "--manifest", type=Path,
        help="download_youth_cards.py가 만든 metadata.json (생략하면 기존 BC 형식)",
    )
    parser.add_argument("--force", action="store_true", help="이미 있는 JSON도 다시 추출")
    return parser.parse_args()


def selected_model(environment: Optional[Dict[str, str]] = None) -> str:
    values = environment if environment is not None else os.environ
    return values.get("GEMINI_MODEL", "").strip() or DEFAULT_MODEL


def run_command(command: List[str]) -> str:
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        tool_name = Path(command[0]).name
        raise RuntimeError(f"{tool_name} 실패(exit={result.returncode})")
    return result.stdout


def pdf_pages(pdf_path: Path) -> int:
    output = run_command(["pdfinfo", str(pdf_path)])
    match = re.search(r"^Pages:\s+(\d+)\s*$", output, re.MULTILINE)
    if not match:
        raise RuntimeError("pdfinfo 결과에서 쪽수를 찾지 못했다")
    return int(match.group(1))


def pdf_text(pdf_path: Path) -> str:
    return run_command(["pdftotext", "-layout", str(pdf_path), "-"])


def chars_per_page(text: str, pages: int) -> float:
    if pages <= 0:
        return 0.0
    return sum(not character.isspace() for character in text) / pages


def is_textual(text: str, pages: int) -> bool:
    return chars_per_page(text, pages) >= MIN_CHARS_PER_PAGE


def normalize_date(raw: Optional[str]) -> Optional[str]:
    if not raw:
        return None
    value = raw.strip().replace(".", "-").replace("/", "-")
    compact = re.fullmatch(r"(\d{4})(\d{2})(\d{2})", value)
    if compact:
        value = "-".join(compact.groups())
    match = re.fullmatch(r"(\d{4})-(\d{1,2})-(\d{1,2})", value)
    if not match:
        # **날짜가 아니면 날짜라고 적지 않는다.** 예전에는 못 읽은 값을 그대로 흘려보내
        # `2025072`(7자리) 같은 것이 카탈로그를 지나 **앱 기동에서 터졌다**(2026-08-18).
        # 모르는 것은 비워 두면 게이트 3 이 as_of 없음으로 잡아 참고 모드로 떨어뜨린다.
        return None
    year, month, day = (int(part) for part in match.groups())
    return f"{year:04d}-{month:02d}-{day:02d}"


def product_id_for(metadata: Dict[str, Any]) -> str:
    """V36의 VARCHAR(30)에 맞는 안정적인 재적재 키를 고른다."""
    raw_product_id = str(metadata.get("product_id", "")).strip()
    if raw_product_id:
        prefix = ISSUER_PREFIX.get(str(metadata.get("issuer", "")), "")
        value = raw_product_id if raw_product_id.startswith(prefix) else prefix + raw_product_id
        if len(value) <= MAX_PRODUCT_ID_LENGTH:
            return value
        return prefix + hashlib.sha256(raw_product_id.encode("utf-8")).hexdigest()[:20]
    stem = Path(str(metadata.get("filename", ""))).stem
    if len(stem) <= MAX_PRODUCT_ID_LENGTH:
        return stem
    number = str(metadata.get("no", "")).strip()
    if not number or len(number) > MAX_PRODUCT_ID_LENGTH:
        raise RuntimeError(f"30자 이내 상품 식별자가 없다: {stem}")
    return number


def load_metadata(src: Path) -> Dict[str, Dict[str, Any]]:
    metadata_path = src.parent / "metadata.json"
    if not metadata_path.exists():
        raise RuntimeError(f"수집 메타데이터가 없다: {metadata_path}")
    root = json.loads(metadata_path.read_text(encoding="utf-8"))
    by_filename: Dict[str, Dict[str, Any]] = {}
    for row in root.get("files", []):
        relative_path = row.get("relative_path")
        if not relative_path or not str(relative_path).startswith("active/"):
            continue
        filename = Path(str(relative_path)).name
        if filename in by_filename:
            raise RuntimeError(f"메타데이터 파일명이 겹친다: {filename}")
        by_filename[filename] = row
    return by_filename


def load_manifest(src: Path, manifest_path: Path) -> Dict[Path, Dict[str, Any]]:
    root = json.loads(manifest_path.read_text(encoding="utf-8"))
    by_path: Dict[Path, Dict[str, Any]] = {}
    for row in root.get("files", []):
        if row.get("status") not in {"downloaded", "existing", "manual_imported"}:
            continue
        relative_path = row.get("relative_path")
        if not relative_path:
            raise RuntimeError("매니페스트 행에 relative_path가 없다")
        pdf_path = (src / str(relative_path)).resolve()
        if pdf_path in by_path:
            raise RuntimeError(f"매니페스트 PDF 경로가 겹친다: {relative_path}")
        by_path[pdf_path] = row
    return by_path


def verified_examples() -> str:
    root = json.loads(SCHEMA_DRAFT.read_text(encoding="utf-8"))
    return json.dumps(root["cards"], ensure_ascii=False, separators=(",", ":"))


def build_prompt(text: str, metadata: Dict[str, Any], examples: str, attached_pdf: bool = False) -> str:
    identity = {
        "issuer": metadata.get("issuer") or "BC카드",
        "name": metadata.get("name") or metadata.get("product_name"),
        "product_id": product_id_for(metadata),
        "status": status_of(metadata),
        "posted_at": normalize_date(metadata.get("posted_at") or metadata.get("launch_date")),
        "card_type": metadata.get("card_type"),
    }
    if attached_pdf:
        source_rule = ("1. 이 요청에 첨부된 PDF 원본을 첫 쪽부터 끝 쪽까지 모두 보고, 표의 모든 행과"
                       " 각주까지 읽으세요. 일부 칼럼만 읽지 마세요.")
        source_block = ("원문은 이 요청에 PDF 파일로 첨부했습니다.\n"
                        "글자를 벡터로 변환해 올린 문서라 텍스트 추출이 되지 않으니, 화면에 보이는 대로 읽으세요.")
    else:
        source_rule = "1. 아래 PDF 전문 전체를 읽고, 첫 매치나 일부 칼럼만 읽지 마세요."
        source_block = f"PDF 전문 시작\n----------------\n{text}\n----------------\nPDF 전문 끝"
    return f"""한국 카드사의 상품설명서 전문에서 카드 한 장을 JSON 한 개로 추출하세요.

정확한 출력 모양의 사람이 검수한 예시 3개:
{examples}

반드시 지킬 규칙:
{source_rule}
2. as_of와 review_no는 문서 끝까지 찾아 반드시 추출하세요. as_of는 **여신금융협회 심의필 문구에
   적힌 날짜**를 YYYY-MM-DD로 옮긴 값입니다. 상품 출시일, 게시일, 준법감시일, PDF 수정일과
   혼동하지 마세요. 심의필 날짜 표기가 정말 없을 때만 null입니다.
3. 문서에 없는 숫자나 조건을 추측하지 마세요. 불명확하면 생략하거나 null로 두세요.
4. performance.excluded(전월실적 제외)와 benefit_excluded(혜택 적용 제외)는 서로 다른 목록입니다.
5. tiers는 문서에 나온 가변 구간이고, requires_tier와 한도 키는 반드시 그 구간 금액을 사용하세요.
6. 표의 병합셀은 해당되는 모든 실적 구간으로 펼치세요.
7. 제외 항목은 의미가 정확히 맞으면 다음 기존 코드를 사용하세요: {', '.join(EXCLUSION_CODES)}.
   정확히 대응하지 않는 새 제외 의미는 짧은 영문 대문자 코드를 새로 만들고 원문 label을 보존하세요.
8. targets.industries에는 문서의 업종 표현을 그대로 쓰고, 브랜드명은 targets.brands에 넣으세요.
9. 생일월 한도, 연간 횟수, 실적 유예처럼 현재 스키마로 정확히 표현 못 하는 조건을 버리지 말고 schema_gaps에 넣으세요.
10. card_type은 CREDIT 또는 CHECK만 사용하세요. 하이브리드는 주 결제 방식인 CHECK로 쓰고 구조는 schema_gaps에 설명하세요.
11. 설명이나 마크다운 없이 JSON만 답하세요.

수집 메타데이터 정본(이 값은 그대로 쓰세요):
{json.dumps(identity, ensure_ascii=False)}

{source_block}
"""


def gemini_request(
        prompt: str,
        temperature: float,
        api_key: str,
        model: str,
        pdf_bytes: Optional[bytes] = None,
) -> urllib.request.Request:
    endpoint = f"{GEMINI_BASE_URL}/v1beta/models/{model}:generateContent"
    parts: List[Dict[str, Any]] = [{"text": prompt}]
    if pdf_bytes is not None:
        parts.append({
            "inline_data": {
                "mime_type": "application/pdf",
                "data": base64.b64encode(pdf_bytes).decode("ascii"),
            },
        })
    payload = {
        "contents": [{"parts": parts}],
        "generationConfig": {
            "temperature": temperature,
            "responseMimeType": "application/json",
            "responseJsonSchema": CARD_SCHEMA,
        },
    }
    return urllib.request.Request(
        endpoint,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "x-goog-api-key": api_key},
        method="POST",
    )


def response_text(payload: Dict[str, Any]) -> str:
    candidates = payload.get("candidates") or []
    if not candidates:
        raise RuntimeError("Gemini 응답에 candidate가 없다")
    parts = candidates[0].get("content", {}).get("parts", [])
    text_parts = [part.get("text", "") for part in parts if part.get("text")]
    if not text_parts:
        raise RuntimeError("Gemini 응답에 JSON text가 없다")
    return "".join(text_parts)


def retry_delay(attempt: int, error: Exception) -> float:
    """다음 시도까지 쉴 시간(초). 한도에 걸린 것과 그냥 실패한 것을 다르게 다룬다."""
    status = getattr(error, "code", None)
    if status in RATE_LIMIT_STATUSES:
        header = getattr(error, "headers", None)
        after = header.get("Retry-After") if header else None
        if after:
            try:
                return min(MAX_RETRY_WAIT, max(1.0, float(after)))
            except ValueError:
                pass
        return RATE_LIMIT_BACKOFF[min(attempt - 1, len(RATE_LIMIT_BACKOFF) - 1)]
    return 2 ** attempt


def call_gemini(
        prompt: str,
        temperature: float,
        api_key: str,
        model: str,
        opener: Any = urllib.request.urlopen,
        pdf_bytes: Optional[bytes] = None,
) -> Dict[str, Any]:
    request = gemini_request(prompt, temperature, api_key, model, pdf_bytes)
    last_error: Optional[Exception] = None
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            with opener(request, timeout=180) as response:
                payload = json.loads(response.read().decode("utf-8"))
            card = json.loads(response_text(payload))
            validate_card(card)
            return card
        except (OSError, ValueError, KeyError, RuntimeError, urllib.error.HTTPError) as error:
            last_error = error
            if attempt < MAX_ATTEMPTS:
                time.sleep(retry_delay(attempt, error))
    error_name = type(last_error).__name__ if last_error else "UnknownError"
    raise RuntimeError(f"Gemini 호출/응답 검증 실패({error_name}, {MAX_ATTEMPTS}회)")


def validate_card(card: Any) -> None:
    if not isinstance(card, dict):
        raise ValueError("카드 응답이 object가 아니다")
    for field in ("annual_fee", "benefits", "combined_caps", "benefit_excluded", "non_monetary", "schema_gaps"):
        if not isinstance(card.get(field), list):
            raise ValueError(f"{field}가 배열이 아니다")
    performance = card.get("performance")
    if performance is not None and not isinstance(performance, dict):
        raise ValueError("performance가 object/null이 아니다")
    for benefit in card["benefits"]:
        if not isinstance(benefit, dict) or not benefit.get("group") or not benefit.get("kind"):
            raise ValueError("혜택의 group/kind가 없다")
        if not isinstance(benefit.get("targets"), list):
            raise ValueError("혜택 targets가 배열이 아니다")


LABEL_SPLIT = re.compile(r"[^0-9A-Za-z가-힣]+")


def label_tokens(*values: Any) -> List[str]:
    """라벨 여러 개를 낱말 가방 하나로 만든다.

    같은 사실을 모델이 어디에 적느냐가 실행마다 흔들린다 —
    한 번은 ``category='영화', brands=['CGV','롯데시네마']``, 다음은
    ``category='영화(CGV, 롯데시네마)', brands=[]``. 칸을 나눠 비교하면
    같은 내용이 다르다고 잡히므로, 칸을 허물고 낱말만 본다.
    """
    tokens: List[str] = []
    pending = list(values)
    while pending:
        value = pending.pop()
        if value is None:
            continue
        if isinstance(value, (list, tuple)):
            pending.extend(value)
        elif isinstance(value, dict):
            pending.extend(value.values())
        else:
            tokens.extend(part.lower() for part in LABEL_SPLIT.split(str(value)) if part)
    return sorted(set(tokens))


def ordered_by_label(items: List[Tuple[List[str], Dict[str, Any]]]) -> List[Dict[str, Any]]:
    """낱말 가방으로 줄을 맞춘 뒤, 비교할 값만 남긴다.

    가방을 정렬 키로 쓰는 이유는 두 실행이 같은 항목을 다른 순서로 내놓기 때문이다.
    가방 자체는 비교하지 않는다 — 이름 붙이는 방식이 흔들리는 것과 숫자를 다르게
    읽는 것은 다른 문제이고, 이 검사는 뒤엣것을 잡는다.

    가방이 같은 항목끼리는 값으로 한 번 더 정렬한다. 모델이 내놓은 순서를 그대로
    두면 같은 내용이 순서 때문에 어긋나 보인다.
    """
    return [
        values for _, _, values in sorted(
            (labels, json.dumps(values, ensure_ascii=False, sort_keys=True), values)
            for labels, values in items
        )
    ]


# 할인이냐 적립이냐는 **사용자에게 같은 값이다** — 둘 다 돈이 돌아오고, 화면은 금액을
# 보여주지 않으며 순위도 겹친 곳 수로 매긴다. 그런데 이 한 글자가 달랐다는 이유로 카드가
# 통째로 참고 모드로 떨어지고 있었다(97건 중 70건, 2026-08-18).
# **무이자할부는 접지 않는다** — 그건 금액으로 셀 수 없는 다른 갈래다.
COMPARABLE_KIND = {"할인": "금전혜택", "적립": "금전혜택"}


def comparable_kind(kind: Any) -> Any:
    return COMPARABLE_KIND.get(kind, kind)


def cap_values(caps: Any) -> List[Any]:
    """한도를 **구간 키 없이** 값만 줄 세워 본다.

    두 실행이 같은 한도를 다른 구간에 붙이면(`5만원 구간에 5천원` vs `30만원 구간에 5천원`)
    사전끼리 비교할 때 양쪽 다 "한쪽만 있음"으로 잡힌다 — 1,058건 중 379건이 그랬다.
    구간 배정은 화면에 나가지 않으므로(첫 구간 문턱만 보여준다) 여기서 따지지 않는다.
    **금액 자체가 다르면 여전히 잡힌다.**
    """
    if not caps:
        return []
    return sorted((str(v) for v in caps.values()))


def numeric_projection(card: Dict[str, Any]) -> Dict[str, Any]:
    performance = card.get("performance") or {}
    fees = ordered_by_label([
        (
            label_tokens(fee.get("scope"), fee.get("brand")),
            {"total": fee.get("total"), "base": fee.get("base"), "affiliate": fee.get("affiliate")},
        )
        for fee in card.get("annual_fee", [])
    ])

    benefits = ordered_by_label([
        (
            label_tokens(benefit.get("targets")),
            {
                "kind": comparable_kind(benefit.get("kind")),
                "rate_percent": benefit.get("rate_percent"),
                "amount_krw": benefit.get("amount_krw"),
                "min_amount": benefit.get("min_amount"),
                "requires_tier": benefit.get("requires_tier"),
                "monthly_cap_by_tier": cap_values(benefit.get("monthly_cap_by_tier")),
            },
        )
        for benefit in card.get("benefits", [])
    ])

    combined = ordered_by_label([
        (
            label_tokens(cap.get("group"), cap.get("members")),
            {"cap_by_tier": cap_values(cap.get("cap_by_tier"))},
        )
        for cap in card.get("combined_caps", [])
    ])
    return {
        "annual_fee": fees,
        "tiers": sorted(performance.get("tiers") or []),
        "benefits": benefits,
        "combined_caps": combined,
    }


def differences(first: Any, second: Any, path: str = "$") -> List[Dict[str, Any]]:
    if type(first) is not type(second):
        return [{"path": path, "first": first, "second": second}]
    if isinstance(first, dict):
        found: List[Dict[str, Any]] = []
        for key in sorted(set(first) | set(second)):
            if key not in first or key not in second:
                found.append({"path": f"{path}.{key}", "first": first.get(key), "second": second.get(key)})
            else:
                found.extend(differences(first[key], second[key], f"{path}.{key}"))
        return found
    if isinstance(first, list):
        found = []
        if len(first) != len(second):
            found.append({"path": f"{path}.length", "first": len(first), "second": len(second)})
        for index, (first_item, second_item) in enumerate(zip(first, second)):
            found.extend(differences(first_item, second_item, f"{path}[{index}]"))
        return found
    return [] if first == second else [{"path": path, "first": first, "second": second}]


def internal_maximum(card: Dict[str, Any]) -> Tuple[Optional[int], bool]:
    performance = card.get("performance") or {}
    tiers = performance.get("tiers") or []
    if not tiers:
        return None, False
    complete = True
    totals: List[int] = []
    combined_by_name = {row.get("group"): row for row in card.get("combined_caps", [])}
    for tier in tiers:
        ordinary_total = 0
        grouped_totals: Dict[str, int] = {}
        for benefit in card.get("benefits", []):
            caps = benefit.get("monthly_cap_by_tier") or {}
            cap = caps.get(str(tier))
            has_money = benefit.get("rate_percent") is not None or benefit.get("amount_krw") is not None
            if cap is None:
                if has_money and benefit.get("kind") != "무이자할부":
                    complete = False
                continue
            group = benefit.get("combined_cap_group")
            if group:
                grouped_totals[group] = grouped_totals.get(group, 0) + int(cap)
            else:
                ordinary_total += int(cap)
        for group, subtotal in grouped_totals.items():
            combined = combined_by_name.get(group) or {}
            combined_cap = (combined.get("cap_by_tier") or {}).get(str(tier))
            if combined_cap is None:
                complete = False
                ordinary_total += subtotal
            else:
                ordinary_total += min(subtotal, int(combined_cap))
        totals.append(ordinary_total)
    return (max(totals) if totals else None), complete


def status_of(metadata: Dict[str, Any]) -> str:
    """공시 목록이 말한 발급 상태. 중단이라고 말하는 신호가 하나라도 있으면 ``stopped``.

    **PDF 본문이 아니라 목록이 정본이다.** 설명서는 중단된 뒤에도 개정되므로(신한 `Love`
    는 2021-07 중단인데 약관 게시일이 2024-07) 본문으로는 발급 여부를 못 가린다.

    예전에는 이 자리에 ``"active"`` 가 박혀 있었다. 그래서 **발급이 끝난 카드를 담아도
    발급 중으로 적혀** 신규 발급 추천에 섞였다 — 신청할 수 없는 카드를 권하게 된다.
    (표 쪽은 이미 갈라 놓았다: `findRecommendable` 이 `status='ACTIVE'` 만 고른다.)

    신호 이름이 카드사마다 다르다. BC `currently_issued`·`issue_status`, 현대 `issued`,
    롯데 `issuance_ended`, 우리 `suspended_date`, KB `stop_date`, 후보 목록 `active_verified`.
    빈 값 표기도 갈려서(``''``·``'-'``·``None``) 날짜 칸은 값이 **있을 때만** 중단으로 본다.

    삼성·농협·신한은 목록에 신호가 아예 없다. 그 경우 ``active`` 로 두는데, 이건
    "발급 중임을 확인했다"가 아니라 **"중단이라는 말이 없다"** 는 뜻이다 — 발급 여부
    확인은 후보 선정(`select_youth_cards`)이 맡고 그 결과가 `active_verified` 로 온다.
    """
    from collector_policy import real_stop_date

    for key in ("active_verified", "currently_issued", "issued"):
        if metadata.get(key) is False:
            return "stopped"
    if metadata.get("issuance_ended") is True:
        return "stopped"
    # 먼 미래 날짜(`9999.12.31`)는 "중단일 없음"의 자리표시자다 — 날짜로 읽으면 발급 중인
    # 카드가 전부 중단으로 뒤집힌다(KB 388장 중 67장, 2026-08-14). 판단은 한 곳에 있다.
    for key in ("stop_date", "stop_date_raw", "suspended_date", "issuance_ended_raw"):
        if real_stop_date(metadata.get(key)):
            return "stopped"
    if "중단" in str(metadata.get("issue_status") or ""):
        return "stopped"
    return "active"


def apply_identity(card: Dict[str, Any], metadata: Dict[str, Any]) -> Dict[str, Any]:
    card["issuer"] = metadata.get("issuer") or "BC카드"
    card["name"] = metadata.get("name") or metadata.get("product_name") or card.get("name")
    card["product_id"] = product_id_for(metadata)
    card["status"] = status_of(metadata)
    card["posted_at"] = normalize_date(metadata.get("posted_at") or metadata.get("launch_date"))
    card["source_url"] = metadata.get("pdf_url") or metadata.get("url")
    if metadata.get("card_type") in {"CREDIT", "CHECK"}:
        card["card_type"] = metadata["card_type"]
    return card


def enforce_review_identity(card: Dict[str, Any], text: str, text_is_evidence: bool = True) -> Dict[str, Any]:
    """준법심의 날짜를 여신금융협회 심의필 날짜로 오인하지 못하게 한다.

    PDF를 첨부해 읽힌 문서는 대조할 추출 텍스트가 없다. 이때는 원문 대조를 건너뛰되,
    모델이 돌려준 review_no 자체가 여신금융협회 문구인지는 그대로 따진다.
    """
    if text_is_evidence and ("여신금융협회" not in text or "심의필" not in text):
        card["as_of"] = None
        card["review_no"] = None
        return card
    review_no = str(card.get("review_no") or "")
    if "여신금융협회" not in review_no:
        card["as_of"] = None
        card["review_no"] = None
    return card


def output_path(out_dir: Path, product_id: str, issuer: str = "BC카드") -> Path:
    safe_id = re.sub(r"[^0-9A-Za-z가-힣._-]+", "_", product_id).strip("._")
    if not safe_id:
        raise RuntimeError("상품 식별자로 파일명을 만들 수 없다")
    safe_issuer = re.sub(r"[^0-9A-Za-z가-힣._-]+", "_", issuer).strip("._")
    return out_dir / f"{safe_issuer}-{safe_id}.json"


def atomic_write(path: Path, payload: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temp_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as temp_file:
            json.dump(payload, temp_file, ensure_ascii=False, indent=2)
            temp_file.write("\n")
        os.replace(temp_name, path)
    except Exception:
        try:
            os.unlink(temp_name)
        except FileNotFoundError:
            pass
        raise


def expected_output_is_valid(path: Path, product_id: str) -> bool:
    try:
        card = json.loads(path.read_text(encoding="utf-8"))
        validate_card(card)
        return card.get("product_id") == product_id
    except (OSError, ValueError):
        return False


def extract_one(
        pdf_path: Path,
        text: str,
        metadata: Dict[str, Any],
        examples: str,
        api_key: str,
        model: str,
        pdf_bytes: Optional[bytes] = None,
) -> Dict[str, Any]:
    attached = pdf_bytes is not None
    prompt = build_prompt(text, metadata, examples, attached)
    first = call_gemini(prompt, TEMPERATURES[0], api_key, model, pdf_bytes=pdf_bytes)
    second_error: Optional[str] = None
    try:
        second = call_gemini(prompt, TEMPERATURES[1], api_key, model, pdf_bytes=pdf_bytes)
        mismatches = differences(numeric_projection(first), numeric_projection(second))
    except RuntimeError as error:
        second_error = str(error)
        mismatches = [{"path": "$", "first": "valid", "second": "second extraction failed"}]
    card = enforce_review_identity(apply_identity(first, metadata), text, not attached)
    maximum, complete = internal_maximum(card)
    card["_extraction_check"] = {
        "source_pdf": pdf_path.name,
        "source_mode": "attached_pdf" if attached else "extracted_text",
        "runs": 2,
        "temperatures": list(TEMPERATURES),
        "numeric_consensus": not mismatches,
        "mismatches": mismatches,
        "second_run_error": second_error,
        "external_max_benefit": {
            "status": "UNAVAILABLE",
            "internal_krw": maximum,
            "internal_complete": complete,
            "external_krw": None,
            "source_url": None,
            "note": "공개된 동일 기준 외부 값을 아직 확인하지 못함",
        },
    }
    return card


def iter_pdf_files(src: Path, manifest_rows: Optional[Dict[Path, Dict[str, Any]]] = None) -> Iterable[Path]:
    if manifest_rows is not None:
        return sorted(manifest_rows)
    return sorted(src.glob("*.pdf"))


def main() -> None:
    args = parse_args()
    src = args.src.expanduser().resolve()
    out_dir = args.out.expanduser().resolve()
    if not src.is_dir():
        raise SystemExit(f"PDF 디렉터리가 없다: {src}")
    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not api_key:
        raise SystemExit("GEMINI_API_KEY가 설정되지 않았다")

    try:
        manifest_rows = None
        if args.manifest:
            manifest_rows = load_manifest(src, args.manifest.expanduser().resolve())
            metadata_by_file = {}
        else:
            metadata_by_file = load_metadata(src)
        examples = verified_examples()
    except (OSError, ValueError, RuntimeError) as error:
        raise SystemExit(str(error))

    model = selected_model()
    print(f"카드 공시 추출 시작 · 모델 {model} · 이중 추출 {TEMPERATURES[0]}/{TEMPERATURES[1]}")
    eligible = 0
    attached = 0
    skipped_oversize = 0
    failures: List[str] = []
    expected_paths: List[Path] = []

    for pdf_path in iter_pdf_files(src, manifest_rows):
        metadata = manifest_rows.get(pdf_path) if manifest_rows is not None else metadata_by_file.get(pdf_path.name)
        if metadata is None:
            failures.append(f"{pdf_path.name}: 수집 메타데이터 없음")
            print(f"  실패 · {pdf_path.name} · 수집 메타데이터 없음")
            continue
        try:
            pages = pdf_pages(pdf_path)
            text = pdf_text(pdf_path)
        except RuntimeError as error:
            failures.append(f"{pdf_path.name}: {error}")
            print(f"  실패 · {pdf_path.name} · PDF 판독 실패")
            continue
        density = chars_per_page(text, pages)
        needs_attachment = not is_textual(text, pages)
        if needs_attachment and pdf_path.stat().st_size > MAX_INLINE_PDF_BYTES:
            skipped_oversize += 1
            print(f"  첨부 제외 · {pdf_path.name} · {pdf_path.stat().st_size / 1024 / 1024:.1f}MB 상한 초과")
            continue

        eligible += 1
        product_id = product_id_for(metadata)
        destination = output_path(out_dir, product_id, metadata.get("issuer") or "BC카드")
        expected_paths.append(destination)
        if destination.exists() and not args.force and expected_output_is_valid(destination, product_id):
            print(f"  기존 사용 · {pdf_path.name}")
            continue
        pdf_bytes = pdf_path.read_bytes() if needs_attachment else None
        if needs_attachment:
            attached += 1
            print(f"  원본 첨부 · {pdf_path.name} · 추출 텍스트 {density:.0f}자/쪽")
        try:
            card = extract_one(pdf_path, text, metadata, examples, api_key, model, pdf_bytes)
            atomic_write(destination, card)
            consensus = "일치" if card["_extraction_check"]["numeric_consensus"] else "불일치"
            print(f"  추출 완료 · {pdf_path.name} · 숫자 {consensus}")
        except (OSError, ValueError, RuntimeError) as error:
            failures.append(f"{pdf_path.name}: {error}")
            print(f"  추출 실패 · {pdf_path.name} · {type(error).__name__}")
        time.sleep(0.5)

    missing = [path.name for path in expected_paths if not path.exists()]
    stale = sorted(path.name for path in out_dir.glob("*.json") if path not in set(expected_paths)) if out_dir.exists() else []
    print(f"\n대상 {eligible}장(그중 원본 첨부 {attached}장) · 첨부 제외 {skipped_oversize}장 · 실패 {len(failures)}장")
    if stale:
        print(f"대상 밖 기존 JSON {len(stale)}개는 보존함")
    if failures or missing:
        for failure in failures:
            print(f"  · {failure}", file=sys.stderr)
        for filename in missing:
            print(f"  · 출력 없음: {filename}", file=sys.stderr)
        raise SystemExit(1)
    print(f"카드 JSON {eligible}개 준비 완료 → {out_dir}")


if __name__ == "__main__":
    main()

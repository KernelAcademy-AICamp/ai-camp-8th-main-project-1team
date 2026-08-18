#!/usr/bin/env python3
"""카드 LLM 추출기와 카탈로그 병합의 빠른 단위 시험."""

import base64
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock


HERE = Path(__file__).resolve().parent


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


extract_llm = load_module("extract_llm", HERE / "extract_llm.py")
build_catalog = load_module("build_catalog", HERE / "build_catalog.py")


def sample_card():
    return {
        "issuer": "BC카드",
        "name": "시험카드",
        "product_id": "1",
        "status": "active",
        "annual_fee": [
            {"scope": "국내전용", "brand": "BC", "total": 10000, "base": 5000, "affiliate": 5000}
        ],
        "performance": {
            "period": "전월 1일~말일",
            "basis": "승인일",
            "tiers": [300000],
            "excluded": [],
        },
        "benefits": [{
            "group": "커피",
            "kind": "할인",
            "rate_percent": 5,
            "requires_tier": 300000,
            "targets": [{"category": "커피", "brands": ["스타벅스"]}],
            "monthly_cap_by_tier": {"300000": 5000},
        }],
        "combined_caps": [],
        "benefit_excluded": [],
        "non_monetary": [],
        "schema_gaps": [],
    }


class FakeResponse:
    def __init__(self, payload):
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def read(self):
        return json.dumps(self.payload, ensure_ascii=False).encode("utf-8")


class ExtractLlmTest(unittest.TestCase):

    def test_text_gate_includes_exactly_200_chars_per_page(self):
        self.assertFalse(extract_llm.is_textual("가" * 399, 2))
        self.assertTrue(extract_llm.is_textual("가" * 400, 2))

    def test_empty_model_uses_fixed_default(self):
        self.assertEqual(extract_llm.DEFAULT_MODEL, extract_llm.selected_model({"GEMINI_MODEL": ""}))
        self.assertEqual(extract_llm.DEFAULT_MODEL, extract_llm.selected_model({}))
        self.assertEqual("fixed-model", extract_llm.selected_model({"GEMINI_MODEL": "fixed-model"}))

    def test_long_pdf_stem_uses_stable_metadata_number(self):
        self.assertEqual("104520", extract_llm.product_id_for({"filename": "104520.pdf", "no": 108}))
        self.assertEqual("46", extract_llm.product_id_for({
            "filename": "46_platinum_260101_brand_choice.pdf", "no": 46,
        }))

    def test_manifest_product_id_is_prefixed_by_issuer(self):
        self.assertEqual("KB-09637", extract_llm.product_id_for({
            "issuer": "KB국민카드", "product_id": "09637",
        }))
        self.assertEqual("HANA-09637", extract_llm.product_id_for({
            "issuer": "하나카드", "product_id": "09637",
        }))

    def test_compliance_review_is_not_used_as_finance_association_date(self):
        card = sample_card()
        card["as_of"] = "2026-06-05"
        card["review_no"] = "준법심의 L-26-2028(2026.06.05)"
        extract_llm.enforce_review_identity(card, "준법심의 L-26-2028(2026.06.05)")
        self.assertIsNone(card["as_of"])
        self.assertIsNone(card["review_no"])

    def test_finance_association_review_is_preserved(self):
        card = sample_card()
        card["as_of"] = "2026-07-29"
        card["review_no"] = "여신금융협회 심의필 제 2026-C1a-11301호"
        extract_llm.enforce_review_identity(card, card["review_no"])
        self.assertEqual("2026-07-29", card["as_of"])

    def test_api_key_is_header_not_url(self):
        request = extract_llm.gemini_request("원문", 0, "secret-value", "fixed-model")
        self.assertNotIn("secret-value", request.full_url)
        self.assertEqual("secret-value", request.get_header("X-goog-api-key"))

    def test_attached_pdf_is_sent_as_inline_data(self):
        request = extract_llm.gemini_request("원문", 0, "secret", "fixed-model", b"%PDF-1.7 body")
        parts = json.loads(request.data.decode("utf-8"))["contents"][0]["parts"]
        self.assertEqual("원문", parts[0]["text"])
        self.assertEqual("application/pdf", parts[1]["inline_data"]["mime_type"])
        self.assertEqual(b"%PDF-1.7 body", base64.b64decode(parts[1]["inline_data"]["data"]))

    def test_text_request_carries_no_inline_data(self):
        request = extract_llm.gemini_request("원문", 0, "secret", "fixed-model")
        parts = json.loads(request.data.decode("utf-8"))["contents"][0]["parts"]
        self.assertEqual(1, len(parts))

    def test_attached_prompt_drops_the_empty_text_block(self):
        prompt = extract_llm.build_prompt("", {"issuer": "우리카드", "name": "시험카드"}, "[]", True)
        self.assertNotIn("PDF 전문 시작", prompt)
        self.assertIn("첨부된 PDF 원본", prompt)

    def test_attached_pdf_keeps_review_when_extracted_text_is_empty(self):
        card = sample_card()
        card["as_of"] = "2026-07-29"
        card["review_no"] = "여신금융협회 심의필 제 2026-C1a-11301호"
        extract_llm.enforce_review_identity(card, "", text_is_evidence=False)
        self.assertEqual("2026-07-29", card["as_of"])

    def test_attached_pdf_still_rejects_compliance_review(self):
        card = sample_card()
        card["as_of"] = "2026-06-05"
        card["review_no"] = "준법심의 L-26-2028(2026.06.05)"
        extract_llm.enforce_review_identity(card, "", text_is_evidence=False)
        self.assertIsNone(card["as_of"])
        self.assertIsNone(card["review_no"])

    def test_call_retries_invalid_json_and_validates_second_response(self):
        valid_payload = {
            "candidates": [{"content": {"parts": [{"text": json.dumps(sample_card(), ensure_ascii=False)}]}}]
        }
        responses = iter([
            FakeResponse({"candidates": [{"content": {"parts": [{"text": "not-json"}]}}]}),
            FakeResponse(valid_payload),
        ])

        def opener(request, timeout):
            self.assertEqual(180, timeout)
            return next(responses)

        with mock.patch.object(extract_llm.time, "sleep"):
            card = extract_llm.call_gemini("원문", 0, "secret", "fixed-model", opener=opener)
        self.assertEqual("시험카드", card["name"])

    def test_numeric_projection_ignores_order_but_finds_changed_rate(self):
        first = sample_card()
        second = json.loads(json.dumps(first, ensure_ascii=False))
        second["annual_fee"].reverse()
        self.assertEqual(extract_llm.numeric_projection(first), extract_llm.numeric_projection(second))
        second["benefits"][0]["rate_percent"] = 7
        found = extract_llm.differences(
            extract_llm.numeric_projection(first), extract_llm.numeric_projection(second)
        )
        self.assertTrue(any(row["path"].endswith("rate_percent") for row in found))

    def _benefit(self, group, rate, category, brands=None, kind="할인"):
        return {
            "group": group, "kind": kind, "rate_percent": rate,
            "targets": [{"category": category, "brands": brands or []}],
        }

    def _compare(self, first_benefits, second_benefits):
        first = {**sample_card(), "benefits": first_benefits}
        second = {**sample_card(), "benefits": second_benefits}
        return extract_llm.differences(
            extract_llm.numeric_projection(first), extract_llm.numeric_projection(second)
        )

    def test_brands_written_inside_the_category_still_line_up(self):
        """같은 사실을 어느 칸에 적었는지는 실행마다 흔들린다. 숫자가 같으면 일치다."""
        self.assertEqual([], self._compare(
            [self._benefit("여가", 10, "영화", ["CGV", "롯데시네마"])],
            [self._benefit("여가", 10, "영화(CGV, 롯데시네마)")],
        ))

    def test_group_name_alone_does_not_break_consensus(self):
        self.assertEqual([], self._compare(
            [self._benefit("여가", 10, "영화")], [self._benefit("생활", 10, "영화")],
        ))

    def test_benefit_order_does_not_break_consensus(self):
        self.assertEqual([], self._compare(
            [self._benefit("여가", 10, "영화"), self._benefit("여가", 5, "스포츠 업종")],
            [self._benefit("여가", 5, "스포츠 업종"), self._benefit("여가", 10, "영화")],
        ))

    def test_swapped_rates_are_still_caught(self):
        """줄을 맞춰준다고 짝까지 봐주지는 않는다. 영화 10/스포츠 5가 뒤집히면 불일치다."""
        found = self._compare(
            [self._benefit("여가", 10, "영화"), self._benefit("여가", 5, "스포츠 업종")],
            [self._benefit("여가", 5, "영화"), self._benefit("여가", 10, "스포츠 업종")],
        )
        self.assertEqual(2, len(found))
        self.assertTrue(all(item["path"].endswith("rate_percent") for item in found))

    def test_benefit_kind_disagreement_is_still_caught(self):
        found = self._compare(
            [self._benefit("여가", 10, "영화", kind="적립")],
            [self._benefit("여가", 10, "영화", kind="할인")],
        )
        self.assertEqual(["$.benefits[0].kind"], [item["path"] for item in found])

    def test_annual_fee_rows_line_up_regardless_of_order(self):
        first = {**sample_card(), "annual_fee": [
            {"scope": "국내전용", "brand": "Local", "total": 15000},
            {"scope": "해외겸용", "brand": "Visa", "total": 22000},
        ]}
        second = {**sample_card(), "annual_fee": [
            {"scope": "해외겸용", "brand": "Visa", "total": 22000},
            {"scope": "국내전용", "brand": "Local", "total": 15000},
        ]}
        self.assertEqual([], extract_llm.differences(
            extract_llm.numeric_projection(first), extract_llm.numeric_projection(second)
        ))

    def test_swapped_annual_fee_amounts_are_still_caught(self):
        first = {**sample_card(), "annual_fee": [
            {"scope": "국내전용", "brand": "Local", "total": 15000},
            {"scope": "해외겸용", "brand": "Visa", "total": 22000},
        ]}
        second = {**sample_card(), "annual_fee": [
            {"scope": "국내전용", "brand": "Local", "total": 22000},
            {"scope": "해외겸용", "brand": "Visa", "total": 15000},
        ]}
        found = extract_llm.differences(
            extract_llm.numeric_projection(first), extract_llm.numeric_projection(second)
        )
        self.assertEqual(2, len(found))

    def test_internal_maximum_applies_combined_cap(self):
        card = sample_card()
        card["benefits"] = [
            {"group": "A", "kind": "할인", "rate_percent": 5,
             "combined_cap_group": "통합", "monthly_cap_by_tier": {"300000": 5000}},
            {"group": "B", "kind": "할인", "rate_percent": 5,
             "combined_cap_group": "통합", "monthly_cap_by_tier": {"300000": 5000}},
        ]
        card["combined_caps"] = [
            {"group": "통합", "members": ["A", "B"], "cap_by_tier": {"300000": 7000}}
        ]
        maximum, complete = extract_llm.internal_maximum(card)
        self.assertEqual(7000, maximum)
        self.assertTrue(complete)

    def test_human_source_wins_duplicate_product_id(self):
        with tempfile.TemporaryDirectory() as temp_name:
            temp_dir = Path(temp_name)
            cards_dir = temp_dir / "cards"
            cards_dir.mkdir()
            llm = sample_card()
            llm["name"] = "LLM 이름"
            (cards_dir / "llm.json").write_text(json.dumps(llm, ensure_ascii=False), encoding="utf-8")
            human = sample_card()
            human["name"] = "사람 이름"
            draft_path = temp_dir / "draft.json"
            draft_path.write_text(json.dumps({"cards": [human]}, ensure_ascii=False), encoding="utf-8")

            with mock.patch.object(build_catalog, "CARDS", str(cards_dir)), \
                    mock.patch.object(build_catalog, "DRAFT", str(draft_path)):
                rows = build_catalog.load_sources()

        self.assertEqual(1, len(rows))
        self.assertEqual("사람 이름", rows[0][0]["name"])
        self.assertEqual("HUMAN_VERIFIED", rows[0][1])

    def test_duplicate_exclusion_code_merges_labels(self):
        card = sample_card()
        card["performance"]["excluded"] = [
            {"code": "HOUSING", "label": "아파트관리비"},
            {"code": "HOUSING", "label": "부동산 임대료"},
        ]
        problems = []
        rows = build_catalog.exclusions_of(card, problems)
        self.assertEqual(1, len(rows))
        self.assertEqual("아파트관리비 / 부동산 임대료", rows[0]["label"])

    # ── 발급 상태 ────────────────────────────────────────────────
    #
    # 예전에는 `card["status"] = "active"` 가 박혀 있었다. 발급이 끝난 카드를 담아도
    # 발급 중으로 적혀 신규 발급 추천에 섞인다 — 신청할 수 없는 카드를 권하게 된다.
    # 카드사마다 신호 이름이 달라서, 한 벌만 잠그면 나머지 카드사에서 다시 샌다.

    def test_stopped_is_read_from_each_issuer_signal(self):
        for label, metadata in (
            ("후보 목록", {"active_verified": False}),
            ("BC 불리언", {"currently_issued": False}),
            ("BC 문구", {"issue_status": "발급중단"}),
            ("현대", {"issued": False}),
            ("롯데", {"issuance_ended": True}),
            ("우리", {"suspended_date": "2024-03-01"}),
            ("KB", {"stop_date": "2023-11-30"}),
        ):
            with self.subTest(label):
                self.assertEqual("stopped", extract_llm.status_of(metadata))

    def test_empty_date_markers_are_not_read_as_stopped(self):
        """빈 값 표기가 카드사마다 다르다. `-` 를 날짜로 읽으면 발급 중인 카드가 전부 중단된다."""
        for value in ("-", "", None):
            with self.subTest(repr(value)):
                self.assertEqual("active", extract_llm.status_of({"stop_date": value}))

    def test_no_signal_means_active(self):
        """삼성·농협·신한은 목록에 신호가 없다. 발급 여부 확인은 후보 선정이 맡는다."""
        self.assertEqual("active", extract_llm.status_of({"issuer": "삼성카드"}))

    def test_identity_writes_the_real_status(self):
        card = sample_card()
        extract_llm.apply_identity(card, {"name": "옛카드", "product_id": "1", "stop_date": "2022-01-05"})
        self.assertEqual("stopped", card["status"])


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3

import importlib.util
import sys
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
SPEC = importlib.util.spec_from_file_location("select_youth_cards", HERE / "select_youth_cards.py")
target = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(target)


def card(issuer, name, kind="CREDIT", score=3, eligible=True):
    return {
        "issuer": issuer,
        "name": name,
        "product_id": name,
        "card_type": kind,
        "posted_at": "2026-01-01",
        "eligible": eligible,
        "youth_priority_score": score,
        "evidence_level": "NAME_ONLY",
    }


class SelectYouthCardsTests(unittest.TestCase):
    def test_restricted_and_special_products_are_excluded(self):
        for name in ("회사 법인카드", "대학교 학생증 체크카드", "S-OIL 주유전용카드"):
            item = target.score_candidate({
                "issuer": "시험", "name": name, "product_id": name,
                "card_type": "UNKNOWN", "document_name": None, "pdf_path": None,
            })
            self.assertFalse(item["eligible"], name)

    def test_youth_keywords_raise_priority_without_becoming_recommendation_score(self):
        item = target.score_candidate({
            "issuer": "시험", "name": "온라인 간편결제 대중교통 OTT 카드",
            "product_id": "1", "card_type": "CREDIT", "document_name": None,
            "pdf_path": None,
        })
        self.assertEqual(item["youth_priority_score"], 11)
        self.assertEqual(item["evidence_level"], "NAME_ONLY")

    def test_members_and_transit_capability_are_not_transport_benefits(self):
        for name in ("4-H멤버스카드", "생활 체크카드(후불교통)", "게임 체크카드(비교통)"):
            item = target.score_candidate({
                "issuer": "시험", "name": name, "product_id": name,
                "card_type": "CHECK", "document_name": None, "pdf_path": None,
            })
            self.assertNotIn("교통", item["matched_groups"], name)

    def test_business_and_closed_membership_products_are_excluded(self):
        for name in ("메인비즈 CEO카드", "나라사랑카드", "시청 지자체카드", "제휴카드_온네임용"):
            item = target.score_candidate({
                "issuer": "시험", "name": name, "product_id": name,
                "card_type": "CREDIT", "document_name": None, "pdf_path": None,
            })
            self.assertFalse(item["eligible"], name)

    def test_unverified_active_status_is_excluded_by_default(self):
        item = target.score_candidate({
            "issuer": "시험", "name": "일반카드", "product_id": "1",
            "card_type": "CREDIT", "document_name": None, "pdf_path": None,
            "active_verified": False,
        })
        self.assertFalse(item["eligible"])
        self.assertEqual(item["exclusion_reason"], "발급 상태 미확인")
        included = target.score_candidate(item, include_unverified_status=True)
        self.assertTrue(included["eligible"])

    def test_attachment_documents_are_not_treated_as_cards(self):
        """'…선택서비스 안내서'는 카드가 아니라 본체 카드에 딸린 문서다."""
        self.assertEqual(
            "부속 안내서",
            target.exclusion_reason("대한항공카드 Edition2 MasterCard World 선택서비스 안내서"),
        )
        self.assertIsNone(target.exclusion_reason("대한항공카드 Edition2"))

    def test_confirmed_stopped_product_is_excluded_as_stopped(self):
        """발급이 끝난 것이 확인된 상품은 '미확인'이 아니라 '발급중단'으로 뺀다."""
        item = target.score_candidate({
            "issuer": "현대카드", "name": "일반카드", "product_id": "1",
            "card_type": "CREDIT", "document_name": None, "pdf_path": None,
            "active_verified": True, "active": False,
        })
        self.assertFalse(item["eligible"])
        self.assertEqual(item["exclusion_reason"], "발급중단")

    def test_stopped_product_stays_excluded_with_unverified_flag(self):
        """--include-unverified-status는 '미확인'을 푸는 것이지 발급중단을 되살리지 않는다."""
        item = target.score_candidate({
            "issuer": "현대카드", "name": "일반카드", "product_id": "1",
            "card_type": "CREDIT", "document_name": None, "pdf_path": None,
            "active_verified": True, "active": False,
        }, include_unverified_status=True)
        self.assertFalse(item["eligible"])
        self.assertEqual(item["exclusion_reason"], "발급중단")

    def test_issuance_state_reads_the_product_not_the_issuer_name(self):
        self.assertEqual((True, True), target.issuance_state("hyundaicard", {"issued": True}))
        self.assertEqual((True, False), target.issuance_state("hyundaicard", {"issued": False}))
        self.assertEqual((True, False), target.issuance_state("samsungcard", {"discontinued": True}))
        self.assertEqual((True, True), target.issuance_state("samsungcard", {"discontinued": False}))

    def test_issuance_state_falls_back_to_collector_filter(self):
        """상태 필드가 없으면 수집 단계에서 중단분을 걸렀는지로 갈린다."""
        self.assertEqual((True, True), target.issuance_state("wooricard", {}))
        self.assertEqual((True, True), target.issuance_state("lottecard", {}))
        self.assertEqual((False, True), target.issuance_state("nhcard", {}))

    def test_document_selection_prefers_latest_goods_description(self):
        documents = [
            {"doc_type": "intl_basic", "filename": "국제브랜드 안내장.pdf", "url": "a", "begin_date": "2026-01-01"},
            {"doc_type": "goods_desc", "filename": "상품설명서.pdf", "url": "b", "begin_date": "2025-01-01"},
            {"doc_type": "goods_desc", "filename": "새 상품설명서.pdf", "url": "c", "begin_date": "2026-01-01"},
        ]
        self.assertEqual(target.choose_document(documents)["url"], "c")

    def test_shortlist_keeps_issuer_quota_and_check_minimum(self):
        rows = []
        for issuer in ("가카드", "나카드"):
            rows.extend(card(issuer, f"{issuer}-신용-{i}", score=20-i) for i in range(5))
            rows.extend(card(issuer, f"{issuer}-체크-{i}", "CHECK", score=5-i) for i in range(2))
        selected = target.shortlist(rows, per_issuer=4, check_min=1, total=8)
        for issuer in ("가카드", "나카드"):
            issuer_rows = [item for item in selected if item["issuer"] == issuer]
            self.assertEqual(len(issuer_rows), 4)
            self.assertGreaterEqual(sum(item["card_type"] == "CHECK" for item in issuer_rows), 1)

    def test_stable_id_caps_database_length(self):
        self.assertEqual(target.stable_id("short-id"), "short-id")
        self.assertLessEqual(len(target.stable_id("x" * 100)), 30)

    def test_card_name_check_marker_wins_over_bad_category_metadata(self):
        self.assertEqual(target.inferred_card_type("어떤 체크카드", "credit"), "CHECK")

    def test_shortlist_caps_same_benefit_group(self):
        rows = []
        for i in range(6):
            item = card("가카드", f"여행-{i}", score=20-i)
            item["matched_groups"] = ["여행·해외"]
            rows.append(item)
        for i in range(3):
            item = card("가카드", f"일반-{i}", score=3-i)
            item["matched_groups"] = []
            rows.append(item)
        selected = target.shortlist(rows, per_issuer=6, check_min=0, total=6, group_cap=2)
        self.assertEqual(sum("여행·해외" in x["matched_groups"] for x in selected), 2)


if __name__ == "__main__":
    unittest.main()

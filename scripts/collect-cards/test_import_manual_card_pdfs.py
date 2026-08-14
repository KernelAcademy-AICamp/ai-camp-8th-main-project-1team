#!/usr/bin/env python3

import importlib.util
import sys
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
SPEC = importlib.util.spec_from_file_location(
    "import_manual_card_pdfs", HERE / "import_manual_card_pdfs.py"
)
target = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(target)


def row(issuer: str, name: str, status: str = "policy_blocked") -> dict:
    return {"issuer": issuer, "name": name, "status": status}


class CandidateIndexTests(unittest.TestCase):
    def test_only_blocked_rows_need_a_manual_file(self):
        """자동으로 받았거나 이미 반입한 행까지 사람에게 요구하지 않는다."""
        rows = [
            row("삼성카드", "나나카드"),
            row("삼성카드", "가가카드"),
            row("현대카드", "이미받음", status="downloaded"),
            row("하나카드", "이미있음", status="existing"),
        ]
        index = target.candidate_index(rows)
        self.assertEqual(
            {("삼성카드", 1): "가가카드", ("삼성카드", 2): "나나카드"},
            {key: value["name"] for key, value in index.items()},
        )

    def test_issuers_come_from_the_manifest_not_a_fixed_list(self):
        """어느 카드사가 수동 경로를 타는지는 robots 정책이 정한다."""
        index = target.candidate_index([row("롯데카드", "가가카드")])
        self.assertEqual([("롯데카드", 1)], list(index))

    def test_numbering_is_per_issuer_and_alphabetical(self):
        rows = [row("삼성카드", "하하카드"), row("우리카드", "가가카드"), row("삼성카드", "가가카드")]
        index = target.candidate_index(rows)
        self.assertEqual("가가카드", index[("삼성카드", 1)]["name"])
        self.assertEqual("하하카드", index[("삼성카드", 2)]["name"])
        self.assertEqual("가가카드", index[("우리카드", 1)]["name"])


class MatchByNameTests(unittest.TestCase):
    def setUp(self):
        self.candidates = {
            ("삼성카드", 1): {"issuer": "삼성카드", "name": "모니모페이카드"},
            ("삼성카드", 2): {"issuer": "삼성카드", "name": "PORSCHE 삼성카드"},
        }

    def test_issuer_filename_with_document_suffix_is_matched(self):
        """카드사가 주는 이름은 카드명 뒤에 문서 종류가 붙는다."""
        self.assertEqual(
            ("삼성카드", 1),
            target.match_by_name("모니모페이카드 이용안내장", self.candidates),
        )

    def test_unrelated_file_is_not_matched(self):
        self.assertIsNone(target.match_by_name("전혀 다른 문서", self.candidates))

    def test_ambiguous_prefix_is_refused(self):
        """카드명이 다른 카드명의 앞부분이면 사람이 번호로 구분해야 한다."""
        candidates = {
            ("삼성카드", 1): {"issuer": "삼성카드", "name": "삼성카드"},
            ("삼성카드", 2): {"issuer": "삼성카드", "name": "삼성카드 & YOUNG"},
        }
        self.assertIsNone(target.match_by_name("삼성카드 & YOUNG 이용안내장", candidates))


class NumberedSourcesTests(unittest.TestCase):
    def test_any_issuer_prefix_is_read(self):
        self.assertEqual(
            ("삼성카드", "01", "탭탭오 카드"),
            target.NUMBERED.match("삼성카드_01_탭탭오 카드.pdf").groups(),
        )

    def test_card_name_may_contain_underscores(self):
        match = target.NUMBERED.match("우리카드_01_KREAM 우리카드_메탈.pdf")
        self.assertEqual("우리카드", match.group(1))
        self.assertEqual("KREAM 우리카드_메탈", match.group(3))

    def test_unnumbered_file_is_ignored(self):
        self.assertIsNone(target.NUMBERED.match("상품안내장_그냥받은파일.pdf"))


if __name__ == "__main__":
    unittest.main()

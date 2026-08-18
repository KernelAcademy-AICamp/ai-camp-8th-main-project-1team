#!/usr/bin/env python3

import importlib.util
import sys
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
SPEC = importlib.util.spec_from_file_location(
    "hyundaicard_downloader", HERE / "hyundaicard_downloader.py"
)
target = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(target)


def product_html(name: str, divr_lines: list[str], sqno: str) -> str:
    items = "".join(f'<li class="divr_txt">{line}</li>' for line in divr_lines)
    return (
        f'<p class="h4_m_lt">{name}</p>'
        f'<ul>{items}</ul>'
        f'<a href="#" sqno="{sqno}">보기</a>'
    )


def parse(html: str) -> list[dict]:
    parser = target.HyundaiProductParser()
    parser.feed(html)
    return parser.products


class HyundaiProductParserTests(unittest.TestCase):
    def test_stop_date_does_not_overwrite_launch_date(self):
        """발급중단일이 뒤에 와도 출시일 자리를 덮지 않는다.

        현대 공시 목록은 중단된 상품에 `li.divr_txt`를 둘 내려준다. 라벨을 안 보면
        뒤에 오는 발급중단일이 출시일을 덮어써, 2007년 출시 카드가 2013년 출시로 바뀐다.
        """
        html = product_html(
            "현대카드M BLUEmembers",
            ["상품출시일 : 2007.04.13", "발급중단일 : 2013.07.01"],
            "154211",
        )
        product = parse(html)[0]
        self.assertEqual("2007-04-13", product["launch_date"])
        self.assertEqual("2013-07-01", product["stop_date"])
        self.assertFalse(product["issued"])

    def test_active_product_has_no_stop_date(self):
        html = product_html("알파벳카드S BOLD", ["상품출시일 : 2026.07.01"], "179615")
        product = parse(html)[0]
        self.assertEqual("2026-07-01", product["launch_date"])
        self.assertIsNone(product["stop_date"])
        self.assertTrue(product["issued"])

    def test_unlabelled_single_line_is_still_read_as_launch_date(self):
        html = product_html("라벨없는카드", ["2020.01.02"], "100001")
        product = parse(html)[0]
        self.assertEqual("2020-01-02", product["launch_date"])
        self.assertTrue(product["issued"])

    def test_each_product_starts_with_clean_state(self):
        """앞 상품의 발급중단일이 다음 상품으로 새지 않는다."""
        html = product_html(
            "중단된카드", ["상품출시일 : 2007.04.13", "발급중단일 : 2013.07.01"], "154211",
        ) + product_html("살아있는카드", ["상품출시일 : 2026.07.01"], "179615")
        stopped, active = parse(html)
        self.assertFalse(stopped["issued"])
        self.assertIsNone(active["stop_date"])
        self.assertTrue(active["issued"])


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
SPEC = importlib.util.spec_from_file_location(
    "samsungcard_downloader", HERE / "samsungcard_downloader.py"
)
target = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(target)


class SamsungCardDownloaderTests(unittest.TestCase):
    def test_download_url_is_encoded_exactly_once(self):
        encoded = "1zPVy1PuFbwGC7xZyM4qtp6yCq%2B1jLC9UwbG5HJoAp0%3D"
        url = target.build_download_url(encoded, "2")
        self.assertIn("Cq%2B1j", url)
        self.assertIn("Ap0%3D&sn=2", url)
        self.assertNotIn("%252B", url)
        self.assertNotIn("%253D", url)

    def test_html_entities_are_unescaped_before_path_sanitizing(self):
        value = " 스카이패스&#x2F;아시아나 &amp; 카드.pdf "
        self.assertEqual(
            target.safe_component(value, "fallback.pdf"),
            "스카이패스_아시아나 & 카드.pdf",
        )

    def test_discontinued_products_are_filtered(self):
        raw = [
            {
                "bltnbmTitNm": "현재 카드",
                "itgBlbdSn": "1",
                "bltnStrtdt": "20260810",
                "uploadFileList": [
                    {
                        "apnFileNm": "현재.pdf",
                        "apnFileSn": "1",
                        "apnFileGrpNoE": "abc%3D",
                        "apnFileGrpNo": "123",
                    }
                ],
            },
            {
                "bltnbmTitNm": "과거 카드 (신규 발급 중단)",
                "itgBlbdSn": "2",
                "bltnStrtdt": "20200101",
                "uploadFileList": [],
            },
        ]
        products, excluded = target.build_products(raw)
        self.assertEqual(excluded, 1)
        self.assertEqual([item["product_name"] for item in products], ["현재 카드"])

    def test_pdf_inspection_rejects_empty_and_accepts_pdf_markers(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sample.pdf"
            path.write_bytes(b"")
            with self.assertRaises(target.PdfValidationError):
                target.inspect_pdf(path)

            path.write_bytes(b"%PDF-1.6\nbody\n%%EOF\n")
            size, digest = target.inspect_pdf(path)
            self.assertEqual(size, path.stat().st_size)
            self.assertEqual(len(digest), 64)


if __name__ == "__main__":
    unittest.main()

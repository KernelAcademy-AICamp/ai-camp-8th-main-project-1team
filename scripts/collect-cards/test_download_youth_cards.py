#!/usr/bin/env python3

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
SPEC = importlib.util.spec_from_file_location(
    "download_youth_cards", HERE / "download_youth_cards.py"
)
target = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(target)


class Response:
    def __init__(self, body):
        self.body = body
        self.offset = 0

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def read(self, size=-1):
        if self.offset >= len(self.body):
            return b""
        end = len(self.body) if size < 0 else self.offset + size
        chunk = self.body[self.offset:end]
        self.offset += len(chunk)
        return chunk


class YouthCardDownloadTests(unittest.TestCase):
    def test_local_path_is_stable_and_separates_issuers(self):
        kb = {"issuer": "KB국민카드", "product_id": "123"}
        hana = {"issuer": "하나카드", "product_id": "123"}
        self.assertEqual("kb/123.pdf", target.local_relative_path(kb))
        self.assertEqual("hana/123.pdf", target.local_relative_path(hana))

    def test_selected_can_be_filtered_by_issuer(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "candidates.json"
            path.write_text(json.dumps({"selected": [
                {"issuer": "KB국민카드", "product_id": "1", "pdf_url": "https://a/1.pdf"},
                {"issuer": "하나카드", "product_id": "2", "pdf_url": "https://b/2.pdf"},
            ]}, ensure_ascii=False), encoding="utf-8")
            rows = target.load_selected(path, ["하나카드"])
        self.assertEqual(["하나카드"], [row["issuer"] for row in rows])

    def test_download_validates_and_atomically_saves_pdf(self):
        card = {
            "issuer": "하나카드", "name": "시험", "product_id": "1",
            "pdf_url": "https://example.com/1.pdf", "source_page": "https://example.com/",
        }
        body = b"%PDF-1.7\nbody\n%%EOF\n"
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "one.pdf"
            result = target.download_pdf(
                card, destination, False, 1, 0,
                opener=lambda request, timeout: Response(body),
            )
            size, digest = target.inspect_pdf(destination)
        self.assertEqual("downloaded", result["status"])
        self.assertEqual(len(body), size)
        self.assertEqual(digest, result["sha256"])

    def test_existing_pdf_is_reused_without_a_request(self):
        """robots가 막는 것은 새 요청이지 이미 가진 파일이 아니다."""
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "one.pdf"
            destination.write_bytes(b"%PDF-1.7\nbody\n%%EOF\n")
            reused = target.reuse_existing(destination)
        self.assertEqual("existing", reused["status"])
        self.assertEqual(0, reused["attempts"])

    def test_missing_or_broken_file_is_not_reused(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / "none.pdf"
            broken = Path(directory) / "broken.pdf"
            broken.write_bytes(b"<html>viewer</html>")
            self.assertIsNone(target.reuse_existing(missing))
            self.assertIsNone(target.reuse_existing(broken))


if __name__ == "__main__":
    unittest.main()

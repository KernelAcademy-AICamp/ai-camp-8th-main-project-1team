#!/usr/bin/env python3

import importlib.util
import sys
import unittest
import urllib.error
from pathlib import Path
from unittest import mock


HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
SPEC = importlib.util.spec_from_file_location("collector_policy", HERE / "collector_policy.py")
policy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(policy)


class Response:
    def __init__(self, body):
        self.body = body

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def read(self):
        return self.body


class CollectorPolicyTests(unittest.TestCase):
    def test_disallow_blocks_collection(self):
        body = b"User-agent: *\nDisallow: /\n"
        with mock.patch.object(policy.urllib.request, "urlopen", return_value=Response(body)):
            with self.assertRaises(policy.RobotsPolicyError):
                policy.require_robots_allowed(["https://example.com/cards"])

    def test_allow_permits_collection(self):
        body = b"User-agent: *\nAllow: /cards\nDisallow: /\n"
        with mock.patch.object(policy.urllib.request, "urlopen", return_value=Response(body)):
            policy.require_robots_allowed(["https://example.com/cards"])

    def test_missing_robots_is_treated_as_no_rules(self):
        error = urllib.error.HTTPError(
            "https://example.com/robots.txt", 404, "not found", {}, None
        )
        with mock.patch.object(policy.urllib.request, "urlopen", side_effect=error):
            policy.require_robots_allowed(["https://example.com/cards"])

    def test_unreadable_robots_fails_closed(self):
        with mock.patch.object(
            policy.urllib.request, "urlopen", side_effect=TimeoutError("timeout")
        ):
            with self.assertRaises(policy.RobotsPolicyError):
                policy.require_robots_allowed(["https://example.com/cards"])


if __name__ == "__main__":
    unittest.main()

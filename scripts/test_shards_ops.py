import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from pg_ops import OperationError, write_json
from shards_backup import FILES, HASH_RULE, check_set, fingerprint
from shards_experiment import measure, percentile
from shards_incident import application_events


class BackupSetTest(unittest.TestCase):
    def test_rejects_changed_archive(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp) / "test-backup"
            directory.mkdir()
            files = {}
            for name in FILES.values():
                path = directory / name
                path.write_bytes(b"mock archive")
                files[name] = fingerprint(path)
            write_json(directory / "manifest.json", {
                "format": 1, "status": "complete", "id": directory.name,
                "hash_rule": HASH_RULE, "files": files, "audit": {},
            })
            self.assertEqual(check_set(directory)[1]["status"], "complete")
            (directory / "shard-c.dump").write_bytes(b"mock damage!")
            with self.assertRaisesRegex(OperationError, "checksum.*shard-c"):
                check_set(directory)

    def test_rejects_an_unpublished_set(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp) / ".incomplete-test"
            directory.mkdir()
            with self.assertRaisesRegex(OperationError, "incomplete"):
                check_set(directory)


class IncidentLogTest(unittest.TestCase):
    def test_preserves_only_recognized_events(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            original = directory / "raw.log"
            original.write_text(
                "Generated token value: mock-secret\nMigration job 7: phase=COPYING, copied=100\n"
                "Order query failed on shard shardC (CannotGetJdbcConnectionException)\n",
                encoding="utf-8",
            )
            self.assertEqual(application_events(original, directory), "collected")
            sanitized = (directory / "application.log").read_text(encoding="utf-8")
            self.assertNotIn("mock-secret", sanitized)
            self.assertIn("Migration job 7: phase=COPYING, copied=100", sanitized)
            self.assertIn("CannotGetJdbcConnectionException", sanitized)


class MeasurementTest(unittest.TestCase):
    def test_separates_moved_user_results_from_control_user_and_includes_failures(self):
        args = SimpleNamespace(url="http://127.0.0.1:8080", label="test", users=[3, 101],
                               samples=12, workers=2, seed=42)
        with patch("shards_experiment.get_status", side_effect=lambda _, user: (
            ("503", 20.0) if user == 3 else ("200", 5.0)
        )):
            report = measure(args)
        moved, control = report["by_user"]["3"], report["by_user"]["101"]
        self.assertEqual(moved["error_rate"], 1)
        self.assertIsNone(moved["successful_latency_ms"]["p50"])
        self.assertEqual(control["error_rate"], 0)
        self.assertEqual(control["latency_ms"]["p95"], 5.0)
        self.assertEqual(report["statuses"], {"503": moved["requests"], "200": control["requests"]})
        self.assertEqual(moved["requests"] + control["requests"], args.samples)

    def test_handles_empty_samples_and_nearest_rank(self):
        self.assertIsNone(percentile([], .95))
        self.assertEqual(percentile(list(range(1, 101)), .95), 95)


if __name__ == "__main__":
    unittest.main()

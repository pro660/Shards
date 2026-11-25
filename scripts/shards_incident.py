"""Collect diagnostic metadata, allow-listed log messages, and readable dumps."""

import json
import re
import uuid
from datetime import datetime, timezone
from pathlib import Path

from pg_ops import OperationError, databases, dump, outside_workspace, query, run, write_json


WORKSPACE = Path(__file__).resolve().parent.parent
APP_EVENTS = re.compile(
    r"Migration job \d+(?:: phase=[A-Z]+, copied=\d+| did not advance \([A-Za-z0-9]+\))"
    r"|Order query failed on shard shard[A-C] \([A-Za-z0-9]+\)"
)
DB_EVENTS = {
    "ready": "database system is ready to accept connections",
    "shutdown-request": "shutdown request",
    "shutdown": "database system is shut down",
    "startup-recovery": "database system was not properly shut down",
    "connection-terminated": "terminating connection",
}


def application_events(path, output):
    if path is None:
        return "not requested"
    # Bound the input and persist only known application events.
    with path.open("rb") as log:
        log.seek(max(0, path.stat().st_size - 4_000_000))
        events = APP_EVENTS.findall(log.read(4_000_000).decode("utf-8", errors="replace"))
    (output / "application.log").write_text("\n".join(events) + "\n", encoding="utf-8")
    return "collected"


def metadata_state(database, output):
    snapshot = query(database, """
        SELECT json_build_object(
            'buckets', (SELECT json_agg(b ORDER BY b.bucket_id) FROM public.bucket_mapping AS b),
            'unfinished', (SELECT json_build_object(
                'jobId', j.job_id, 'phase', j.phase, 'bucketId', j.bucket_id,
                'sourceShardId', j.source_shard_id, 'targetShardId', j.target_shard_id,
                'sourceMappingVersion', j.source_mapping_version,
                'lastCopiedOrderId', j.last_copied_order_id, 'copiedOrderCount', j.copied_order_count,
                'updatedAt', j.updated_at
            ) FROM public.migration_jobs AS j WHERE j.finished_at IS NULL)
        )
    """)
    write_json(output / "metadata-state.json", json.loads(snapshot))
    return "collected"


def database_log(label, database, output, remote_shard_c):
    if (label == "shard-c" and remote_shard_c) or database.host not in ("127.0.0.1", "localhost", "::1"):
        return "remote Docker logs require local collection on the shard host"
    compose_file = "compose.shard-c.yaml" if label == "shard-c" else "compose.yaml"
    service = "meta-db" if label == "metadata" else label
    data = run(["docker", "compose", "-f", str(WORKSPACE / compose_file),
                "logs", "--no-color", "--since", "15m", "--tail", "500", service], timeout=20)
    counts = dict.fromkeys(DB_EVENTS, 0)
    for line in data.decode("utf-8", errors="replace").splitlines():
        for name, token in DB_EVENTS.items():
            if token in line:
                counts[name] += 1
    write_json(output / f"{label}-database-events.json", counts)
    return "collected"


def record_attempt(results, name, task):
    try:
        results[name] = task()
    except OperationError as error:
        results[name] = f"unavailable ({error})"
    except (OSError, ValueError) as error:
        results[name] = f"unavailable ({type(error).__name__})"


def capture_dump(database, path):
    partial = path.with_suffix(".partial")
    dump(database, partial)
    partial.rename(path)
    return "collected"


def capture(root, values, app_log, logs_only, remote_shard_c):
    root = outside_workspace(root)
    root.mkdir(parents=True, exist_ok=True)
    output = root / ("incident-" + uuid.uuid4().hex)
    output.mkdir(mode=0o700)
    started_at = datetime.now(timezone.utc).isoformat()
    results = {}
    record_attempt(results, "application-log", lambda: application_events(app_log, output))
    try:
        database_by_label = databases(values)
    except OperationError:
        results["database-configuration"] = "unavailable (check the database settings)"
        database_by_label = {}
    if database_by_label:
        record_attempt(results, "metadata-state", lambda: metadata_state(database_by_label["metadata"], output))
        database_logs = output / "database-logs"
        database_logs.mkdir()
        if not logs_only:
            dump_dir = output / "readable-databases"
            dump_dir.mkdir()
        for label, database in database_by_label.items():
            record_attempt(results, label + "-log", lambda: database_log(label, database, database_logs, remote_shard_c))
            if not logs_only:
                record_attempt(results, label + "-dump", lambda: capture_dump(database, dump_dir / (label + ".dump")))
    report = output / "incident.json"
    write_json(report, {
        "type": "incident", "started_at": started_at, "recorded_at": datetime.now(timezone.utc).isoformat(),
        "status": "collected" if all(result in ("collected", "not requested") for result in results.values())
        else "partial", "results": results,
    })
    return report

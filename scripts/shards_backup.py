"""Create and verify a coordinated metadata/A/B/C backup set."""

import hashlib
import json
import tempfile
import time
import uuid
from datetime import datetime, timezone

from pg_ops import BackupLock, OperationError, databases, dump, outside_workspace, query, run, write_json


FILES = {label: label + ".dump" for label in ("metadata", "shard-a", "shard-b", "shard-c")}
CONNECTION_KEYS = {"shard-a": "shardA", "shard-b": "shardB", "shard-c": "shardC"}
HASH_RULE = "sha256-utf8-first4-signed-big-endian-floor-mod-16"


def fingerprint(path):
    with path.open("rb") as data:
        return {"bytes": path.stat().st_size, "sha256": hashlib.file_digest(data, "sha256").hexdigest()}


def audit(database_by_label):
    metadata = database_by_label["metadata"]
    mapping = [
        json.loads(row)
        for row in query(metadata, """
            SELECT json_build_array(b.bucket_id, s.connection_key, b.version, b.state)::text
            FROM public.bucket_mapping AS b JOIN public.shards AS s ON b.shard_id = s.shard_id
            ORDER BY b.bucket_id
        """).splitlines()
    ]
    if [row[0] for row in mapping] != list(range(16)) or any(
        row[1] not in CONNECTION_KEYS.values() or row[3] != "NORMAL" for row in mapping
    ):
        raise OperationError("Metadata contains missing, unknown, or migrating bucket mappings")
    metadata_content = query(metadata, """
        SELECT json_build_array(
            (SELECT jsonb_agg(to_jsonb(s) ORDER BY s.shard_id) FROM public.shards AS s),
            (SELECT jsonb_agg(to_jsonb(j) ORDER BY j.job_id) FROM public.migration_jobs AS j)
        )::text
    """)
    metadata_digest = hashlib.sha256(metadata_content.strip()).hexdigest()
    counts_and_digests = {}
    for label, connection_key in CONNECTION_KEYS.items():
        with tempfile.TemporaryFile(mode="w+b") as rows:
            query(database_by_label[label], """
                SELECT json_build_array(order_id, user_id, bucket_id, amount::text, status,
                    to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US'),
                    to_char(updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US'))::text
                FROM public.orders ORDER BY order_id
            """, rows)
            rows.seek(0)
            digest = hashlib.sha256()
            count = 0
            for raw_row in rows:
                row = json.loads(raw_row)
                hash_bytes = hashlib.sha256(str(row[1]).encode("utf-8")).digest()
                bucket = int.from_bytes(hash_bytes[:4], "big", signed=True) % 16
                if row[2] != bucket or mapping[bucket][1] != connection_key:
                    raise OperationError(f"Order hash or ownership mismatch on {label}")
                canonical = json.dumps(row, separators=(",", ":"), ensure_ascii=True).encode("ascii")
                digest.update(canonical + b"\n")
                count += 1
            counts_and_digests[label] = {"orders": count, "sha256": digest.hexdigest()}
    return {"bucket_mapping": mapping, "metadata_sha256": metadata_digest, "orders": counts_and_digests}


def backup(root, values):
    root = outside_workspace(root)
    root.mkdir(parents=True, exist_ok=True)
    database_by_label = databases(values)
    identifier = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:12]
    stage = root / (".incomplete-" + identifier)
    stage.mkdir(mode=0o700)
    begin = time.monotonic()
    try:
        with BackupLock(database_by_label["metadata"]) as lock:
            started_at = datetime.now(timezone.utc).isoformat()
            data_audit = audit(database_by_label)
            server_versions = {}
            files = {}
            for label, name in FILES.items():
                lock.confirm()
                database = database_by_label[label]
                server_versions[label] = query(database, "SHOW server_version_num").decode().strip()
                dump(database, stage / name)
                files[name] = fingerprint(stage / name)
                if files[name]["bytes"] == 0:
                    raise OperationError(f"Empty dump for {label}")
            lock.confirm()
            write_json(stage / "manifest.json", {
                "format": 1, "status": "complete", "id": identifier,
                "locked_at": started_at, "completed_at": datetime.now(timezone.utc).isoformat(),
                "seconds": round(time.monotonic() - begin, 3),
                "pg_dump": run(["pg_dump", "--version"]).decode().strip(),
                "server_versions": server_versions, "hash_rule": HASH_RULE,
                "files": files, "audit": data_audit,
            })
            lock.confirm()
            stage.rename(root / identifier)
    except (OperationError, OSError) as error:
        if stage.is_dir():
            detail = str(error) if isinstance(error, OperationError) else type(error).__name__
            write_json(stage / "FAILED.json", {"status": "failed", "reason": detail})
        raise
    return root / identifier


def check_set(directory):
    directory = outside_workspace(directory)
    if directory.name.startswith(".incomplete-"):
        raise OperationError("An incomplete backup cannot be restored")
    manifest_path = directory / "manifest.json"
    if manifest_path.is_symlink():
        raise OperationError("A backup manifest cannot be a symbolic link")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if (
        not isinstance(manifest, dict) or manifest.get("format") != 1
        or manifest.get("status") != "complete" or manifest.get("id") != directory.name
        or manifest.get("hash_rule") != HASH_RULE or not isinstance(manifest.get("files"), dict)
        or set(manifest["files"]) != set(FILES.values()) or not isinstance(manifest.get("audit"), dict)
    ):
        raise OperationError("Unrecognized or incomplete backup manifest")
    for name in FILES.values():
        path = directory / name
        if path.is_symlink() or not path.is_file() or fingerprint(path) != manifest["files"][name]:
            raise OperationError(f"Backup checksum or file size mismatch: {name}")
    return directory, manifest


def restore(directory, values):
    directory, manifest = check_set(directory)
    database_by_label = databases(values, restore=True)
    prefix = "restore_" + uuid.uuid4().hex[:12]
    targets = {}
    for label, database in database_by_label.items():
        new_name = prefix + "_" + label.replace("-", "_")
        if query(database.named("postgres"), f"SELECT 1 FROM pg_catalog.pg_database WHERE datname = '{new_name}'").strip():
            raise OperationError("A restore database already exists")
        targets[label] = database.named(new_name)

    print(f"Restore database prefix: {prefix}", flush=True)
    started = time.monotonic()
    for label, target in targets.items():
        run(["createdb", *target.endpoint(), "--maintenance-db=postgres", target.name], target)
        run(["pg_restore", *target.endpoint(), "--exit-on-error", "--single-transaction", "--no-owner",
             "--no-privileges", f"--dbname={target.name}", str(directory / FILES[label])], target)
    restored_audit = audit(targets)
    if restored_audit != manifest["audit"]:
        raise OperationError("Restored bucket mappings, order counts, or order contents differ from the manifest")
    report = directory.parent / f"verified-{prefix}.json"
    write_json(report, {
        "status": "restore-verified", "backup_set": directory.name,
        "verified_at": datetime.now(timezone.utc).isoformat(), "seconds": round(time.monotonic() - started, 3),
        "databases": {label: db.name for label, db in targets.items()}, "audit": restored_audit,
    })
    return report

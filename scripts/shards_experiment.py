"""Measure read latency and independently verify the physical location of an order."""

import argparse
import json
import math
import random
import sys
import time
import uuid
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from http.client import HTTPException
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import urlopen

from pg_ops import OperationError, databases, outside_workspace, query, read_values
from shards_backup import CONNECTION_KEYS


def positive_user_id(value):
    user_id = int(value)
    if not 1 <= user_id <= 2**63 - 1:
        raise argparse.ArgumentTypeError("user IDs must be positive Java long values")
    return user_id


def get_status(origin, user_id):
    before = time.perf_counter()
    try:
        with urlopen(f"{origin}/orders?userId={user_id}", timeout=10) as response:
            code = str(response.status)
            while response.read(65536):
                pass
    except HTTPError as error:
        code = str(error.code)
        error.close()
    except (HTTPException, URLError, OSError):
        code = "transport-error"
    return code, (time.perf_counter() - before) * 1000


def percentile(sorted_values, proportion):
    if not sorted_values:
        return None
    return round(sorted_values[math.ceil(len(sorted_values) * proportion) - 1], 2)


def request_stats(measurements):
    codes = Counter(status for status, _ in measurements)
    all_ms = sorted(duration for _, duration in measurements)
    success_ms = sorted(duration for status, duration in measurements if status == "200")
    return {
        "requests": len(measurements), "statuses": dict(codes),
        "error_rate": round(1 - len(success_ms) / len(measurements), 5),
        "latency_ms": {"p50": percentile(all_ms, .50), "p95": percentile(all_ms, .95)},
        "successful_latency_ms": {"p50": percentile(success_ms, .50), "p95": percentile(success_ms, .95)},
    }


def measure(args):
    parsed = urlsplit(args.url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.path not in ("", "/") or (
        parsed.query or parsed.fragment or parsed.username or parsed.password
    ):
        raise OperationError("Use an HTTP origin such as http://127.0.0.1:8080")
    if not 1 <= args.samples <= 10_000 or not 1 <= args.workers <= min(args.samples, 32):
        raise OperationError("Use 1–10000 samples and 1–32 workers, no more than the number of samples")
    choices = random.Random(args.seed).choices(args.users, k=args.samples)
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        measurements = list(executor.map(lambda user_id: get_status(args.url.rstrip("/"), user_id), choices))
    elapsed = time.perf_counter() - started
    # executor.map은 입력 순서를 유지하므로 이동 사용자와 대조 사용자의 결과를 나눌 수 있습니다.
    by_user = defaultdict(list)
    for user_id, result in zip(choices, measurements, strict=True):
        by_user[user_id].append(result)
    return {
        "label": args.label, "measured_at": datetime.now(timezone.utc).isoformat(),
        "seed": args.seed, "users": args.users, "workers": args.workers,
        "seconds": round(elapsed, 3), "requests_per_second": round(args.samples / elapsed, 2),
        **request_stats(measurements),
        "by_user": {str(user): request_stats(results) for user, results in sorted(by_user.items())},
    }


def verify_order(args):
    labels = databases(read_values(args.env_file))
    order_id = str(args.order_id)
    owner = query(labels["metadata"], f"""
        SELECT s.connection_key FROM public.bucket_mapping AS b
        JOIN public.shards AS s ON b.shard_id = s.shard_id WHERE bucket_id = {args.bucket}
    """).decode().strip()
    locations = []
    for label, key in CONNECTION_KEYS.items():
        result = query(labels[label], f"""
            SELECT json_build_array(user_id, bucket_id, amount, status)::text FROM public.orders
            WHERE order_id = '{order_id}'
        """).decode().strip()
        if result:
            locations.append({"shard": key, "fields": json.loads(result)})
    if owner != args.owner or len(locations) != 1 or locations[0]["shard"] != owner or (
        locations[0]["fields"][:2] != [args.user, args.bucket]
    ):
        raise OperationError("Expected order and bucket mapping to point to the same single shard")
    return {"status": "verified", "order_id": order_id, "bucket": args.bucket, "owner": owner, "rows": locations}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    cli = parser.add_subparsers(dest="command", required=True)
    load = cli.add_parser("measure")
    load.add_argument("--url", default="http://127.0.0.1:8080")
    load.add_argument("--label", required=True)
    load.add_argument("--users", type=positive_user_id, nargs="+", required=True)
    load.add_argument("--samples", type=int, default=100)
    load.add_argument("--workers", type=int, default=2)
    load.add_argument("--seed", type=int, default=42)
    load.add_argument("--output", type=Path, required=True)
    verify = cli.add_parser("verify-order")
    verify.add_argument("--env-file", type=Path, default=Path(".env"))
    verify.add_argument("--order-id", type=uuid.UUID, required=True)
    verify.add_argument("--user", type=positive_user_id, required=True)
    verify.add_argument("--bucket", type=int, choices=range(16), required=True)
    verify.add_argument("--owner", choices=CONNECTION_KEYS.values(), required=True)
    args = parser.parse_args()
    try:
        if args.command == "verify-order":
            print(json.dumps(verify_order(args), indent=2))
        else:
            output = outside_workspace(args.output)
            result = measure(args)
            output.parent.mkdir(parents=True, exist_ok=True)
            with output.open("x", encoding="utf-8") as report:
                json.dump(result, report, indent=2)
                report.write("\n")
            print(output)
        return 0
    except OperationError as error:
        print(f"Experiment failed: {error}", file=sys.stderr)
    except (OSError, ValueError) as error:
        print(f"Experiment failed ({type(error).__name__})", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())

"""Command line tools for capstone database operations."""

import argparse
import sys
from pathlib import Path

from pg_ops import OperationError, read_values
from shards_backup import backup, restore
from shards_incident import capture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=Path(".env"))
    subparsers = parser.add_subparsers(dest="command", required=True)
    back = subparsers.add_parser("backup", help="create a verified four-database archive set")
    back.add_argument("--root", type=Path, required=True)
    recovery = subparsers.add_parser("restore", help="restore a trusted backup set into fresh databases")
    recovery.add_argument("--set", type=Path, required=True)
    incident = subparsers.add_parser("incident", help="collect diagnostic information and readable dumps")
    incident.add_argument("--root", type=Path, required=True)
    incident.add_argument("--app-log", type=Path)
    incident.add_argument("--logs-only", action="store_true")
    incident.add_argument("--remote-shard-c", action="store_true", help="skip local Docker logs for a tunneled shard C")
    args = parser.parse_args()
    try:
        values = read_values(args.env_file)
        if args.command == "backup":
            report = backup(args.root, values)
        elif args.command == "restore":
            report = restore(args.set, values)
        else:
            report = capture(args.root, values, args.app_log, args.logs_only, args.remote_shard_c)
        print(report)
        return 0
    except OperationError as error:
        print(f"Operation failed: {error}", file=sys.stderr)
    except (OSError, ValueError) as error:
        print(f"Operation failed ({type(error).__name__})", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())

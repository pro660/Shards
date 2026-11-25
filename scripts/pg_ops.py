"""PostgreSQL 18 client helpers for local and remote lab databases."""

import json
import os
import queue
import re
import subprocess
import threading
from dataclasses import dataclass, replace
from pathlib import Path


class OperationError(Exception):
    pass


DB_NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,62}")
LOCK_KEY = "715000, 0"


@dataclass(frozen=True)
class Database:
    host: str
    port: int
    name: str
    user: str
    password: str
    sslmode: str | None

    def named(self, name):
        return replace(self, name=name)

    def environment(self):
        environment = os.environ.copy()
        # Never include credentials in a process command line.
        environment["PGPASSWORD"] = self.password
        environment["PGCONNECT_TIMEOUT"] = "5"
        environment["PGCLIENTENCODING"] = "UTF8"
        environment["PGTZ"] = "UTC"
        environment["PGDATESTYLE"] = "ISO"
        if self.sslmode:
            environment["PGSSLMODE"] = self.sslmode
        return environment

    def endpoint(self):
        return [f"--host={self.host}", f"--port={self.port}", f"--username={self.user}", "--no-password"]

    def psql(self):
        return ["psql", *self.endpoint(), f"--dbname={self.name}", "-X", "-qAt", "-v", "ON_ERROR_STOP=1"]


def read_values(path):
    values = {}
    if path.is_file():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            key, separator, value = stripped.removeprefix("export ").partition("=")
            if separator:
                value = value.strip()
                if value.startswith(("'", '"')) and value.endswith(value[0]) and len(value) >= 2:
                    value = value[1:-1]
                elif not value.startswith(("'", '"')):
                    value = value.split(" #", 1)[0].rstrip()
                values[key.strip()] = value
    return {**values, **os.environ}


def databases(values, restore=False):
    profiles = {
        "metadata": ("META_DB", 15432), "shard-a": ("SHARD_A_DB", 15433),
        "shard-b": ("SHARD_B_DB", 15434), "shard-c": ("SHARD_C_DB", 15435),
    }
    result = {}
    for label, (prefix, port) in profiles.items():
        def setting(suffix, default=None):
            key = f"{prefix}_{suffix}"
            if restore and f"RESTORE_{key}" in values:
                return values[f"RESTORE_{key}"]
            return values.get(key, default)

        name = setting("NAME")
        user = setting("USER")
        password = setting("PASSWORD")
        if not name or not DB_NAME.fullmatch(name) or not user or not password:
            raise OperationError(f"Check {prefix}_NAME, {prefix}_USER and {prefix}_PASSWORD")
        try:
            configured_port = int(setting("PORT", str(port)))
        except ValueError:
            raise OperationError(f"Invalid port for {label}") from None
        if configured_port not in range(1, 65536):
            raise OperationError(f"Invalid port for {label}")
        result[label] = Database(
            setting("HOST", "127.0.0.1"), configured_port, name, user, password, setting("SSLMODE")
        )
    return result


def run(command, database=None, timeout=300, output=None):
    try:
        return subprocess.run(
            command, env=database.environment() if database else None,
            stdin=subprocess.DEVNULL, stdout=output if output is not None else subprocess.PIPE,
            stderr=subprocess.DEVNULL, timeout=timeout, check=True,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        raise OperationError(f"{command[0]} failed or timed out") from None


def query(database, sql, output=None):
    return run([*database.psql(), "-c", sql], database, output=output)


def dump(database, path):
    run(["pg_dump", *database.endpoint(), "-Fc", f"--dbname={database.name}", f"--file={path}"], database)


def write_json(path, document):
    path.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def outside_workspace(path):
    resolved = path.expanduser().resolve()
    workspace = Path(__file__).resolve().parent.parent
    if resolved.is_relative_to(workspace):
        raise OperationError("Choose a location outside the project checkout")
    return resolved


class BackupLock:
    """Hold a session-level exclusive lock on the metadata DB during a backup."""

    def __init__(self, database):
        self.database = database
        self.process = None
        self.lines = queue.Queue()

    def __enter__(self):
        try:
            self.process = subprocess.Popen(
                self.database.psql(), env=self.database.environment(),
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                text=True, encoding="utf-8",
            )
            threading.Thread(target=self._read_lines, daemon=True).start()
            if self.ask(f"SELECT pg_try_advisory_lock({LOCK_KEY})") != "t":
                raise OperationError("Order writes or another operation are still running")
            if self.ask("SELECT EXISTS (SELECT 1 FROM public.migration_jobs WHERE finished_at IS NULL)") != "f":
                raise OperationError("Finish the unfinished migration before starting a backup")
        except (OSError, OperationError):
            self.close()
            raise
        return self

    def _read_lines(self):
        try:
            for line in self.process.stdout:
                self.lines.put(line.strip())
        finally:
            self.lines.put(None)

    def ask(self, sql):
        try:
            self.process.stdin.write(sql + ";\n")
            self.process.stdin.flush()
            answer = self.lines.get(timeout=15)
        except (OSError, queue.Empty):
            raise OperationError("The metadata backup lock session was lost") from None
        if answer is None:
            raise OperationError("The metadata backup lock session was lost")
        return answer

    def confirm(self):
        if self.ask("SELECT 1") != "1":
            raise OperationError("The metadata backup lock session was lost")

    def close(self):
        if self.process is not None:
            if self.process.poll() is None:
                self.process.terminate()
            self.process.wait(timeout=5)
            self.process.stdin.close()
            self.process.stdout.close()

    def __exit__(self, *_):
        self.close()

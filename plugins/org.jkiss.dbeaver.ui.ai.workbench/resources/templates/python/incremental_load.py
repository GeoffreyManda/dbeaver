#!/usr/bin/env python3
"""Idempotently load id,value,updated_at CSV into a local SQLite database.

Usage: python3 incremental_load.py input.csv output.sqlite
Python 3.11+; standard library only. This writes only the specified SQLite file.
Newer timestamps replace values; older events are counted and skipped. Equal-time
conflicts and duplicate keys abort the entire batch. Deletions are not inferred.
"""
import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sqlite3

BATCH_LIMIT = 100000
EXPECTED_COLUMNS = ["id", "value", "updated_at"]


def timestamp(value):
    parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    if parsed.utcoffset() is None:
        raise ValueError("updated_at requires a timezone")
    return parsed.astimezone(timezone.utc).isoformat(timespec="microseconds")


def file_digest(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def load(source, database):
    source, database = Path(source).resolve(), Path(database).resolve()
    if source == database:
        raise ValueError("Input CSV and database must be different files")
    before = source.stat()
    digest = file_digest(source)
    counts = {"rows": 0, "inserted": 0, "updated": 0, "unchanged": 0, "stale": 0}
    connection = sqlite3.connect(database, isolation_level=None)
    try:
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("BEGIN IMMEDIATE")
        connection.execute("""CREATE TABLE IF NOT EXISTS tutorial_records (
            id TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL,
            updated_at TEXT NOT NULL, source_sha256 TEXT NOT NULL)""")
        connection.execute("""CREATE TABLE IF NOT EXISTS tutorial_load_batches (
            source_sha256 TEXT PRIMARY KEY, loaded_at TEXT NOT NULL,
            source_rows INTEGER NOT NULL, inserted INTEGER NOT NULL,
            updated INTEGER NOT NULL, unchanged INTEGER NOT NULL, stale INTEGER NOT NULL)""")
        seen = set()
        with source.open(encoding="utf-8-sig", newline="") as stream:
            reader = csv.DictReader(stream, strict=True)
            if reader.fieldnames != EXPECTED_COLUMNS:
                raise ValueError("Expected exactly id,value,updated_at columns in that order")
            for row in reader:
                counts["rows"] += 1
                if counts["rows"] > BATCH_LIMIT:
                    raise ValueError("Split input into batches of at most 100000 rows")
                if None in row or any(value is None for value in row.values()):
                    raise ValueError("Wrong CSV field count")
                key = row["id"].strip()
                if not key or key in seen:
                    raise ValueError("Blank or duplicate id in input batch")
                seen.add(key)
                date = timestamp(row["updated_at"])
                existing = connection.execute(
                    "SELECT value, updated_at FROM tutorial_records WHERE id = ?", (key,)).fetchone()
                if existing is None:
                    connection.execute("INSERT INTO tutorial_records VALUES (?, ?, ?, ?)",
                                       (key, row["value"], date, digest))
                    counts["inserted"] += 1
                elif date < existing[1]:
                    counts["stale"] += 1
                elif date == existing[1]:
                    if row["value"] != existing[0]:
                        raise ValueError("Conflicting values at the same id and timestamp")
                    counts["unchanged"] += 1
                else:
                    connection.execute("UPDATE tutorial_records SET value=?, updated_at=?, source_sha256=? WHERE id=?",
                                       (row["value"], date, digest, key))
                    counts["updated"] += 1
        after = source.stat()
        if (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
            raise ValueError("Source changed during load")
        connection.execute("""INSERT INTO tutorial_load_batches VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(source_sha256) DO NOTHING""", (
            digest, datetime.now(timezone.utc).isoformat(), counts["rows"], counts["inserted"],
            counts["updated"], counts["unchanged"], counts["stale"]))
        connection.execute("COMMIT")
        return {"source_sha256": digest, **counts}
    except BaseException:
        if connection.in_transaction:
            connection.execute("ROLLBACK")
        raise
    finally:
        connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("database", type=Path)
    args = parser.parse_args()
    print(json.dumps(load(args.input, args.database), indent=2))


if __name__ == "__main__":
    main()

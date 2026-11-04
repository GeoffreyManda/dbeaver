#!/usr/bin/env python3
"""Profile CSV structure and completeness without printing source values.

Usage: python3 profile_csv.py input.csv [--delimiter ,]
Python 3.11+; standard library only. The input is never modified.
"""
import argparse
import csv
import json
import math
from pathlib import Path

DISTINCT_LIMIT = 10000
COLUMN_LIMIT = 256


def profile(path, delimiter=","):
    if len(delimiter) != 1:
        raise ValueError("Delimiter must be one character")
    with Path(path).open(encoding="utf-8-sig", newline="") as source:
        reader = csv.reader(source, delimiter=delimiter, strict=True)
        header = next(reader, None)
        if not header or len(header) > COLUMN_LIMIT or any(not h.strip() for h in header):
            raise ValueError("Expected 1..256 named columns")
        if len(set(header)) != len(header):
            raise ValueError("Duplicate column names")
        stats = [{"column": name, "missing": 0, "numeric": 0,
                  "distinct": set(), "distinct_complete": True} for name in header]
        rows = 0
        for line, values in enumerate(reader, 2):
            if len(values) != len(header):
                raise ValueError(f"Wrong field count at CSV record {line}")
            rows += 1
            for stat, value in zip(stats, values):
                value = value.strip()
                if not value:
                    stat["missing"] += 1
                    continue
                try:
                    if math.isfinite(float(value)):
                        stat["numeric"] += 1
                except ValueError:
                    pass
                if stat["distinct_complete"]:
                    stat["distinct"].add(value)
                    if len(stat["distinct"]) > DISTINCT_LIMIT:
                        stat["distinct"].clear()
                        stat["distinct_complete"] = False
        for stat in stats:
            complete = stat["distinct_complete"]
            stat["distinct_count"] = len(stat.pop("distinct")) if complete else None
            stat["nonmissing"] = rows - stat["missing"]
        return {"rows": rows, "columns": stats,
                "distinct_limit": DISTINCT_LIMIT, "values_included": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--delimiter", default=",")
    args = parser.parse_args()
    print(json.dumps(profile(args.input, args.delimiter), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

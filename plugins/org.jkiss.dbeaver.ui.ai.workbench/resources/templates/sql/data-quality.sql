-- SQLite template for the tables produced by incremental_load.py.
-- These statements are read-only. Run each query; investigate nonzero violations.

-- 1. Population and completeness. An empty table must be assessed separately.
SELECT COUNT(*) AS records,
       SUM(CASE WHEN id IS NULL OR trim(id) = '' THEN 1 ELSE 0 END) AS missing_ids,
       SUM(CASE WHEN value IS NULL OR trim(value) = '' THEN 1 ELSE 0 END) AS blank_values,
       SUM(CASE WHEN julianday(updated_at) IS NULL THEN 1 ELSE 0 END) AS invalid_dates,
       MIN(updated_at) AS first_event_at,
       MAX(updated_at) AS last_event_at
FROM tutorial_records;

-- 2. Key integrity. Expected result: no rows.
SELECT id, COUNT(*) AS occurrences
FROM tutorial_records
GROUP BY id
HAVING COUNT(*) > 1;

-- 3. Provenance completeness. Expected result: zero missing batches.
SELECT COUNT(*) AS records_without_load_batch
FROM tutorial_records AS record
LEFT JOIN tutorial_load_batches AS batch
  ON record.source_sha256 = batch.source_sha256
WHERE batch.source_sha256 IS NULL;

-- 4. Per-batch accounting. Expected result: no rows.
SELECT source_sha256, source_rows, inserted, updated, unchanged, stale
FROM tutorial_load_batches
WHERE source_rows <> inserted + updated + unchanged + stale;

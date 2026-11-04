-- Databricks SQL / Delta Lake sample. Creates and writes a tutorial table.
-- Select a disposable catalog/schema in DBeaver before running any statement.
-- Run numbered stages separately; inspect each result before continuing.
-- No DELETE, DROP, OVERWRITE, credential or production object is included.
-- Reference: https://docs.databricks.com/aws/en/delta/merge

-- 1. Confirm the selected namespace and actor before creating anything.
SELECT current_catalog() AS selected_catalog,
       current_schema() AS selected_schema,
       current_user() AS actor;

-- 2. Fix timestamp interpretation for this session, then create the target.
-- Each statement commits independently.
SET TIME ZONE 'UTC';
CREATE TABLE IF NOT EXISTS workbench_tutorial_records (
    id STRING NOT NULL,
    value STRING NOT NULL,
    updated_at TIMESTAMP NOT NULL
) USING DELTA;

-- 3. Merge a deterministic, uniquely keyed sample batch.
-- When replacing this source, deduplicate by id using a deterministic ordering;
-- reject equal-timestamp conflicting values before invoking MERGE.
-- Newer same-ID values update; repeated or older events do not replace the target.
MERGE INTO workbench_tutorial_records AS target
USING (
    SELECT * FROM VALUES
      ('example-001', 'alpha', TIMESTAMP '2026-01-01 00:00:00'),
      ('example-002', 'beta',  TIMESTAMP '2026-01-02 00:00:00')
    AS batch(id, value, updated_at)
) AS source
ON target.id = source.id
WHEN MATCHED AND source.updated_at > target.updated_at THEN
    UPDATE SET value = source.value, updated_at = source.updated_at
WHEN NOT MATCHED THEN
    INSERT (id, value, updated_at)
    VALUES (source.id, source.value, source.updated_at);

-- 4. Reconcile keys and state. The sample yields two rows on a new target.
SELECT COUNT(*) AS records, COUNT(DISTINCT id) AS distinct_ids,
       MIN(updated_at) AS first_event_at, MAX(updated_at) AS last_event_at
FROM workbench_tutorial_records;

-- 5. Inspect Delta transaction history before any rollback decision.
DESCRIBE HISTORY workbench_tutorial_records LIMIT 10;

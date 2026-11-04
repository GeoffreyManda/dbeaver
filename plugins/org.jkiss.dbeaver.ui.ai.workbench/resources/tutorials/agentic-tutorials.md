# Data engineering tutorials

These exercises build a small, traceable pipeline from a CSV file to a database, then show how to apply the same update rules to a Databricks Delta table. The Python examples use the standard library, and the R example uses base R. They can be opened from the addon’s bundled resources or read in this repository; they do not require package installation.

## Choose the workflow

| Exercise | Template | Result |
|---|---|---|
| Profile a source | `python/profile_csv.py` | Row counts, missingness, numeric counts and bounded distinct counts |
| Check a source in R | `r/profile_csv.R` | A comparable per-column summary written to standard output |
| Load incrementally | `python/incremental_load.py` | SQLite records plus a source-hash batch ledger |
| Validate a load | `sql/data-quality.sql` | Completeness, key and lineage checks |
| Merge on Databricks | `sql/databricks-delta.sql` | A repeatable sample Delta upsert and transaction history |

Template paths in this guide are relative to `plugins/org.jkiss.dbeaver.ui.ai.workbench/resources/templates/`. Run scripts with an installed Python 3.11+ or R runtime. DBeaver remains the connection, SQL and result inspection surface; the templates do not imply that an embedded Python or R interpreter has been installed.

## 1. Connect and establish the namespace

Create a local SQLite connection for the output of the incremental-load exercise. Select the database file after the script has created it. Open a SQL editor for that connection and check the connection selector before executing a statement. Keep a separate editor for each database so a local sample cannot be mistaken for a remote target.

For Databricks, use the installed Databricks driver and the workspace’s warehouse or cluster connection details. Enter authentication through the native connection dialog, not in SQL files or AI prompts. Confirm the available authentication methods in the installed edition. DBeaver documents its [Databricks connection settings](https://dbeaver.com/docs/dbeaver/Database-driver-Databricks/), and Databricks provides a [DBeaver connection guide](https://docs.databricks.com/gcp/en/dev-tools/dbeaver).

Run a namespace query before any write. In the Databricks template, the first statement returns the selected catalog, schema and actor. Choose a disposable schema for the tutorial. The template does not create a catalog, grant permissions or infer a production destination.

## 2. Profile before transforming

Save a small UTF-8 source as `input.csv`:

```csv
id,value,updated_at
example-001,alpha,2026-01-01T00:00:00Z
example-002,beta,2026-01-02T00:00:00Z
```

Run the Python profile from the template directory:

```sh
python3 python/profile_csv.py input.csv
```

The result contains counts rather than source values. Check the row total, column names, missingness and expected data types. A numeric count means that values parsed as finite numbers; it does not establish that a column should be treated as a measure. Distinct counts are exact only while `distinct_complete` is true. Counts above the 10,000-value limit are left unknown rather than reported as exact. Python profiling streams records and rejects duplicate headers or inconsistent field counts.

Use a different delimiter with `--delimiter ';'`. The parser supports quoted fields and a UTF-8 byte-order mark. It does not guess date formats or silently coerce identifiers. For Python’s CSV parser behavior, see the [CSV module documentation](https://docs.python.org/3/library/csv.html).

## 3. Compare the source with base R

```sh
Rscript r/profile_csv.R input.csv
```

R writes a CSV summary to standard output. Compare the row total and missing counts with Python’s summary. The R exercise reads at most 100,001 rows and rejects an input above its 100,000-row limit; split larger inputs before using it. It preserves columns as character data during import, which prevents an identifier with leading zeros from being converted to a number. Its distinct-count limit is also 10,000.

Neither profiler prints sample values. A missing or nonnumeric value is a diagnostic to investigate, not an automatic instruction to delete a row. Base R’s [data import manual](https://www.stat.ethz.ch/R-manual/R-devel/doc/manual/R-data.html) explains text encoding and import choices.

## 4. Load incrementally and prove replay behavior

The loader requires exactly `id,value,updated_at` in that order. Timestamps must include a timezone. It normalizes timestamps to UTC before comparing events.

```sh
python3 python/incremental_load.py input.csv tutorial.sqlite
```

Run the same command a second time. On a new database, the first run reports two insertions; the second reports two unchanged rows. It should not create duplicate records or duplicate the source-hash ledger entry. The loader writes only the SQLite file supplied on the command line.

Change `example-001` to a new value and a later timestamp, then load the changed file. The existing key is updated. An older event is counted as stale and leaves the current record intact. Equal timestamps with different values are rejected because the loader has no evidence for choosing a winner. Duplicate IDs within a batch are also rejected. Either error rolls back that entire batch, including earlier insertions in it.

Each accepted source has a SHA-256 entry in `tutorial_load_batches`. Current records retain the hash of the batch that supplied their current value. This identifies the supplying file’s bytes; it does not preserve the original file, identify its author or provide a full record-level event history. Keep the original source once in the project’s existing source home. The loader does not infer deletions from absent keys, implement change-data-capture offsets or claim exactly-once behavior across remote systems.

The SQLite transaction starts explicitly before schema and data changes, and values are passed through bound parameters. The [Python SQLite documentation](https://docs.python.org/3/library/sqlite3.html) explains parameter binding and explicit transaction control.

## 5. Validate in DBeaver

Connect to `tutorial.sqlite` and open `sql/data-quality.sql` in that connection’s SQL editor. Its statements are read-only. Assess the total population first, then investigate missing IDs, blank values or invalid timestamps. A nonzero blank-value count may be permitted by a particular contract; document the decision rather than changing a threshold to obtain a pass.

The duplicate-key query should return no rows. The lineage query should return zero records without a load batch. The batch-accounting query should return no rows because each source row must be counted as inserted, updated, unchanged or stale. An empty table still needs a separate coverage decision; zero violations on zero records does not establish a successful load.

When reviewing a result with AI Chat, request a fresh workbench observation before a display action. A fetched row count describes the loaded result buffer. A spatial presentation must be available and rendered before it is treated as a map. These templates contain no geometry or coordinate transformation.

## 6. Apply the update contract to Delta Lake

Open `sql/databricks-delta.sql` against the selected disposable Databricks schema. Run its numbered stages separately and inspect each result before proceeding. It creates `workbench_tutorial_records`, then merges a deterministic two-row batch. A rerun does not duplicate the keys; a newer event for an existing key updates its value. The script includes no delete, overwrite or drop operation.

Before replacing its inline batch with a real source, establish one source row per key and reject equal-timestamp conflicts. The sample’s two keys are already unique; it is not a general deduplication pipeline. The target must also retain one row per key. A Delta `MERGE` is transactional, but the tutorial’s create, merge and inspection statements are separate operations. Do not add a portable `BEGIN` wrapper and assume it supplies a cross-statement transaction.

After the merge, reconcile record and distinct-key counts, then inspect `DESCRIBE HISTORY`. The [Databricks merge documentation](https://docs.databricks.com/aws/en/delta/merge) describes upserts and duplicate-match requirements. The [Delta tutorial](https://docs.databricks.com/aws/en/delta/tutorial) covers table creation and history. The SQL template has not been executed against a remote Databricks account as part of this package.

## Release a pipeline others can reproduce

Retain the source once, the transformation code in Git, the selected runtime versions and the accepted quality results in the project’s existing release record. Record the engine, catalog and schema without recording credentials. Re-run the smallest relevant checks after changing a source contract or transformation. Review a generated query in the correct engine before executing it.

For a production workflow, add the domain’s required schema evolution, access control, scheduling, monitoring and recovery policy around these examples. A reviewable next change has a named behavior, a small patch and a check capable of exposing the previous error. Self-improvement should produce that change for review; it should not silently rewrite the running agent or its permissions.

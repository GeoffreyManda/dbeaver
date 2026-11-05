# Agentic Community workbench

The extension adds app-aware assistance to DBeaver Community while retaining Eclipse RCP, OSGi, JDBC, existing drivers, editors and credential storage. It is developed as an Apache-licensed plugin in the public fork.

## Application model

Native APIs supply a directed graph of observed workbench parts, result ownership and available presentations. The agent observes before acting and receives a fresh graph afterward. Observation tokens expire and are invalidated when fetched results change. Opaque or unobserved state remains unknown. Metadata does not establish that a chart or map rendered successfully.

## Capability model

The installed command and registered driver catalogs are searchable with Unicode normalization and explicit applicability and risk. Catalog entries are not automatically executable AI tools. Native commands acquire execution adapters only after their effects, permissions, undo behavior and tests are defined. The implementation must not claim hundreds of tools by counting command descriptions or aliases.

Tool families to implement include schema discovery, connection diagnostics, dialect-aware SQL, transactions, profiling, constraints, lineage, incremental pipelines, Delta Lake, spatial work, imports and exports, and reproducible Python and R analysis. Existing schema-discovery tools remain authoritative. New tools preserve the normal confirmation and connection permission checks.

## Connections and Databricks

The connection page opens DBeaver’s existing wizard and navigator. Driver selection, authentication and credential persistence stay in their existing components. Databricks catalog discovery and metadata refresh use the current driver. Data synchronization is a separate explicit workflow: source validation, staging policy, key and watermark definition, dry-run comparison, authorized Delta MERGE, and read-back reconciliation. A metadata refresh must never be labelled as a completed data sync.

## Python and R

Embedded templates provide profiling, data-quality checks and idempotent incremental loading. Execution adapters must identify the selected interpreter and environment, show the exact script and input scope, preserve exit status and bound output. They must not silently install packages or treat generated code as reviewed code. Credentials remain outside scripts and published examples.

## Language and online help

The connection companion exposes the existing AI response-language preference. Unicode capability matching supports non-English queries. Interface translations and tutorial translations require independent review; a language preference alone is not evidence of translated documentation.

Headless documentation lookup searches a curated official catalog and requests fixed official URLs. It sends no query string or local database content. Responses are bounded, attributed and treated as source material rather than instructions. A general online-search provider remains a separate integration.

## Improvement and presentation

Self-improvement follows a reviewable patch workflow: observed failure, reproducible case, proposed code change, tests and review, followed by an explicit release. The source tools use a native-selected addon checkout, bounded source reads, SHA-256 guarded exact replacements and in-memory rollback. Compilation uses a fixed local compiler with annotation processing disabled and temporary output removed. They cannot run model-supplied commands or silently install a patch. Installation follows source review; live verification remains separate.

The appearance controller applies a coordinated native palette and updates chat through a validated nine-color stylesheet API. It preserves the base light/dark theme, syntax formatting and fonts, restores original colors on reset and follows newly opened controls. No model-supplied CSS or JavaScript is executed. The companion uses SWT platform fonts and controls, with consistent spacing and keyboard access. Apple platform behavior is a design target on macOS; replacing DBeaver’s established core UI is outside this plugin’s approach.

## Current implementation and acceptance

The extension includes bounded scene observation, native navigation, guarded SQL editing and read execution, geometry presentation, official-documentation lookup, source edits with rollback, shared design controls and optional image observation. The companion provides connection navigation, response-language settings and engineering templates. Compilation and fixture tests remain distinct from rendered acceptance: a submitted query is not a fetched result, a material attachment is not visible transparency, and a selected Spatial presentation is not a verified map. Authenticated Databricks synchronization requires a configured connection and separate verification.

# AI workbench tools

AI Chat needs current application state to distinguish an open editor from a fetched result or a rendered map. This Community extension adds native observation and display tools through the public AI function extension points.

The directed scene graph describes open editors and views, the selected editor’s fetched result and available presentations. Native API observations supply each node and edge. Unknown or truncated state is explicit. SQL text, editor titles, paths, connection URLs, credentials and row values are excluded.

Display actions require a recent observation token and an identifier returned by that observation. They activate existing parts or select available presentations. They do not execute SQL, fetch rows, modify data or convert geometry. DBeaver retains tool confirmation.

## Acceptance

The graph has fixed limits and rejects duplicate nodes and dangling edges. Actions reject expired observations, closed parts and changed editor or result state. Workbench access runs on the SWT display thread. Spatial is selectable only when DBeaver already offers it. Geometry conversion remains separate because its existing flow can execute the query again.

Tests cover graph bounds, identity, edges and immutability. Compilation checks the installed Community APIs. Runtime acceptance requires AI Chat to observe, activate an observed part and switch an available presentation; compilation alone does not establish runtime acceptance.

## Implementation

The independent OSGi bundle `org.jkiss.dbeaver.ui.ai.workbench` uses public AI function, workbench and presentation APIs. It requires no external MCP server or paid plugin, keeps one bounded observation in memory and persists no observations.

This AI-assisted extension is distributed under Apache 2.0. Project-specific datasets and account configuration are excluded.

## Build and use

The standalone builder compiles the addon and the small chat theme/continuation API changes against the plugin jars in an existing DBeaver installation. It runs the model tests and creates one small JAR under the addon’s `target` directory. It does not copy the DBeaver installation or download a build platform.

```sh
python3 scripts/build-workbench-tools.py --app /path/to/DBeaver.app
```

A Java 21+ JDK is required. An existing Eclipse ECJ jar can be supplied with `--compiler /path/to/ecj.jar` when the app includes only a JRE. The optional `--online-test` fetches the official Python CSV documentation; normal tests make no network requests.

Close DBeaver after saving editors, run `python3 scripts/install-workbench-tools.py --app /path/to/DBeaver.app`, then restart. The addon is installed through an Eclipse dropins directory. Some products do not auto-start the built-in dropins reconciler; check the [Equinox dropins instructions](https://wiki.eclipse.org/Equinox/p2/Getting_Started#Debugging_dropins) for that installation. The theme and action-continuation APIs update two small core bundles. The bounded installer preserves their original bytes once with SHA-256 receipts, refuses a running app and rejects signed bundles. It does not replace the workbench architecture or database engines.

Open **Window → Show View → Other → AI Workbench** for the connection and engineering companion. AI Chat can request `observeWorkbench`, `activateWorkbenchPart`, `selectResultPresentation`, `searchWorkbenchCapabilities` and `searchOfficialDocumentation`. The model/provider must support function calling. Tool confirmation defaults to Ask.

With the addon active, Enter submits from the AI prompt and Shift+Enter inserts a new line. Enter during an active IME composition is left to the input method. SQL editors and other text controls keep their original key behavior. The existing Cmd/Ctrl+Enter shortcut remains available.

The companion exposes the AI response-language preference and translated navigation labels in Arabic, French, Spanish and Chinese. Detailed guidance currently falls back to English where a translation is unavailable.

## Source improvements

Open the companion’s **Source changes** page and select this checkout. AI Chat can list/read allowed addon files, propose an exact replacement against their current SHA-256, compile-check the changed addon, and roll back its own edits. The source picker chooses the root; the model cannot supply it. Edits require confirmation and remain separate from the running installation. The rollback buffer is bounded to 256 KiB in memory, with no source backup tree. Switching workspaces with tracked edits is refused. Closing the app preserves source edits but clears the in-memory buffer; Git remains the durable review history.

Source verification compiles only. It does not execute generated code, tests or a model-editable build script. A successful compile does not establish runtime correctness. The bounded installer is used only after review and while DBeaver is closed.

Actions can return informational evidence while retaining their action permission classification. Streaming chat then continues with that evidence, rather than printing an internal result as the final reply. This supports observation, an approved change and subsequent verification within one request.

## Design and SQL actions

`designWorkbench` exposes its current token schema and applies material, density, text scale, corner radius, glass opacity, blur and icon tint. macOS material uses a clear AppKit glass view where available, with a visual-effect fallback; Reduce Transparency is respected. Legacy WebKit chat backgrounds use their public transparency control. Material attachment is distinct from visible coverage and must be checked in the rendered window. Chat consumes the same design settings through validated renderer tokens. Blue native icon pixels can be tinted without changing red/orange semantic colors or original assets. Browser tint targets SQL action icons and excludes attachment images.

`sqlWorkbench` opens a new SQL console, inspects a requested script, applies an exact hash-guarded edit, and submits a single parsed non-modifying SELECT on a connection marked read-only. WKT formatting uses DBeaver’s native geometry transformer, a fresh result observation and the guarded SQL document. It may refetch the read query. Submission, fetched row counts and rendered spatial output are separate observations.

`captureWorkbench` shares a bounded image of the foreground DBeaver window with the configured image-capable AI provider after confirmation. On macOS it snapshots the app’s own content view without screen-recording access. Images remain in memory, are excluded from saved chat history, and are limited to 1600 pixels per edge and 512 KiB. Blank images fail explicitly; protected or remote-rendered layers may be omitted.

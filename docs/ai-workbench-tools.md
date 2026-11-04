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

The standalone builder compiles only this addon against the plugin jars in an existing DBeaver installation. It runs the model tests and creates one small JAR under the addon’s `target` directory. It does not copy the DBeaver installation or download a build platform.

```sh
python3 scripts/build-workbench-tools.py --app /path/to/DBeaver.app
```

A Java 21+ JDK is required. An existing Eclipse ECJ jar can be supplied with `--compiler /path/to/ecj.jar` when the app includes only a JRE. The optional `--online-test` fetches the official Python CSV documentation; normal tests make no network requests.

Install the generated addon using an Eclipse dropins directory and restart after saving editors. Some products do not auto-start the built-in dropins reconciler; check the [Equinox dropins instructions](https://wiki.eclipse.org/Equinox/p2/Getting_Started#Debugging_dropins) for that installation. No existing plugin JAR is replaced.

Open **Window → Show View → Other → AI Workbench** for the connection and engineering companion. AI Chat can request `observeWorkbench`, `activateWorkbenchPart`, `selectResultPresentation`, `searchWorkbenchCapabilities` and `searchOfficialDocumentation`. The model/provider must support function calling. Tool confirmation defaults to Ask.

With the addon active, Enter submits from the AI prompt and Shift+Enter inserts a new line. Enter during an active IME composition is left to the input method. SQL editors and other text controls keep their original key behavior. The existing Cmd/Ctrl+Enter shortcut remains available.

The companion exposes the AI response-language preference and translated navigation labels in Arabic, French, Spanish and Chinese. Detailed guidance currently falls back to English where a translation is unavailable.

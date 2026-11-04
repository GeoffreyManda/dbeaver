/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jkiss.dbeaver.ui.ai.workbench;

import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetController;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetListener;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetPresentationDescriptor;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetViewer;
import org.jkiss.dbeaver.ui.controls.resultset.handler.ResultSetHandlerMain;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Native display operations. Access only from the SWT display thread. */
final class WorkbenchTools {
    private static final int MAX_PARTS = 64;
    private static final int MAX_PRESENTATIONS = 16;
    private static final Duration MAX_AGE = Duration.ofMinutes(2);
    private static volatile Observation observation;
    private static volatile Disclosure disclosure;

    private WorkbenchTools() {
    }

    @NotNull
    static Map<String, Object> observe() throws DBException {
        clearObservation();
        IWorkbenchWindow window = UIUtils.getActiveWorkbenchWindow();
        if (window == null || window.getActivePage() == null) {
            observation = null;
            throw new DBException("No active workbench page");
        }
        IWorkbenchPage page = window.getActivePage();
        var graph = WorkbenchSceneGraph.builder();
        graph.addNode("workbench", "workbench", Map.of("provenance", "native-workbench"));
        Map<String, IWorkbenchPartReference> parts = new LinkedHashMap<>();
        addParts(graph, parts, page.getEditorReferences(), "editor", page);
        addParts(graph, parts, page.getViewReferences(), "view", page);
        IEditorPart editor = page.getActiveEditor();
        IResultSetController result = ResultSetHandlerMain.getActiveResultSet(editor);
        Map<String, ResultSetPresentationDescriptor> presentations = new LinkedHashMap<>();
        if (result != null && !result.getControl().isDisposed()) {
            graph.addNode("result", "fetched-result", Map.of(
                "fetchedRows", result.getModel().getRowCount(),
                "hasMoreData", result.isHasMoreData(),
                "columnCount", result.getModel().getAttributes().length,
                "readOnly", result.isReadOnly(),
                "dirty", result.getModel().isDirty(),
                "presentationClass", result.getActivePresentation().getClass().getSimpleName()));
            for (var entry : parts.entrySet()) {
                if (entry.getValue().getPart(false) == editor) {
                    graph.addEdge(entry.getKey(), "result", "result");
                }
            }
            if (result instanceof ResultSetViewer viewer) {
                for (ResultSetPresentationDescriptor descriptor : viewer.getAvailablePresentations()) {
                    if (presentations.size() >= MAX_PRESENTATIONS) {
                        graph.markTruncated();
                        break;
                    }
                    String id = "presentation-" + presentations.size();
                    graph.addNode(id, "presentation", Map.of("presentationId", descriptor.getId()));
                    graph.addEdge("result", "presentation", id);
                    presentations.put(id, descriptor);
                }
            }
        }
        NativeSceneInspector.append(graph);
        var snapshot = graph.build();
        observation = new Observation(snapshot.id().toString(), snapshot.observedAt(), page, editor,
            result, result == null ? -1 : result.getModel().getRowCount(), parts, presentations,
            new Invalidator(snapshot.id().toString()), UIUtils.getDisplay());
        if (result != null) {
            result.addListener(observation.listener());
        }
        String observationId = observation.id();
        UIUtils.timerExec((int) MAX_AGE.toMillis(), () -> {
            if (observation != null && observation.id().equals(observationId)) {
                clearObservation();
            }
        });
        return Map.of("snapshotId", snapshot.id().toString(), "observedAt", snapshot.observedAt().toString(),
            "nodes", snapshot.nodes(), "edges", snapshot.edges(), "truncated", snapshot.truncated(),
            "coverage", "active page, visible native shells/controls and fetched metadata; browser DOM and cell content unobserved");
    }

    private static void addParts(WorkbenchSceneGraph.Builder graph,
        Map<String, IWorkbenchPartReference> parts, IWorkbenchPartReference[] refs,
        String type, IWorkbenchPage page) {
        for (IWorkbenchPartReference ref : refs) {
            if (parts.size() >= MAX_PARTS) {
                graph.markTruncated();
                break;
            }
            String id = "part-" + parts.size();
            boolean active = ref.getPart(false) != null && ref.getPart(false) == page.getActivePart();
            graph.addNode(id, type, Map.of("partId", ref.getId(), "active", active,
                "instantiated", ref.getPart(false) != null,
                "selectedEditor", ref.getPart(false) != null && ref.getPart(false) == page.getActiveEditor()));
            graph.addEdge("workbench", "contains", id);
            if (active) {
                graph.addEdge("workbench", "active", id);
            }
            parts.put(id, ref);
        }
    }

    @NotNull
    static Map<String, Object> act(@NotNull String snapshotId, @NotNull String nodeId,
        boolean presentation) throws DBException {
        Observation state = observation;
        Disclosure visible = disclosure;
        if (state == null || !state.id().equals(snapshotId)
            || visible == null || !visible.id().equals(snapshotId) || !visible.nodes().contains(nodeId)
            || Duration.between(state.time(), Instant.now()).compareTo(MAX_AGE) > 0
            || UIUtils.getActiveWorkbenchWindow() == null
            || UIUtils.getActiveWorkbenchWindow().getActivePage() != state.page()
            || state.page().getActiveEditor() != state.editor()) {
            throw new DBException("Stale workbench observation; observe again before acting");
        }
        // Consume the token before acting; failed or repeated calls must observe again.
        clearObservation();
        if (presentation) {
            IResultSetController current = ResultSetHandlerMain.getActiveResultSet(state.editor());
            ResultSetPresentationDescriptor descriptor = state.presentations().get(nodeId);
            if (!(current instanceof ResultSetViewer viewer) || current != state.result()
                || current.getControl().isDisposed() || current.isRefreshInProgress()
                || current.getModel().getRowCount() != state.rows()
                || descriptor == null || !viewer.getAvailablePresentations().contains(descriptor)) {
                throw new DBException("Fetched result changed or presentation unavailable; observe again");
            }
            if (!viewer.switchPresentation(descriptor)) {
                throw new DBException("Presentation was unchanged or could not be selected; observe again");
            }
        } else {
            IWorkbenchPartReference ref = state.parts().get(nodeId);
            if (ref == null || ref.getPart(false) == null
                || state.page().getReference(ref.getPart(false)) != ref) {
                throw new DBException("Observed part is closed or not instantiated; observe again");
            }
            state.page().activate(ref.getPart(false));
        }
        return observe();
    }

    static void disclose(String id, Set<String> nodes) {
        Observation state = observation;
        if (state != null && state.id().equals(id)) {
            disclosure = new Disclosure(id, Set.copyOf(nodes));
        }
    }

    private record Disclosure(String id, Set<String> nodes) {
    }

    static void dispose() {
        disclosure = null;
        Observation state = observation;
        if (state != null && !state.display().isDisposed()) {
            state.display().asyncExec(WorkbenchTools::clearObservation);
        } else {
            observation = null;
        }
    }

    private static void clearObservation() {
        Observation old = observation;
        observation = null;
        disclosure = null;
        if (old != null && old.result() != null && !old.result().getControl().isDisposed()) {
            old.result().removeListener(old.listener());
        }
    }

    private static final class Invalidator implements IResultSetListener {
        private final String id;

        private Invalidator(String id) {
            this.id = id;
        }

        private void invalidate() {
            if (observation != null && observation.id().equals(id)) {
                Observation old = observation;
                observation = null;
                disclosure = null;
                // Some viewer events iterate the live listener list; detach after dispatch.
                UIUtils.asyncExec(() -> {
                    if (old.result() != null && !old.result().getControl().isDisposed()) {
                        old.result().removeListener(old.listener());
                    }
                });
            }
        }

        @Override
        public void handleResultSetLoad() { invalidate(); }

        @Override
        public void handleResultSetChange() { invalidate(); }

        @Override
        public void onModelPrepared() { invalidate(); }

        @Override
        public void handleResultSetSelectionChange(SelectionChangedEvent event) { }
    }

    private record Observation(String id, Instant time, IWorkbenchPage page, IEditorPart editor,
        IResultSetController result, int rows, Map<String, IWorkbenchPartReference> parts,
        Map<String, ResultSetPresentationDescriptor> presentations, IResultSetListener listener, Display display) {
    }
}

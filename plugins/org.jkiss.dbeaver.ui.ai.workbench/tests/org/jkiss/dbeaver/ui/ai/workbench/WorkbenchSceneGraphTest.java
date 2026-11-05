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

import org.jkiss.code.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Dependency-free checks, invoked by the bounded add-on build. */
public final class WorkbenchSceneGraphTest {
    public static void main(@NotNull String[] arguments) {
        bounds();
        invalidGraph();
        immutableSnapshot();
        safeProperties();
    }

    private static void bounds() {
        WorkbenchSceneGraph.Builder builder = WorkbenchSceneGraph.builder();
        for (int i = 0; i < WorkbenchSceneGraph.MAX_NODES; i++) {
            check(builder.addNode("n" + i, "control", Map.of()), "Node within limit rejected");
        }
        check(!builder.addNode("overflow", "control", Map.of()), "Node limit not enforced");
        for (int i = 0; i < WorkbenchSceneGraph.MAX_EDGES; i++) {
            check(builder.addEdge("n" + i % 256, "contains", "n" + (i + 1) % 256), "Edge within limit rejected");
        }
        check(!builder.addEdge("n0", "active", "n1"), "Edge limit not enforced");
        WorkbenchSceneGraph.Snapshot graph = builder.build();
        check(graph.nodes().size() == 256 && graph.edges().size() == 512 && graph.truncated(), "Incorrect capped graph");
        WorkbenchSceneGraph.Builder partial = WorkbenchSceneGraph.builder();
        check(!partial.build().truncated(), "Empty graph incorrectly truncated");
        partial.markTruncated();
        check(partial.build().truncated(), "Explicit truncation lost");
    }

    private static void invalidGraph() {
        WorkbenchSceneGraph.Builder builder = WorkbenchSceneGraph.builder();
        builder.addNode("editor", "editor", Map.of());
        expect(IllegalArgumentException.class, () -> builder.addNode("editor", "control", Map.of()));
        expect(IllegalArgumentException.class, () -> builder.addEdge("editor", "contains", "missing"));
        expect(IllegalArgumentException.class, () -> builder.addEdge("editor", "guessed", "editor"));
        WorkbenchSceneGraph.Node node = builder.build().nodes().getFirst();
        expect(IllegalArgumentException.class, () -> new WorkbenchSceneGraph.Snapshot(
            UUID.randomUUID(), Instant.now(), List.of(node, node), List.of(), false));
        expect(IllegalArgumentException.class, () -> new WorkbenchSceneGraph.Snapshot(
            UUID.randomUUID(), Instant.now(), List.of(node),
            List.of(new WorkbenchSceneGraph.Edge("editor", "result", "missing", "native-workbench")), false));
    }

    @SuppressWarnings("unchecked")
    private static void immutableSnapshot() {
        List<Object> nested = new ArrayList<>(List.of("original"));
        Map<String, Object> nestedMap = new LinkedHashMap<>();
        nestedMap.put("enabled", true);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("nested", nested);
        properties.put("state", nestedMap);
        WorkbenchSceneGraph.Builder builder = WorkbenchSceneGraph.builder();
        builder.addNode("a", "editor", properties);
        final WorkbenchSceneGraph.Snapshot first = builder.build();
        nested.set(0, "changed");
        nestedMap.put("enabled", false);
        properties.clear();
        List<Object> held = (List<Object>) first.nodes().getFirst().properties().get("nested");
        check(held.equals(List.of("original")), "Caller mutation changed snapshot");
        Map<String, Object> heldMap = (Map<String, Object>) first.nodes().getFirst().properties().get("state");
        check(Boolean.TRUE.equals(heldMap.get("enabled")), "Nested map mutation changed snapshot");
        expect(UnsupportedOperationException.class, () -> heldMap.put("enabled", false));
        expect(UnsupportedOperationException.class, () -> held.add("new"));
        expect(UnsupportedOperationException.class, () -> first.nodes().clear());
        expect(UnsupportedOperationException.class, () -> first.edges().clear());
        expect(UnsupportedOperationException.class, () -> first.nodes().getFirst().properties().clear());
        builder.addNode("b", "result", Map.of());
        builder.addEdge("a", "result", "b");
        WorkbenchSceneGraph.Snapshot second = builder.build();
        check(first.nodes().size() == 1 && first.edges().isEmpty(), "Builder mutation changed snapshot");
        check(!first.id().equals(second.id()) && first.observedAt() != null, "Observation identity missing");
        check(second.edges().getFirst().provenance().equals("native-workbench"), "Edge provenance missing");
    }

    private static void safeProperties() {
        Object unsafe = new Object() {
            @NotNull
            @Override
            public String toString() {
                throw new AssertionError("Arbitrary contents were read");
            }
        };
        expect(IllegalArgumentException.class, () -> new WorkbenchSceneGraph.Node("a", "editor", Map.of("unsafe", unsafe)));
        expect(IllegalArgumentException.class, () -> new WorkbenchSceneGraph.Node("a", "editor", Map.of("n", Double.NaN)));
        expect(IllegalArgumentException.class, () -> new WorkbenchSceneGraph.Node("a", "editor", Map.of("n", Float.POSITIVE_INFINITY)));
        expect(IllegalArgumentException.class, () -> new WorkbenchSceneGraph.Node("a", "editor", Map.of("s", "x".repeat(129))));
        Map<String, Object> many = new LinkedHashMap<>();
        for (int i = 0; i < 9; i++) {
            many.put("k" + i, i);
        }
        expect(IllegalArgumentException.class, () -> new WorkbenchSceneGraph.Node("a", "editor", many));
        Map<String, Object> nullable = new LinkedHashMap<>();
        nullable.put("unknown", null);
        check(new WorkbenchSceneGraph.Node("a", "editor", nullable).properties().containsKey("unknown"), "Unknown value lost");
    }

    private static void check(boolean condition, @NotNull String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expect(@NotNull Class<? extends Throwable> expected, @NotNull Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            if (expected.isInstance(error)) {
                return;
            }
            throw new AssertionError("Unexpected failure", error);
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }
}

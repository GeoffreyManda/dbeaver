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

import com.google.gson.Gson;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded responses bind action capabilities to exactly the scene nodes disclosed to the model. */
public final class WorkbenchResponse {
    public static final int MAX_BYTES = 32 * 1024;
    private static final String ERROR = "Workbench response cannot fit within the response limit";
    private static final String INVALID = "Invalid workbench response payload";
    private static final Gson GSON = new Gson();

    private WorkbenchResponse() { }

    public record Response(@NotNull String json, @Nullable String snapshotId, @NotNull Set<String> disclosedNodes) {
        public Response {
            Objects.requireNonNull(json);
            disclosedNodes = Set.copyOf(disclosedNodes);
            if (json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                throw new IllegalArgumentException(ERROR);
            }
            if (snapshotId == null && !disclosedNodes.isEmpty()) {
                throw new IllegalArgumentException(INVALID);
            }
        }
    }

    @NotNull
    public static Response serialize(@NotNull Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException(INVALID);
        }
        Scene scene = payload.containsKey("snapshotId") ? scene(payload) : null;
        try {
            return response(encode(payload), scene, scene == null ? List.of() : scene.nodes());
        } catch (TooLarge e) {
            if (scene == null) {
                throw new IllegalArgumentException(ERROR);
            }
        }
        Response best = null;
        int low = 1;
        int high = scene.nodes().size() - 1;
        // With at most 256 nodes, eight probes find the longest fitting nonempty prefix.
        while (low <= high) {
            int count = low + (high - low) / 2;
            List<WorkbenchSceneGraph.Node> kept = scene.nodes().subList(0, count);
            Set<String> ids = ids(kept);
            Map<String, Object> candidate = new LinkedHashMap<>(payload);
            candidate.put("nodes", kept);
            candidate.put("edges", scene.edges().stream()
                .filter(edge -> ids.contains(edge.from()) && ids.contains(edge.to())).toList());
            candidate.put("truncated", true);
            candidate.put("omittedNodes", scene.nodes().size() - count);
            try {
                best = new Response(encode(candidate), scene.id(), ids);
                low = count + 1;
            } catch (TooLarge e) {
                high = count - 1;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException(ERROR);
        }
        return best;
    }

    private record Scene(String id, List<WorkbenchSceneGraph.Node> nodes, List<WorkbenchSceneGraph.Edge> edges) { }

    @NotNull
    private static Scene scene(@NotNull Map<String, Object> payload) {
        if (!(payload.get("snapshotId") instanceof String id) || id.isBlank() || id.length() > 128
            || !(payload.get("nodes") instanceof List<?> rawNodes) || rawNodes.isEmpty()
            || rawNodes.size() > WorkbenchSceneGraph.MAX_NODES
            || !(payload.get("edges") instanceof List<?> rawEdges) || rawEdges.size() > WorkbenchSceneGraph.MAX_EDGES) {
            throw new IllegalArgumentException(INVALID);
        }
        List<WorkbenchSceneGraph.Node> nodes = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (Object value : rawNodes) {
            if (!(value instanceof WorkbenchSceneGraph.Node node) || !unique.add(node.id())) {
                throw new IllegalArgumentException(INVALID);
            }
            nodes.add(node);
        }
        List<WorkbenchSceneGraph.Edge> edges = new ArrayList<>();
        for (Object value : rawEdges) {
            if (!(value instanceof WorkbenchSceneGraph.Edge edge)
                || !unique.contains(edge.from()) || !unique.contains(edge.to())) {
                throw new IllegalArgumentException(INVALID);
            }
            edges.add(edge);
        }
        return new Scene(id, List.copyOf(nodes), List.copyOf(edges));
    }

    @NotNull
    private static Set<String> ids(@NotNull List<WorkbenchSceneGraph.Node> nodes) {
        Set<String> ids = new LinkedHashSet<>();
        for (WorkbenchSceneGraph.Node node : nodes) {
            ids.add(node.id());
        }
        return ids;
    }

    @NotNull
    private static Response response(@NotNull String json, @Nullable Scene scene, @NotNull List<WorkbenchSceneGraph.Node> nodes) {
        return new Response(json, scene == null ? null : scene.id(), ids(nodes));
    }

    @NotNull
    private static String encode(@NotNull Map<String, Object> payload) {
        BoundedWriter writer = new BoundedWriter();
        GSON.toJson(payload, writer);
        String json = writer.text.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new TooLarge();
        }
        return json;
    }

    private static final class TooLarge extends RuntimeException {
        private TooLarge() {
            super(null, null, false, false);
        }
    }

    /** Every UTF-16 code unit costs at least one UTF-8 byte, so this also bounds interim storage. */
    private static final class BoundedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void write(@NotNull char[] source, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, source.length);
            check(length);
            text.append(source, offset, length);
        }

        @Override
        public void write(@NotNull String source, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, source.length());
            check(length);
            text.append(source, offset, offset + length);
        }

        private void check(int length) {
            if (length > MAX_BYTES - text.length()) {
                throw new TooLarge();
            }
        }

        @Override
        public void flush() { }

        @Override
        public void close() { }
    }
}

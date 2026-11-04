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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable, bounded observations. Callers supply metadata, never arbitrary widget contents. */
public final class WorkbenchSceneGraph {
    public static final int MAX_NODES = 256;
    public static final int MAX_EDGES = 512;
    public static final int MAX_PROPERTIES = 8;
    public static final int MAX_TEXT_LENGTH = 128;
    private static final int MAX_DEPTH = 4;
    private static final String PROVENANCE = "native-workbench";
    private static final Set<String> RELATIONS = Set.of("contains", "active", "result", "presentation");

    private WorkbenchSceneGraph() {
    }

    public record Node(String id, String type, Map<String, Object> properties) {
        public Node {
            id = text(id);
            type = text(type);
            properties = copyProperties(properties, 0);
        }
    }

    public record Edge(String from, String relation, String to, String provenance) {
        public Edge {
            from = text(from);
            to = text(to);
            if (!RELATIONS.contains(relation) || !PROVENANCE.equals(provenance)) {
                throw new IllegalArgumentException("Unsupported edge relation or provenance");
            }
        }
    }

    public record Snapshot(UUID id, Instant observedAt, List<Node> nodes, List<Edge> edges, boolean truncated) {
        public Snapshot {
            Objects.requireNonNull(id);
            Objects.requireNonNull(observedAt);
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
            if (nodes.size() > MAX_NODES || edges.size() > MAX_EDGES) {
                throw new IllegalArgumentException("Scene exceeds graph limits");
            }
            Set<String> ids = new HashSet<>();
            for (Node node : nodes) {
                if (!ids.add(node.id())) {
                    throw new IllegalArgumentException("Duplicate scene node id");
                }
            }
            for (Edge edge : edges) {
                if (!ids.contains(edge.from()) || !ids.contains(edge.to())) {
                    throw new IllegalArgumentException("Dangling scene edge");
                }
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final List<Edge> edges = new ArrayList<>();
        private boolean truncated;

        public boolean addNode(String id, String type, Map<String, Object> properties) {
            Node node = new Node(id, type, properties);
            if (nodes.containsKey(node.id())) {
                throw new IllegalArgumentException("Duplicate scene node id");
            }
            if (nodes.size() == MAX_NODES) {
                truncated = true;
                return false;
            }
            nodes.put(node.id(), node);
            return true;
        }

        public boolean addEdge(String from, String relation, String to) {
            Edge edge = new Edge(from, relation, to, PROVENANCE);
            if (!nodes.containsKey(from) || !nodes.containsKey(to)) {
                throw new IllegalArgumentException("Dangling scene edge");
            }
            if (edges.size() == MAX_EDGES) {
                truncated = true;
                return false;
            }
            edges.add(edge);
            return true;
        }

        public void markTruncated() {
            truncated = true;
        }

        public Snapshot build() {
            return new Snapshot(UUID.randomUUID(), Instant.now(), new ArrayList<>(nodes.values()), edges, truncated);
        }
    }

    private static String text(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Scene text must contain 1 to 128 characters");
        }
        return value;
    }

    private static Map<String, Object> copyProperties(Map<?, ?> source, int depth) {
        Objects.requireNonNull(source);
        if (depth > MAX_DEPTH || source.size() > MAX_PROPERTIES) {
            throw new IllegalArgumentException("Scene property limit exceeded");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Scene property keys must be strings");
            }
            result.put(text(key), copyValue(entry.getValue(), depth + 1));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Object copyValue(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Scene property depth exceeded");
        }
        if (value == null || value instanceof Boolean || value instanceof Byte || value instanceof Short
            || value instanceof Integer || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof String string) {
            if (string.length() > MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException("Scene property text limit exceeded");
            }
            return string;
        }
        if (value instanceof Double doubleValue && Double.isFinite(doubleValue)
            || value instanceof Float floatValue && Float.isFinite(floatValue)) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return copyProperties(map, depth);
        }
        if (value instanceof List<?> list && list.size() <= MAX_PROPERTIES) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(copyValue(item, depth + 1));
            }
            return Collections.unmodifiableList(result);
        }
        throw new IllegalArgumentException("Unsupported scene property type");
    }
}

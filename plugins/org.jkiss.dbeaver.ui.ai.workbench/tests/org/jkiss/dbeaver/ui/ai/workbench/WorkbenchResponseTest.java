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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jkiss.code.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Uses the actual Gson codec rather than duplicating an estimated JSON budget. */
public final class WorkbenchResponseTest {
    private static final Gson GSON = new Gson();
    private static final String COVERAGE = "Visible controls; browser DOM and private values unknown";

    public static void main(@NotNull String[] args) {
        normalMap();
        exactLimit();
        truncatedScene();
        smallScene();
        invalidScenes();
    }

    private static int bytes(@NotNull String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void normalMap() {
        Map<String, Object> payload = Map.of("status", "<&'\" 地图 𐐀", "count", 2, "applicable", true);
        var response = WorkbenchResponse.serialize(payload);
        JsonObject decoded = JsonParser.parseString(response.json()).getAsJsonObject();
        check(decoded.get("status").getAsString().equals(payload.get("status")), "Unicode/escape roundtrip failed");
        check(decoded.get("count").getAsInt() == 2 && decoded.get("applicable").getAsBoolean(), "Normal fields changed");
        check(response.snapshotId() == null && response.disclosedNodes().isEmpty(), "Non-scene gained action IDs");
        expect(UnsupportedOperationException.class, () -> response.disclosedNodes().add("hidden"));
    }

    private static void exactLimit() {
        int overhead = bytes(GSON.toJson(Map.of("text", "")));
        String value = "x".repeat(WorkbenchResponse.MAX_BYTES - overhead);
        check(bytes(WorkbenchResponse.serialize(Map.of("text", value)).json()) == WorkbenchResponse.MAX_BYTES,
            "Exact UTF-8 limit rejected");
        expectLimit(() -> WorkbenchResponse.serialize(Map.of("text", value + "x")));
        int available = WorkbenchResponse.MAX_BYTES - overhead;
        String unicode = "界".repeat(available / 3) + "x".repeat(available % 3);
        check(bytes(WorkbenchResponse.serialize(Map.of("text", unicode)).json()) == WorkbenchResponse.MAX_BYTES,
            "Exact multibyte limit rejected");
        expectLimit(() -> WorkbenchResponse.serialize(Map.of("text", unicode + "x")));
    }

    @NotNull
    private static Map<String, Object> payload(@NotNull WorkbenchSceneGraph.Snapshot snapshot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("snapshotId", snapshot.id().toString());
        payload.put("observedAt", snapshot.observedAt().toString());
        payload.put("coverage", COVERAGE);
        payload.put("nodes", snapshot.nodes());
        payload.put("edges", snapshot.edges());
        payload.put("truncated", snapshot.truncated());
        return payload;
    }

    private static void truncatedScene() {
        var builder = WorkbenchSceneGraph.builder();
        builder.addNode("workbench", "workbench", Map.of());
        String escaped = "<&'\"=\u2028".repeat(20);
        String unicode = "地图𐐀".repeat(20);
        for (int index = 1; index < WorkbenchSceneGraph.MAX_NODES; index++) {
            builder.addNode("node" + index, "control", Map.of(
                "html", escaped, "unicode", unicode, "controls", "\u0000".repeat(128),
                "one", escaped, "two", escaped, "three", escaped, "four", escaped, "five", escaped));
            builder.addEdge("workbench", "contains", "node" + index);
            if (index > 1) {
                builder.addEdge("node" + (index - 1), "active", "node" + index);
            }
        }
        var snapshot = builder.build();
        Map<String, Object> original = payload(snapshot);
        var response = WorkbenchResponse.serialize(original);
        JsonObject json = JsonParser.parseString(response.json()).getAsJsonObject();
        int kept = json.getAsJsonArray("nodes").size();
        check(bytes(response.json()) <= WorkbenchResponse.MAX_BYTES, "UTF-8 limit exceeded");
        check(kept > 1 && kept < snapshot.nodes().size(), "Scene not reduced to a nonempty prefix");
        check(json.get("truncated").getAsBoolean(), "Truncation hidden");
        check(json.get("omittedNodes").getAsInt() == snapshot.nodes().size() - kept, "Omission count incorrect");
        check(json.get("snapshotId").getAsString().equals(snapshot.id().toString()), "Snapshot ID changed");
        check(json.get("observedAt").getAsString().equals(snapshot.observedAt().toString()), "Observation date changed");
        check(json.get("coverage").getAsString().equals(COVERAGE), "Coverage changed");
        check(response.snapshotId().equals(snapshot.id().toString()), "Response binding changed");
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < kept; index++) {
            JsonObject node = json.getAsJsonArray("nodes").get(index).getAsJsonObject();
            String id = node.get("id").getAsString();
            check(id.equals(snapshot.nodes().get(index).id()), "Node prefix reordered");
            ids.add(id);
        }
        check(ids.equals(response.disclosedNodes()) && ids.contains("workbench"), "Disclosed IDs do not match JSON");
        check(!response.disclosedNodes().contains("node255"), "Hidden node is actionable");
        expect(UnsupportedOperationException.class, () -> response.disclosedNodes().clear());
        for (var value : json.getAsJsonArray("edges")) {
            JsonObject edge = value.getAsJsonObject();
            check(ids.contains(edge.get("from").getAsString()) && ids.contains(edge.get("to").getAsString()), "Dangling edge emitted");
        }
        check(json.getAsJsonArray("nodes").get(1).getAsJsonObject().getAsJsonObject("properties")
            .get("html").getAsString().equals(escaped), "HTML escaping changed node metadata");
        Set<String> nextIds = new HashSet<>(ids);
        nextIds.add(snapshot.nodes().get(kept).id());
        Map<String, Object> larger = new LinkedHashMap<>(original);
        larger.put("nodes", snapshot.nodes().subList(0, kept + 1));
        larger.put("edges", snapshot.edges().stream().filter(e -> nextIds.contains(e.from()) && nextIds.contains(e.to())).toList());
        larger.put("truncated", true);
        larger.put("omittedNodes", snapshot.nodes().size() - kept - 1);
        check(bytes(GSON.toJson(larger)) > WorkbenchResponse.MAX_BYTES, "Longest fitting prefix was not retained");
        check(original.get("nodes") == snapshot.nodes() && Boolean.FALSE.equals(original.get("truncated"))
            && !original.containsKey("omittedNodes"), "Caller payload mutated");
    }

    private static void smallScene() {
        var builder = WorkbenchSceneGraph.builder();
        builder.addNode("workbench", "workbench", Map.of());
        builder.markTruncated();
        var snapshot = builder.build();
        var response = WorkbenchResponse.serialize(payload(snapshot));
        JsonObject decoded = JsonParser.parseString(response.json()).getAsJsonObject();
        check(decoded.get("truncated").getAsBoolean() && response.disclosedNodes().equals(Set.of("workbench")),
            "Existing scope truncation or root lost");
        Map<String, Object> largeEnvelope = payload(snapshot);
        largeEnvelope.put("coverage", "x".repeat(WorkbenchResponse.MAX_BYTES));
        expectLimit(() -> WorkbenchResponse.serialize(largeEnvelope));
    }

    private static void invalidScenes() {
        var root = new WorkbenchSceneGraph.Node("workbench", "workbench", Map.of());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("snapshotId", UUID.randomUUID().toString());
        payload.put("nodes", List.of(root, root));
        payload.put("edges", List.of());
        expect(IllegalArgumentException.class, () -> WorkbenchResponse.serialize(payload));
        payload.put("nodes", List.of(root));
        payload.put("edges", List.of(new WorkbenchSceneGraph.Edge("workbench", "contains", "hidden", "native-workbench")));
        expect(IllegalArgumentException.class, () -> WorkbenchResponse.serialize(payload));
        payload.put("nodes", List.of());
        payload.put("edges", List.of());
        expect(IllegalArgumentException.class, () -> WorkbenchResponse.serialize(payload));
    }

    private static void expectLimit(@NotNull Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException error) {
            check(error.getMessage().equals("Workbench response cannot fit within the response limit"), "Unbounded failure detail");
            return;
        }
        throw new AssertionError("Expected fixed response-limit failure");
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

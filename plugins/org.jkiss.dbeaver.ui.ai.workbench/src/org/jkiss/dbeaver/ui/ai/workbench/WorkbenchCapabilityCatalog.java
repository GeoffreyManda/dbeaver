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

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Registry metadata; no execution. */
public final class WorkbenchCapabilityCatalog {
    public static final int MAX_ENTRIES = 4096;
    public static final int MAX_RESULTS = 100;
    public static final int MAX_OUTPUT_BYTES = 32 * 1024;
    private static final int ENVELOPE_BYTES = 256;
    private static final int ENTRY_OVERHEAD_BYTES = 128;
    private final List<Capability> entries;
    private final boolean sourceTruncated;

    public enum Risk { READ_ONLY, DISPLAY_ACTION, DATABASE_WRITE, EXTERNAL_EFFECT, SECURITY, UNKNOWN }

    public record Capability(String id, String kind, String category, String title, String description,
        boolean readOnly, boolean applicable, Risk risk) {
        public Capability {
            id = text(id, 128, false);
            kind = text(kind, 32, false);
            category = text(category, 64, false);
            title = text(title, 160, true);
            description = text(description, 512, true);
            Objects.requireNonNull(risk);
            if (readOnly && risk != Risk.READ_ONLY) {
                throw new IllegalArgumentException("Read-only requires READ_ONLY risk");
            }
        }

        public Map<String, Object> toMap() {
            return Map.of("id", id, "kind", kind, "category", category, "title", title,
                "description", description, "readOnly", readOnly, "applicable", applicable, "risk", risk.name());
        }

        private String searchText() {
            return normalize(id + " " + kind + " " + category + " " + title + " " + description);
        }

        private int byteBudget() {
            return ENTRY_OVERHEAD_BYTES + jsonBytes(id) + jsonBytes(kind) + jsonBytes(category)
                + jsonBytes(title) + jsonBytes(description) + jsonBytes(risk.name());
        }
    }

    public record Result(List<Capability> capabilities, int matched, boolean truncated) {
        public Result {
            capabilities = List.copyOf(capabilities);
            if (matched < capabilities.size() || matched > MAX_ENTRIES || capabilities.size() > MAX_RESULTS) {
                throw new IllegalArgumentException("Invalid catalogue result counts");
            }
            int bytes = ENVELOPE_BYTES;
            for (Capability capability : capabilities) {
                bytes += capability.byteBudget();
            }
            if (bytes > MAX_OUTPUT_BYTES) {
                throw new IllegalArgumentException("Catalogue response budget exceeded");
            }
            if (matched > capabilities.size()) {
                truncated = true;
            }
        }

        public Map<String, Object> toMap() {
            return Map.of("capabilities", capabilities.stream().map(Capability::toMap).toList(),
                "matched", matched, "truncated", truncated);
        }
    }

    public WorkbenchCapabilityCatalog(List<Capability> source) {
        this(source, false);
    }

    public WorkbenchCapabilityCatalog(List<Capability> source, boolean truncated) {
        Objects.requireNonNull(source);
        List<Capability> accepted = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < Math.min(source.size(), MAX_ENTRIES); index++) {
            Capability capability = Objects.requireNonNull(source.get(index));
            if (!ids.add(capability.kind() + "\u0000" + capability.id())) {
                throw new IllegalArgumentException("Duplicate catalogue capability");
            }
            accepted.add(capability);
        }
        entries = List.copyOf(accepted);
        sourceTruncated = truncated || source.size() > MAX_ENTRIES;
    }

    public Result query(String terms, String category, boolean readOnlyOnly, boolean applicableOnly, int limit) {
        String normalized = normalize(text(terms, 256, true)).strip();
        String selectedCategory = category == null || category.isBlank() ? "" : normalize(text(category, 64, false));
        String[] tokens = normalized.isEmpty() ? new String[0] : normalized.split("(?U)\\s+");
        if (tokens.length > 16 || limit < 1) {
            throw new IllegalArgumentException("Invalid catalogue query limits");
        }
        int resultLimit = Math.min(limit, MAX_RESULTS);
        int matched = 0;
        int bytes = ENVELOPE_BYTES;
        boolean truncated = sourceTruncated;
        List<Capability> selected = new ArrayList<>();
        for (Capability capability : entries) {
            if (readOnlyOnly && !capability.readOnly() || applicableOnly && !capability.applicable()
                || !selectedCategory.isEmpty() && !normalize(capability.category()).equals(selectedCategory)) {
                continue;
            }
            String haystack = capability.searchText();
            boolean matches = true;
            for (String token : tokens) {
                if (!haystack.contains(token)) {
                    matches = false;
                    break;
                }
            }
            if (!matches) {
                continue;
            }
            matched++;
            if (selected.size() < resultLimit && bytes + capability.byteBudget() <= MAX_OUTPUT_BYTES) {
                selected.add(capability);
                bytes += capability.byteBudget();
            } else {
                truncated = true;
            }
        }
        return new Result(selected, matched, truncated);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static int jsonBytes(String value) {
        int bytes = 2;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c == '"' || c == '\\' || "\b\f\n\r\t".indexOf(c) >= 0) {
                bytes += 2;
            } else if (c < 32 || "<>&='".indexOf(c) >= 0 || c == '\u2028' || c == '\u2029') {
                bytes += 6;
            } else if (Character.isHighSurrogate(c)) {
                bytes += 4;
                index++;
            } else {
                bytes += c < 128 ? 1 : c < 2048 ? 2 : 3;
            }
        }
        return bytes;
    }

    private static String text(String value, int maxLength, boolean emptyAllowed) {
        if (value == null || value.length() > maxLength || !emptyAllowed && value.isBlank()) {
            throw new IllegalArgumentException("Invalid catalogue metadata length");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new IllegalArgumentException("Invalid catalogue Unicode");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("Invalid catalogue Unicode");
            }
        }
        return value;
    }
}

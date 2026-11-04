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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class WorkbenchCapabilityCatalogTest {
    public static void main(String[] arguments) {
        filtering();
        unicode();
        boundedResults();
        immutable();
        riskAndMetadata();
    }

    private static WorkbenchCapabilityCatalog.Capability entry(String id, String category, String title,
        boolean readOnly, boolean applicable, WorkbenchCapabilityCatalog.Risk risk) {
        return new WorkbenchCapabilityCatalog.Capability(id, "command", category, title, "", readOnly, applicable, risk);
    }

    private static void filtering() {
        var read = entry("inspect", "data", "Inspect selected metadata", true, true, WorkbenchCapabilityCatalog.Risk.READ_ONLY);
        var write = entry("write", "data", "Write selected data", false, true, WorkbenchCapabilityCatalog.Risk.DATABASE_WRITE);
        var off = entry("off", "data", "Inspect offline", true, false, WorkbenchCapabilityCatalog.Risk.READ_ONLY);
        var display = entry("map", "display", "Inspect map", false, true, WorkbenchCapabilityCatalog.Risk.DISPLAY_ACTION);
        var catalog = new WorkbenchCapabilityCatalog(List.of(read, write, off, display));
        check(catalog.query("selected inspect", "DATA", true, true, 100).capabilities().equals(List.of(read)), "AND/category filter");
        check(catalog.query("inspect", null, true, false, 100).matched() == 2, "Read-only filter");
        check(catalog.query("", null, false, true, 100).matched() == 3, "Applicability filter");
        check(catalog.query("[.*]", null, false, false, 100).matched() == 0, "Regex interpreted");
    }

    private static void unicode() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var capability = entry("INSPECT", "géométrie", "ÉCOLE خريطة 地図 🗺", true, true, WorkbenchCapabilityCatalog.Risk.READ_ONLY);
            var catalog = new WorkbenchCapabilityCatalog(List.of(capability));
            check(catalog.query("inspect e\u0301cole خريطة 地図", "GE\u0301OME\u0301TRIE", true, true, 10).matched() == 1,
                "Unicode/locale search");
            check(catalog.query("ÉCOLE\u2003خريطة", null, false, false, 10).matched() == 1, "Unicode whitespace");
        } finally {
            Locale.setDefault(original);
        }
    }

    private static void boundedResults() {
        List<WorkbenchCapabilityCatalog.Capability> source = new ArrayList<>();
        for (int index = 0; index < WorkbenchCapabilityCatalog.MAX_ENTRIES + 1; index++) {
            source.add(entry("item" + index, "data", "Inspect", true, true, WorkbenchCapabilityCatalog.Risk.READ_ONLY));
        }
        var catalog = new WorkbenchCapabilityCatalog(source);
        var result = catalog.query("", null, true, true, 1000);
        check(result.capabilities().size() == 100 && result.matched() == 4096 && result.truncated(), "Count limits failed");
        check(catalog.query("item4096", null, false, false, 10).matched() == 0, "Input cap failed");
        check(catalog.query("none", null, false, false, 10).truncated(), "Source truncation hidden");
        List<WorkbenchCapabilityCatalog.Capability> longEntries = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            longEntries.add(new WorkbenchCapabilityCatalog.Capability("long" + index, "driver", "data", "<&'".repeat(50),
                "\u0000".repeat(512), true, true, WorkbenchCapabilityCatalog.Risk.READ_ONLY));
        }
        var byteLimited = new WorkbenchCapabilityCatalog(longEntries).query("", null, false, false, 100);
        check(byteLimited.capabilities().size() < 100 && byteLimited.matched() == 100 && byteLimited.truncated(), "Byte budget not applied");
        check(new WorkbenchCapabilityCatalog(List.of(), true).query("", null, false, false, 1).truncated(), "Upstream truncation lost");
    }

    private static void immutable() {
        var capability = entry("one", "data", "Inspect", true, true, WorkbenchCapabilityCatalog.Risk.READ_ONLY);
        List<WorkbenchCapabilityCatalog.Capability> source = new ArrayList<>(List.of(capability));
        var catalog = new WorkbenchCapabilityCatalog(source);
        source.clear();
        var result = catalog.query("", null, false, false, 10);
        check(result.matched() == 1, "Source mutation changed catalogue");
        expect(UnsupportedOperationException.class, () -> result.capabilities().clear());
        expect(UnsupportedOperationException.class, () -> capability.toMap().clear());
        Map<String, Object> mapped = result.toMap();
        expect(UnsupportedOperationException.class, () -> mapped.clear());
        expect(UnsupportedOperationException.class, () -> ((List<?>) mapped.get("capabilities")).clear());
    }

    private static void riskAndMetadata() {
        var unknown = entry("unknown", "native", "Unclassified command", false, true, WorkbenchCapabilityCatalog.Risk.UNKNOWN);
        check(new WorkbenchCapabilityCatalog(List.of(unknown)).query("", null, true, false, 10).matched() == 0, "Unknown risk admitted");
        check(unknown.toMap().get("risk").equals("UNKNOWN"), "Risk omitted from response");
        expect(IllegalArgumentException.class, () -> entry("bad", "data", "Bad", true, true, WorkbenchCapabilityCatalog.Risk.DATABASE_WRITE));
        expect(IllegalArgumentException.class, () -> entry("x".repeat(129), "data", "Bad", false, true, WorkbenchCapabilityCatalog.Risk.UNKNOWN));
        expect(IllegalArgumentException.class, () -> entry("id", "data", "x".repeat(161), false, true, WorkbenchCapabilityCatalog.Risk.UNKNOWN));
        expect(IllegalArgumentException.class, () -> entry("id", "data", "\uD800", false, true, WorkbenchCapabilityCatalog.Risk.UNKNOWN));
        expect(IllegalArgumentException.class, () -> new WorkbenchCapabilityCatalog(List.of(unknown, unknown)));
        expect(IllegalArgumentException.class, () -> new WorkbenchCapabilityCatalog(List.of()).query("", null, false, false, 0));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expect(Class<? extends Throwable> expected, Runnable action) {
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

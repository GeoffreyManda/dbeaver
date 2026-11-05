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

import org.eclipse.jface.resource.ColorRegistry;
import org.eclipse.swt.graphics.RGB;
import org.jkiss.code.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps shared workbench roles into the native ER renderer's theme registry. */
final class WorkbenchDiagramTheme {
    private static final String DIAGRAM = "org.jkiss.dbeaver.erd.diagram.";
    private static final Map<String, RGB> ORIGINALS = new LinkedHashMap<>();
    private static final Map<String, RGB> APPLIED = new LinkedHashMap<>();
    private static ColorRegistry registry;

    private WorkbenchDiagramTheme() {
    }

    static void apply(@NotNull ColorRegistry colors, @NotNull WorkbenchAppearance.Palette palette) {
        if (registry != colors) {
            restore();
            registry = colors;
        }
        apply("background", palette.chrome());
        apply("entity.regular.background", palette.raisedSurface());
        apply("entity.primary.background", palette.raisedSurface());
        apply("entity.association.background", palette.raisedSurface());
        apply("entity.name.foreground", palette.chromeForeground());
        apply("attributes.background", palette.chrome());
        apply("attributes.foreground", palette.chromeForeground());
        apply("lines.foreground", palette.focus());
        apply("search.highlighting", palette.warningSurface());
        apply("fk.highlighting", palette.raisedSurface());
        apply("notes.background", palette.raisedSurface());
        apply("notes.foreground", palette.chromeForeground());
    }

    private static void apply(@NotNull String role, int color) {
        String key = DIAGRAM + role;
        RGB current = registry.getRGB(key);
        if (current == null) {
            return;
        }
        RGB target = new RGB(color >>> 16 & 255, color >>> 8 & 255, color & 255);
        if (target.equals(current)) {
            return;
        }
        if (!current.equals(APPLIED.get(key))) {
            ORIGINALS.put(key, current);
        }
        APPLIED.put(key, target);
        registry.put(key, target);
    }

    static void restore() {
        if (registry != null) {
            for (var entry : ORIGINALS.entrySet()) {
                String key = entry.getKey();
                if (APPLIED.get(key).equals(registry.getRGB(key))) {
                    registry.put(key, entry.getValue());
                }
            }
        }
        forget();
    }

    static void forget() {
        registry = null;
        ORIGINALS.clear();
        APPLIED.clear();
    }
}

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

import org.eclipse.e4.ui.workbench.renderers.swt.CTabRendering;
import org.eclipse.swt.custom.CTabFolder;
import org.jkiss.code.NotNull;

/** Pure alpha contract and custom-renderer policy checks; does not create a native display. */
public final class WorkbenchTabMaterialTest {
    private WorkbenchTabMaterialTest() {
    }

    public static void main(@NotNull String[] args) {
        for (int current = 0; current <= 255; current++) {
            for (int background = 0; background <= 255; background++) {
                int result = WorkbenchTabMaterial.effectiveAlpha(current, background);
                check(result >= 0 && result <= current && result <= background, "Composited alpha bounds");
                if (background == 255) {
                    check(result == current, "Opaque body preserves caller alpha");
                }
                if (background == 0 || current == 0) {
                    check(result == 0, "Transparent body remains transparent");
                }
            }
        }
        check(WorkbenchTabMaterial.effectiveAlpha(128, 128) == 64, "Alpha multiplication");
        check(WorkbenchTabMaterial.knownRenderer(CTabRendering.class), "Known e4 renderer");
        check(!WorkbenchTabMaterial.knownRenderer(CustomRendering.class), "Custom renderer must be preserved");
        check(!WorkbenchTabMaterial.knownRenderer(null), "Unknown renderer rejected");
        check(WorkbenchTabMaterial.apply(null, true).get("changed").equals(false), "No renderer without live folder");
        rejects(() -> WorkbenchTabMaterial.effectiveAlpha(-1, 255));
        rejects(() -> WorkbenchTabMaterial.effectiveAlpha(255, 256));
        WorkbenchTabMaterial.stop();
        System.out.println("Tab material alpha and renderer preservation policy checks passed; native painting not exercised");
    }

    private static void rejects(@NotNull Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected invalid alpha rejection");
        } catch (IllegalArgumentException expected) {
            // Expected invalid alpha bounds.
        }
    }

    private static void check(boolean condition, @NotNull String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class CustomRendering extends CTabRendering {
        private CustomRendering(@NotNull CTabFolder parent) {
            super(parent);
        }
    }
}

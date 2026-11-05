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

import org.eclipse.swt.events.DisposeListener;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.ui.ai.chat.controls.WebViewMessageList;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Uses the chat renderer's validated local stylesheet API, never browser script or document access. */
final class WorkbenchThemeIntegration {
    private static final int MAX_VIEWS = 64;
    private static final Map<WebViewMessageList, Map<String, String>> ORIGINALS = new IdentityHashMap<>();
    private static final DisposeListener DISPOSE = event -> ORIGINALS.remove((WebViewMessageList) event.widget);

    private WorkbenchThemeIntegration() {
    }

    static boolean apply(@NotNull WebViewMessageList view, @NotNull WorkbenchAppearance.Palette palette) {
        if (view.isDisposed()) {
            return false;
        }
        if (!ORIGINALS.containsKey(view)) {
            if (ORIGINALS.size() == MAX_VIEWS) {
                return false;
            }
            ORIGINALS.put(view, Map.copyOf(view.getThemeColors()));
            view.addDisposeListener(DISPOSE);
        }
        Map<String, String> colors = colors(palette);
        if (!view.getThemeColors().equals(colors)) {
            view.setThemeColors(colors);
        }
        return true;
    }

    static int size() {
        return ORIGINALS.size();
    }

    static void restore() {
        for (var entry : new ArrayList<>(ORIGINALS.entrySet())) {
            WebViewMessageList view = entry.getKey();
            if (!view.isDisposed()) {
                view.removeDisposeListener(DISPOSE);
                view.setThemeColors(entry.getValue());
            }
        }
        ORIGINALS.clear();
    }

    static void forgetDisposed() {
        ORIGINALS.clear();
    }

    @NotNull
    static Map<String, String> colors(@NotNull WorkbenchAppearance.Palette palette) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("BACKGROUND_COLOR", hex(palette.chrome()));
        values.put("BACKGROUND_COLOR_INACTIVE", hex(palette.raisedSurface()));
        values.put("TEXT_COLOR", hex(palette.chromeForeground()));
        values.put("PROMPT_BACKGROUND", hex(palette.raisedSurface()));
        values.put("PROMPT_BORDER", hex(palette.focus()));
        values.put("HYPERLINK_COLOR", hex(palette.focus()));
        values.put("BORDER_COLOR", hex(palette.focus()));
        values.put("WARNING_BACKGROUND", hex(palette.warningSurface()));
        values.put("ERROR_BACKGROUND", hex(palette.errorSurface()));
        return Map.copyOf(values);
    }

    @NotNull
    private static String hex(int color) {
        return String.format(Locale.ROOT, "#%06X", color);
    }
}

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
        int background = palette.chrome();
        int inactive = mix(background, palette.dark() ? 0 : 0xFFFFFF, 0.07);
        int foreground = palette.chromeForeground();
        int link = palette.accent();
        for (int step = 0; step < 32 && (WorkbenchAppearance.contrast(background, link) < 4.5
            || WorkbenchAppearance.contrast(inactive, link) < 4.5); step++) {
            link = mix(link, foreground, 0.15);
        }
        if (WorkbenchAppearance.contrast(background, link) < 4.5 || WorkbenchAppearance.contrast(inactive, link) < 4.5) {
            link = foreground;
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("BACKGROUND_COLOR", hex(background));
        values.put("BACKGROUND_COLOR_INACTIVE", hex(inactive));
        values.put("TEXT_COLOR", hex(foreground));
        values.put("PROMPT_BACKGROUND", hex(inactive));
        values.put("PROMPT_BORDER", hex(link));
        values.put("HYPERLINK_COLOR", hex(link));
        values.put("BORDER_COLOR", hex(link));
        values.put("WARNING_BACKGROUND", hex(readableSurface(mix(background, 0xE5A000, 0.15), foreground)));
        values.put("ERROR_BACKGROUND", hex(readableSurface(mix(background, 0xCC3333, 0.15), foreground)));
        return Map.copyOf(values);
    }

    private static int readableSurface(int color, int foreground) {
        int endpoint = foreground == 0 ? 0xFFFFFF : 0;
        for (int step = 0; step < 32 && WorkbenchAppearance.contrast(color, foreground) < 4.5; step++) {
            color = mix(color, endpoint, 0.08);
        }
        return WorkbenchAppearance.contrast(color, foreground) >= 4.5 ? color : endpoint;
    }

    private static int mix(int first, int second, double amount) {
        int rgb = 0;
        for (int shift : new int[] {16, 8, 0}) {
            int channel = (int) Math.round((first >>> shift & 255) * (1 - amount) + (second >>> shift & 255) * amount);
            rgb |= channel << shift;
        }
        return rgb;
    }

    @NotNull
    private static String hex(int color) {
        return String.format(Locale.ROOT, "#%06X", color);
    }
}

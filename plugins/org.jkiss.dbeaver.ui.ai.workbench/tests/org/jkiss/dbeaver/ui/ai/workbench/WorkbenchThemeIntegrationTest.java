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
import org.jkiss.dbeaver.ui.ai.chat.controls.WebCSSInitializer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Palette checks are independent of native widgets, browser contents and conversation data. */
public final class WorkbenchThemeIntegrationTest {
    public static void main(@NotNull String[] arguments) {
        validatedCoreApi();
        Set<String> expected = Set.of("BACKGROUND_COLOR", "BACKGROUND_COLOR_INACTIVE", "TEXT_COLOR", "PROMPT_BACKGROUND",
            "PROMPT_BORDER", "WARNING_BACKGROUND", "ERROR_BACKGROUND", "HYPERLINK_COLOR", "BORDER_COLOR");
        for (int red = 0; red <= 255; red += 51) {
            for (int green = 0; green <= 255; green += 51) {
                for (int blue = 0; blue <= 255; blue += 51) {
                    String accent = String.format(java.util.Locale.ROOT, "#%02X%02X%02X", red, green, blue);
                    for (boolean dark : new boolean[] {false, true}) {
                        Map<String, String> colors = WorkbenchThemeIntegration.colors(WorkbenchAppearance.palette(accent, dark));
                        check(colors.keySet().equals(expected), "Unexpected stylesheet token");
                        for (String value : colors.values()) {
                            check(value.matches("#[0-9A-F]{6}"), "Non-color stylesheet value");
                        }
                        int text = rgb(colors.get("TEXT_COLOR"));
                        int link = rgb(colors.get("HYPERLINK_COLOR"));
                        for (String surface : new String[] {"BACKGROUND_COLOR", "BACKGROUND_COLOR_INACTIVE", "PROMPT_BACKGROUND",
                            "WARNING_BACKGROUND", "ERROR_BACKGROUND"}) {
                            check(WorkbenchAppearance.contrast(text, rgb(colors.get(surface))) >= 4.5, "Browser text contrast failed");
                        }
                        check(WorkbenchAppearance.contrast(link, rgb(colors.get("BACKGROUND_COLOR"))) >= 4.5, "Link contrast failed");
                        check(WorkbenchAppearance.contrast(link, rgb(colors.get("BACKGROUND_COLOR_INACTIVE"))) >= 4.5,
                            "Inactive link contrast failed");
                        try {
                            colors.put("FONT_FAMILY", "javascript:invalid");
                            throw new AssertionError("Mutable browser palette");
                        } catch (UnsupportedOperationException expectedFailure) {
                            // Immutable fixed color tokens cannot carry scripts, URLs or font overrides.
                        }
                    }
                }
            }
        }
    }

    private static void validatedCoreApi() {
        Map<String, String> caller = new LinkedHashMap<>(Map.of("BACKGROUND_COLOR", "#aAbBcC"));
        Map<String, String> validated = WebCSSInitializer.validateThemeColors(caller);
        caller.clear();
        check(validated.equals(Map.of("BACKGROUND_COLOR", "#AABBCC")), "Core palette is mutable or not normalized");
        check(WebCSSInitializer.validateThemeColors(Map.of()).isEmpty(), "Core palette reset rejected");
        for (Map<String, String> invalid : java.util.List.of(
            Map.of("FONT_FAMILY", "#112233"), Map.of("TEXT_COLOR", "url(javascript:invalid)"),
            Map.of("BACKGROUND_COLOR", "#123456;display:none"), Map.of("COLOR_SCHEME", "#112233"),
            Map.of("TEXT_COLOR", "#123"))) {
            try {
                WebCSSInitializer.validateThemeColors(invalid);
                throw new AssertionError("Unsafe or unsupported core palette accepted");
            } catch (IllegalArgumentException expectedFailure) {
                // Only fixed color tokens can cross the native-to-renderer boundary.
            }
        }
        try {
            validated.put("TEXT_COLOR", "#123456");
            throw new AssertionError("Validated core palette is mutable");
        } catch (UnsupportedOperationException expectedFailure) {
            // The renderer retains an immutable snapshot of the explicit color palette.
        }
    }

    private static int rgb(@NotNull String color) {
        return Integer.parseInt(color.substring(1), 16);
    }

    private static void check(boolean condition, @NotNull String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

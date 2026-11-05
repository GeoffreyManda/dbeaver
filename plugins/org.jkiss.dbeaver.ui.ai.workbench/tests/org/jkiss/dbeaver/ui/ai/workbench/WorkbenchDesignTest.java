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
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure schema/material checks; no native windows, preferences, browser content or fonts are changed. */
public final class WorkbenchDesignTest {
    public static void main(@NotNull String[] arguments) {
        if (!WebCSSInitializer.designStyles(Map.of("material", "glass")).contains("html,body{background:transparent;}")) {
            throw new AssertionError("Glass must not retain the opaque chat page background");
        }

        if (WorkbenchDesign.normalizedParameters(Map.of("operation", "apply", "accent", "system")).containsKey("accent")) {
            throw new AssertionError("Reported system accent must round-trip without becoming a custom color");
        }
        var omitted = WorkbenchDesign.normalizedParameters(Map.of("operation", "apply", "accent", "", "material", "glass"));
        if (omitted.containsKey("accent") || !omitted.get("material").equals("glass")) {
            throw new AssertionError("Blank optional values must remain absent");
        }

        defaultsAndSchema();
        typedBounds();
        clearGlass();
        nativeDensity();
    }

    private static void defaultsAndSchema() {
        Set<String> keys = Set.of("material", "density", "fontScale", "radius", "opacity", "blur", "iconTint");
        check(WebCSSInitializer.getDesignSchema().keySet().equals(keys), "Incomplete renderer design schema");
        Map<String, Object> defaults = WebCSSInitializer.validateDesignTokens(Map.of());
        check(defaults.get("material").equals("flat") && defaults.get("density").equals("comfortable"),
            "Original material/density defaults changed");
        check(defaults.get("fontScale").equals(1.0) && defaults.get("blur").equals(0), "Heavy or scaled default");
        check(defaults.get("opacity").equals(0.02), "Default glass body is not clear");
        check(WebCSSInitializer.designStyles(Map.of()).isEmpty(), "Reset retains generated material rules");
        try {
            defaults.put("material", "glass");
            throw new AssertionError("Mutable design state");
        } catch (UnsupportedOperationException expected) {
            // Explicit token state is immutable.
        }
    }

    private static void typedBounds() {
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(
            Map.of("material", "glass; color:red"), Map.of("density", "arbitrary"), Map.of("fontScale", 0.84),
            Map.of("fontScale", 1.31), Map.of("fontScale", "1.0"), Map.of("fontScale", Double.NaN),
            Map.of("opacity", 0.01), Map.of("opacity", 0.21), Map.of("opacity", Double.POSITIVE_INFINITY),
            Map.of("radius", 21), Map.of("radius", -1), Map.of("radius", 1.5), Map.of("blur", 9),
            Map.of("blur", -1), Map.of("blur", "url(javascript:invalid)"), Map.of("css", "body{display:none}"),
            Map.of("iconTint", "url(invalid)"))) {
            try {
                WebCSSInitializer.validateDesignTokens(invalid);
                throw new AssertionError("Out-of-range or untyped design input accepted");
            } catch (IllegalArgumentException expected) {
                // Semantic tokens cannot carry executable CSS, script, URLs or unbounded numbers.
            }
        }
        Map<String, Object> caller = new LinkedHashMap<>(Map.of("material", "glass", "fontScale", 1.3,
            "density", "compact", "radius", 20, "blur", 8, "opacity", 0.2));
        Map<String, Object> validated = WebCSSInitializer.validateDesignTokens(caller);
        caller.clear();
        check(validated.get("fontScale").equals(1.3) && validated.get("radius").equals(20), "Valid limits rejected");
        check(validated.get("blur").equals(8) && validated.get("opacity").equals(0.2), "Optical bounds not preserved");
    }

    private static void clearGlass() {
        String defaults = WebCSSInitializer.designStyles(Map.of("material", "glass"));
        check(defaults.contains("color-mix(in srgb,var(--background-color) 2.0%,transparent)"), "Opaque glass body");
        check(defaults.contains("border:1px solid") && defaults.contains("inset 0 1px 0"), "Rim/specular cap missing");
        check(defaults.contains("inset 0 -1px 0") && defaults.contains("0 3px 10px"), "Hairline/shadow missing");
        check(defaults.contains("backdrop-filter:none") && !defaults.contains("blur(0px)"), "Zero-blur gate missing");
        check(defaults.contains("prefers-reduced-transparency:reduce"), "Reduced-transparency fallback missing");
        check(!defaults.contains("javascript:") && !defaults.contains("url(") && !defaults.contains("<script"),
            "Generated style can transmit or execute arbitrary content");
        String bounded = WebCSSInitializer.designStyles(Map.of("material", "glass", "density", "compact",
            "radius", 20, "blur", 8, "opacity", 0.2, "fontScale", 1.3));
        check(bounded.contains("blur(8px)") && bounded.contains("border-radius:20px"), "Explicit optical limits ignored");
        check(bounded.length() < 4096, "Unbounded material stylesheet");
        String flat = WebCSSInitializer.designStyles(Map.of("material", "flat"));
        check(!flat.contains("backdrop-filter") && !flat.contains("box-shadow"), "Flat material retains glass effects");
        String white = WebCSSInitializer.designStyles(Map.of("iconTint", "#FFFFFF"));
        check(white.contains(".sql-button-img{filter:grayscale(1) brightness(0) invert(1.0)"),
            "White tint is not limited to known SQL action icons");
        String color = WebCSSInitializer.designStyles(Map.of("iconTint", "#2563EB"));
        check(!color.contains(".sql-button-img"), "Unsupported browser color tint was fabricated");
    }

    private static void nativeDensity() {
        check(WorkbenchDesign.spacing(0, true) == 0, "Zero margin changed");
        check(WorkbenchDesign.spacing(8, true) == 6, "Compact density not applied");
        check(WorkbenchDesign.spacing(8, false) == 8, "Comfortable/default density not reversible");
    }

    private static void check(boolean condition, @NotNull String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

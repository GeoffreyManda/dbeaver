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

import java.util.Locale;

/** Palette and input checks need no Display and do not change native appearance or preferences. */
public final class WorkbenchAppearanceTest {
    public static void main(String[] arguments) {
        normalization();
        invalidInputs();
        contrast();
        derivedChrome();
    }

    private static void normalization() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            check(WorkbenchAppearance.normalizeAccent(" BLUE ").equals("#2563EB"), "Blue not normalized");
            check(WorkbenchAppearance.normalizeAccent("#aBc09f").equals("#ABC09F"), "Hex not normalized");
            check(WorkbenchAppearance.normalizeAccent("default").isEmpty(), "Default not cleared");
            check(WorkbenchAppearance.normalizeAccent("RESET").isEmpty(), "Reset not cleared");
        } finally {
            Locale.setDefault(original);
        }
    }

    private static void invalidInputs() {
        for (String value : new String[] {"", "#123", "#1234567", "#ZZ0000", "blue; color:red", "url(file:/private)",
            "org.eclipse.e4.ui.css.theme.e4_dark", "x".repeat(33)}) {
            try {
                WorkbenchAppearance.normalizeAccent(value);
            } catch (IllegalArgumentException e) {
                continue;
            }
            throw new AssertionError("Invalid appearance input accepted");
        }
        try {
            WorkbenchAppearance.normalizeAccent(null);
        } catch (IllegalArgumentException e) {
            return;
        }
        throw new AssertionError("Null appearance input accepted");
    }

    private static void contrast() {
        check(Math.abs(WorkbenchAppearance.contrast(0, 0xFFFFFF) - 21) < 0.000001, "WCAG endpoints incorrect");
        check(Math.abs(WorkbenchAppearance.contrast(0x123456, 0x123456) - 1) < 0.000001, "Equal colors incorrect");
        for (int red = 0; red <= 255; red += 17) {
            for (int green = 0; green <= 255; green += 17) {
                for (int blue = 0; blue <= 255; blue += 17) {
                    int rgb = red << 16 | green << 8 | blue;
                    int foreground = WorkbenchAppearance.foreground(rgb);
                    check(foreground == 0 || foreground == 0xFFFFFF, "Foreground is not black or white");
                    check(WorkbenchAppearance.contrast(rgb, foreground) >= 4.5, "Selection contrast below WCAG AA");
                    check(WorkbenchAppearance.contrast(rgb, foreground) >= WorkbenchAppearance.contrast(rgb,
                        foreground == 0 ? 0xFFFFFF : 0), "Foreground does not maximize contrast");
                }
            }
        }
    }

    private static void derivedChrome() {
        for (String color : new String[] {"blue", "red", "green", "purple", "orange", "black", "white", "#777777"}) {
            String normalized = WorkbenchAppearance.normalizeAccent(color);
            var light = WorkbenchAppearance.palette(normalized, false);
            var dark = WorkbenchAppearance.palette(normalized, true);
            check(light.accent() == Integer.parseInt(normalized.substring(1), 16), "Selection accent changed");
            check(light.chrome() != dark.chrome() && !light.dark() && dark.dark(), "Base theme not respected");
            for (var palette : new WorkbenchAppearance.Palette[] {light, dark}) {
                check(WorkbenchAppearance.contrast(palette.accent(), palette.selectionForeground()) >= 4.5,
                    "Palette selection contrast failed");
                check(WorkbenchAppearance.contrast(palette.chrome(), palette.chromeForeground()) >= 4.5,
                    "Palette chrome contrast failed");
            }
        }
        var blue = WorkbenchAppearance.palette(WorkbenchAppearance.normalizeAccent("blue"), false);
        check((blue.chrome() & 255) > (blue.chrome() >>> 16 & 255), "Blue chrome has no blue tint");
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}

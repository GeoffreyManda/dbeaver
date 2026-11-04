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

import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.CoolBar;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.themes.IThemeManager;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIStyles;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Reversible native chrome colors. Does not replace the theme, render CSS or inspect editor content. */
public final class WorkbenchAppearance {
    public static final String PREFERENCE_KEY = "ai.workbench.accent";
    private static final int MAX_CONTROLS = 2048;
    private static final int MAX_SCAN = 16384;
    private static final int MAX_QUEUE = 4096;
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final Map<Control, Original> ORIGINALS = new IdentityHashMap<>();
    private static final Map<Integer, Color> COLORS = new LinkedHashMap<>();
    private static final DisposeListener DISPOSE = event -> ORIGINALS.remove((Control) event.widget);
    private static volatile Display installedDisplay;
    private static long installedGeneration;
    private static String activeAccent;
    private static Palette activePalette;
    private static Listener refreshFilter;
    private static IThemeManager themeManager;
    private static IPropertyChangeListener themeListener;
    private static boolean queued;
    private static boolean rebaseRequested;
    private static boolean truncated;

    private WorkbenchAppearance() { }

    /** Applies only colors to native tab headers/toolbars and persists the normalized RGB preference. */
    public static Map<String, Object> apply(String accent) {
        Display display = requireDisplay();
        String normalized = normalizeAccent(accent);
        int restored = ORIGINALS.size();
        long generation = GENERATION.incrementAndGet();
        reset(normalized.isEmpty());
        if (normalized.isEmpty()) {
            DBWorkbench.getPlatform().getPreferenceStore().setValue(PREFERENCE_KEY, "");
            return Map.of("accent", "default", "styledControls", 0, "restoredControls", restored,
                "scope", "native interface surfaces, tabs and toolbars", "persisted", true);
        }
        if (generation != GENERATION.get()) {
            throw new IllegalStateException("Appearance operation canceled");
        }
        installedGeneration = generation;
        installedDisplay = display;
        activeAccent = normalized;
        activePalette = palette(normalized, UIStyles.isDarkTheme());
        installHooks(display);
        scan(display);
        if (generation != GENERATION.get()) {
            reset(true);
            throw new IllegalStateException("Appearance operation canceled");
        }
        DBWorkbench.getPlatform().getPreferenceStore().setValue(PREFERENCE_KEY, normalized);
        return Map.of("accent", normalized, "styledControls", ORIGINALS.size(),
            "themeMode", activePalette.dark() ? "dark" : "light", "truncated", truncated,
            "scope", "native interface surfaces, tabs and toolbars", "persisted", true);
    }

    /** Called by the normal startup path, never by a background documentation tool. */
    public static void restoreSaved() {
        requireDisplay();
        String saved = DBWorkbench.getPlatform().getPreferenceStore().getString(PREFERENCE_KEY);
        if (saved == null || saved.isBlank()) {
            return;
        }
        try {
            apply(saved);
        } catch (IllegalArgumentException e) {
            DBWorkbench.getPlatform().getPreferenceStore().setValue(PREFERENCE_KEY, "");
        }
    }

    /** Off-thread safe: stopping an unused instance does not create a Display. */
    public static void stop() {
        long generation = GENERATION.incrementAndGet();
        Display display = installedDisplay;
        if (display == null) {
            return;
        }
        if (display.isDisposed()) {
            forgetDisposed(display);
            return;
        }
        if (Display.getCurrent() == display) {
            reset(true);
            return;
        }
        try {
            display.asyncExec(() -> {
                if (installedDisplay == display && installedGeneration < generation) {
                    reset(true);
                }
            });
        } catch (SWTException e) {
            if (!display.isDisposed()) { throw e; }
            forgetDisposed(display);
        }
    }

    private static Display requireDisplay() {
        Display display = Display.getCurrent();
        if (display == null || display.isDisposed()) {
            throw new IllegalStateException("Appearance operations require the SWT display thread");
        }
        return display;
    }

    private static void installHooks(Display display) {
        refreshFilter = event -> schedule(event.type == SWT.Skin || event.type == SWT.Settings);
        for (int event : new int[] { SWT.Show, SWT.Skin, SWT.Settings, SWT.Activate, SWT.Selection }) {
            display.addFilter(event, refreshFilter);
        }
        if (PlatformUI.isWorkbenchRunning()) {
            themeManager = PlatformUI.getWorkbench().getThemeManager();
            themeListener = event -> schedule(true);
            themeManager.addPropertyChangeListener(themeListener);
        }
    }

    private static void schedule(boolean rebase) {
        Display display = installedDisplay;
        if (display == null || display.isDisposed() || activeAccent == null) {
            return;
        }
        // Theme notifications may originate off the display thread.
        long generation = installedGeneration;
        if (Display.getCurrent() != display) {
            display.asyncExec(() -> {
                if (display == installedDisplay && generation == installedGeneration) { schedule(rebase); }
            });
            return;
        }
        rebaseRequested |= rebase;
        if (queued) { return; }
        queued = true;
        display.asyncExec(() -> {
            if (display != installedDisplay || generation != installedGeneration || generation != GENERATION.get()) {
                return;
            }
            queued = false;
            boolean dark = UIStyles.isDarkTheme();
            if (rebaseRequested || dark != activePalette.dark()) {
                rebaseOriginals();
                activePalette = palette(activeAccent, dark);
                rebaseRequested = false;
            }
            scan(display);
        });
    }

    private static void scan(Display display) {
        ArrayDeque<Control> queue = new ArrayDeque<>();
        for (var shell : display.getShells()) {
            if (queue.size() == MAX_QUEUE) { truncated = true; break; }
            queue.addLast(shell);
        }
        int scanned = 0;
        while (!queue.isEmpty()) {
            if (++scanned > MAX_SCAN) { truncated = true; break; }
            Control control = queue.removeFirst();
            if (control.isDisposed() || control instanceof Browser) { continue; }
            style(control);
            if (control instanceof Composite composite) {
                for (Control child : composite.getChildren()) {
                    if (queue.size() == MAX_QUEUE) { truncated = true; break; }
                    queue.addLast(child);
                }
            }
        }
    }

    private static boolean protectedContent(Control control) {
        return control instanceof Text || control instanceof StyledText || control instanceof Browser
            || control instanceof Combo || control instanceof CCombo || control instanceof Tree || control instanceof Table;
    }

    private static boolean safeChrome(Composite parent) {
        ArrayDeque<Control> queue = new ArrayDeque<>();
        queue.add(parent);
        int count = 0;
        while (!queue.isEmpty()) {
            if (++count > 256) { return false; }
            Control control = queue.removeFirst();
            if (control.isDisposed()) { continue; }
            if (protectedContent(control)) { return false; }
            if (control instanceof Composite composite) {
                for (Control child : composite.getChildren()) {
                    if (queue.size() == 256) { return false; }
                    queue.addLast(child);
                }
            }
        }
        return true;
    }

    private static void style(Control control) {
        if (!ORIGINALS.containsKey(control)) {
            if (ORIGINALS.size() == MAX_CONTROLS) { truncated = true; return; }
            ORIGINALS.put(control, Original.capture(control));
            control.addDisposeListener(DISPOSE);
        }
        if (control instanceof CTabFolder folder) {
            // Apply both the inactive surface and selected header with readable text.
            folder.setBackground(color(activePalette.chrome()));
            folder.setForeground(color(activePalette.chromeForeground()));
            folder.setSelectionBackground(color(activePalette.accent()));
            folder.setSelectionForeground(color(activePalette.selectionForeground()));
        } else {
            control.setBackground(color(activePalette.chrome()));
            if (!(control instanceof StyledText)) {
                control.setForeground(color(activePalette.chromeForeground()));
            }
        }
        control.redraw();
    }

    private static Color color(int rgb) {
        return COLORS.computeIfAbsent(rgb, value -> new Color(installedDisplay,
            value >>> 16 & 255, value >>> 8 & 255, value & 255));
    }

    private static boolean ownedColor(Color color) {
        return color != null && !color.isDisposed() && COLORS.containsKey(rgb(color.getRGB()));
    }

    private static void rebaseOriginals() {
        for (var entry : ORIGINALS.entrySet()) {
            Control control = entry.getKey();
            if (!control.isDisposed()) { entry.setValue(entry.getValue().rebase(control)); }
        }
    }

    private record Original(Color background, Color foreground, Color selectedBackground, Color selectedForeground) {
        static Original capture(Control control) {
            return new Original(control.getBackground(), control.getForeground(),
                control instanceof CTabFolder folder ? folder.getSelectionBackground() : null,
                control instanceof CTabFolder folder ? folder.getSelectionForeground() : null);
        }

        Original rebase(Control control) {
            Original current = capture(control);
            return new Original(base(current.background, background), base(current.foreground, foreground),
                base(current.selectedBackground, selectedBackground), base(current.selectedForeground, selectedForeground));
        }

        private static Color base(Color current, Color original) {
            return ownedColor(current) ? original : current;
        }
    }

    private static Color live(Color original, Display display, int fallback) {
        return original == null || original.isDisposed() ? display.getSystemColor(fallback) : original;
    }

    private static void reset(boolean reskin) {
        Display display = installedDisplay;
        if (display == null) { return; }
        if (display.isDisposed()) { forgetDisposed(display); return; }
        if (refreshFilter != null) {
            for (int event : new int[] { SWT.Show, SWT.Skin, SWT.Settings, SWT.Activate, SWT.Selection }) {
                display.removeFilter(event, refreshFilter);
            }
        }
        if (themeManager != null && themeListener != null) { themeManager.removePropertyChangeListener(themeListener); }
        for (var entry : new ArrayList<>(ORIGINALS.entrySet())) {
            Control control = entry.getKey();
            Original original = entry.getValue();
            if (control.isDisposed()) { continue; }
            control.removeDisposeListener(DISPOSE);
            if (control instanceof CTabFolder folder) {
                folder.setSelectionBackground(live(original.selectedBackground(), display, SWT.COLOR_LIST_SELECTION));
                folder.setSelectionForeground(live(original.selectedForeground(), display, SWT.COLOR_LIST_SELECTION_TEXT));
            } else {
                control.setBackground(live(original.background(), display, SWT.COLOR_WIDGET_BACKGROUND));
                control.setForeground(live(original.foreground(), display, SWT.COLOR_WIDGET_FOREGROUND));
            }
            if (reskin) { control.reskin(SWT.NONE); }
            control.redraw();
        }
        // No native control may retain an owned Color when it is disposed.
        for (Color color : COLORS.values()) { if (!color.isDisposed()) { color.dispose(); } }
        clearReferences();
    }

    private static void forgetDisposed(Display display) {
        if (installedDisplay != display) { return; }
        if (themeManager != null && themeListener != null) { themeManager.removePropertyChangeListener(themeListener); }
        clearReferences();
    }

    private static void clearReferences() {
        ORIGINALS.clear();
        COLORS.clear();
        installedDisplay = null;
        activeAccent = null;
        activePalette = null;
        refreshFilter = null;
        themeManager = null;
        themeListener = null;
        queued = false;
        rebaseRequested = false;
        truncated = false;
    }

    static String normalizeAccent(String value) {
        if (value == null || value.length() > 32) {
            throw new IllegalArgumentException("Use a supported accent name or #RRGGBB");
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.equals("default") || normalized.equals("reset")) {
            return "";
        }
        normalized = switch (normalized) {
            case "blue" -> "#2563eb";
            case "red" -> "#b91c1c";
            case "green" -> "#15803d";
            case "purple" -> "#7e22ce";
            case "orange" -> "#c2410c";
            case "black" -> "#000000";
            case "white" -> "#ffffff";
            default -> normalized;
        };
        if (!normalized.matches("#[0-9a-f]{6}")) {
            throw new IllegalArgumentException("Use a supported accent name or #RRGGBB");
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    static record Palette(int accent, int selectionForeground, int chrome, int chromeForeground, boolean dark) { }

    static Palette palette(String accent, boolean dark) {
        int rgb = Integer.parseInt(accent.substring(1), 16);
        int chrome = mix(rgb, dark ? 0x202124 : 0xFFFFFF, dark ? 0.08 : 0.06);
        return new Palette(rgb, foreground(rgb), chrome, foreground(chrome), dark);
    }

    private static int mix(int accent, int base, double amount) {
        int result = 0;
        for (int shift : new int[] {16, 8, 0}) {
            int value = (int) Math.round((accent >>> shift & 255) * amount + (base >>> shift & 255) * (1 - amount));
            result |= value << shift;
        }
        return result;
    }

    private static int rgb(RGB rgb) {
        return rgb.red << 16 | rgb.green << 8 | rgb.blue;
    }

    static int foreground(int rgb) {
        return contrast(rgb, 0xFFFFFF) >= contrast(rgb, 0) ? 0xFFFFFF : 0;
    }

    static double contrast(int first, int second) {
        double one = luminance(first);
        double two = luminance(second);
        return (Math.max(one, two) + 0.05) / (Math.min(one, two) + 0.05);
    }

    private static double luminance(int rgb) {
        double result = 0;
        int[] shifts = {16, 8, 0};
        double[] weights = {0.2126, 0.7152, 0.0722};
        for (int index = 0; index < shifts.length; index++) {
            double channel = (rgb >>> shifts[index] & 255) / 255.0;
            result += weights[index] * (channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4));
        }
        return result;
    }
}

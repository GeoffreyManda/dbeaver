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
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.themes.IThemeManager;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIStyles;
import org.jkiss.dbeaver.ui.ai.chat.controls.AIPromptStyledText;
import org.jkiss.dbeaver.ui.ai.chat.controls.WebViewMessageList;

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
    private static final Map<CTabItem, TabOriginal> TAB_ORIGINALS = new IdentityHashMap<>();
    private static final Map<Integer, Color> COLORS = new LinkedHashMap<>();
    private static final DisposeListener DISPOSE = event -> ORIGINALS.remove((Control) event.widget);
    private static final DisposeListener TAB_DISPOSE = event -> TAB_ORIGINALS.remove((CTabItem) event.widget);
    private static volatile Display installedDisplay;
    private static long installedGeneration;
    private static String activeAccent;
    private static Palette activePalette;
    private static Listener refreshFilter;
    private static IThemeManager themeManager;
    private static IPropertyChangeListener themeListener;
    private static final org.eclipse.swt.events.PaintListener TAB_HIGHLIGHT = event -> {
        CTabFolder folder = (CTabFolder) event.widget;
        if (activePalette != null && folder.getSelection() != null) {
            var bounds = folder.getSelection().getBounds();
            event.gc.setBackground(color(activePalette.focus()));
            event.gc.fillRectangle(bounds.x, bounds.y + bounds.height - 2, bounds.width, 2);
        }
    };
    private static boolean queued;
    private static boolean rebaseRequested;
    private static boolean truncated;

    private WorkbenchAppearance() {
    }

    /** Applies only colors to native tab headers/toolbars and persists the normalized RGB preference. */
    @NotNull
    public static Map<String, Object> apply(@NotNull String accent) {
        final Display display = requireDisplay();
        String normalized = normalizeAccent(accent);
        int restored = ORIGINALS.size();
        long generation = GENERATION.incrementAndGet();
        reset(normalized.isEmpty());
        if (normalized.isEmpty()) {
            DBWorkbench.getPlatform().getPreferenceStore().setValue(PREFERENCE_KEY, "");
            return Map.of("accent", "default", "styledControls", 0, "restoredControls", restored,
                "scope", "native interface and embedded chat", "persisted", true);
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
        return Map.of("accent", normalized, "styledControls", ORIGINALS.size(), "styledTabLabels", TAB_ORIGINALS.size(),
            "styledChatViews", WorkbenchThemeIntegration.size(),
            "themeMode", activePalette.dark() ? "dark" : "light", "truncated", truncated,
            "scope", "native interface and embedded chat", "persisted", true);
    }

    /** Repaints design-aware surfaces without changing the user's saved accent. */
    public static void refreshForDesign() {
        Display display = requireDisplay();
        String saved = DBWorkbench.getPlatform().getPreferenceStore().getString(PREFERENCE_KEY);
        if ((saved == null || saved.isBlank()) && !WorkbenchDesign.isGlass()) {
            if (installedDisplay != null) {
                GENERATION.incrementAndGet();
                reset(true);
            }
            return;
        }
        if (installedDisplay == null) {
            installedGeneration = GENERATION.incrementAndGet();
            installedDisplay = display;
            activeAccent = saved == null || saved.isBlank()
                ? String.format(Locale.ROOT, "#%06X", rgb(display.getSystemColor(SWT.COLOR_LIST_SELECTION).getRGB())) : saved;
            activePalette = palette(activeAccent, UIStyles.isDarkTheme());
            installHooks(display);
        }
        scan(display);
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
            if (!display.isDisposed()) {
                throw e;
            }
            forgetDisposed(display);
        }
    }

    @NotNull
    private static Display requireDisplay() {
        Display display = Display.getCurrent();
        if (display == null || display.isDisposed()) {
            throw new IllegalStateException("Appearance operations require the SWT display thread");
        }
        return display;
    }

    private static void installHooks(@NotNull Display display) {
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
                if (display == installedDisplay && generation == installedGeneration) {
                    schedule(rebase);
                }
            });
            return;
        }
        rebaseRequested |= rebase;
        if (queued) {
            return;
        }
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

    private static void scan(@NotNull Display display) {
        ArrayDeque<Control> queue = new ArrayDeque<>();
        for (var shell : display.getShells()) {
            if (queue.size() == MAX_QUEUE) {
                truncated = true;
                break;
            }
            queue.addLast(shell);
        }
        int scanned = 0;
        while (!queue.isEmpty()) {
            if (++scanned > MAX_SCAN) {
                truncated = true;
                break;
            }
            Control control = queue.removeFirst();
            if (control.isDisposed()) {
                continue;
            }
            boolean material = WorkbenchDesign.isGlass() && NativeWorkbenchMaterial.isApplied(control.getShell());
            if (control instanceof Browser) {
                if (material) {
                    NativeWorkbenchMaterial.prepareControl(control);
                }
                continue;
            }
            if (control instanceof WebViewMessageList chat && !WorkbenchThemeIntegration.apply(chat, activePalette)) {
                truncated = true;
            }
            style(control);
            if (material && control instanceof CTabFolder) {
                NativeWorkbenchMaterial.applyRegion(control);
            }
            if (control instanceof Composite composite) {
                for (Control child : composite.getChildren()) {
                    if (queue.size() == MAX_QUEUE) {
                        truncated = true;
                        break;
                    }
                    queue.addLast(child);
                }
            }
        }
        WorkbenchDesign.refreshFromAppearance();
    }

    private static void style(@NotNull Control control) {
        if (!ORIGINALS.containsKey(control)) {
            if (ORIGINALS.size() + TAB_ORIGINALS.size() == MAX_CONTROLS) {
                truncated = true;
                return;
            }
            ORIGINALS.put(control, Original.capture(control));
            control.addDisposeListener(DISPOSE);
            if (control instanceof CTabFolder folder) {
                folder.addPaintListener(TAB_HIGHLIGHT);
            }
        }
        boolean glass = WorkbenchDesign.isGlass() && NativeWorkbenchMaterial.isApplied(control.getShell());
        int surface = control instanceof AIPromptStyledText ? activePalette.raisedSurface() : activePalette.chrome();
        Color background = color(surface);
        if (control instanceof CTabFolder folder) {
            rememberTabItems(folder);
            // Gradients style the headers; the body renderer reads the actual control background alpha.
            folder.setBackground(glass ? translucent(surface, WorkbenchDesign.glassOpacity()) : background);
            folder.setBackground(new Color[] {background, background},
                new int[] {100}, true);
            folder.setForeground(color(activePalette.chromeForeground()));
            Color selected = color(activePalette.raisedSurface());
            folder.setSelectionBackground(new Color[] {selected, selected},
                new int[] {100}, true);
            folder.setSelectionForeground(color(activePalette.chromeForeground()));
            WorkbenchTabMaterial.apply(folder, glass);
            for (CTabItem item : folder.getItems()) {
                if (TAB_ORIGINALS.containsKey(item)) {
                    item.setForeground(color(activePalette.chromeForeground()));
                    item.setSelectionForeground(color(activePalette.chromeForeground()));
                }
            }
        } else {
            // Keep chrome opaque so the material underlay cannot wash out text and icons.
            control.setBackground(background);
            if (!(control instanceof StyledText) || control instanceof AIPromptStyledText) {
                control.setForeground(color(activePalette.chromeForeground()));
            }
        }
        control.redraw();
    }

    private static void rememberTabItems(@NotNull CTabFolder folder) {
        Original parent = ORIGINALS.get(folder);
        for (CTabItem item : folder.getItems()) {
            if (item.isDisposed() || TAB_ORIGINALS.containsKey(item)) {
                continue;
            }
            if (ORIGINALS.size() + TAB_ORIGINALS.size() == MAX_CONTROLS) {
                truncated = true;
                break;
            }
            Color foreground = item.getForeground();
            Color selected = item.getSelectionForeground();
            TAB_ORIGINALS.put(item, new TabOriginal(ownedColor(foreground) ? parent.foreground() : foreground,
                ownedColor(selected) ? parent.selectedForeground() : selected));
            item.addDisposeListener(TAB_DISPOSE);
        }
    }

    private record TabOriginal(@Nullable Color foreground, @Nullable Color selected) {
    }

    @NotNull
    private static Color color(int rgb) {
        return COLORS.computeIfAbsent(rgb, value -> new Color(installedDisplay,
            value >>> 16 & 255, value >>> 8 & 255, value & 255));
    }

    private static boolean ownedColor(@Nullable Color color) {
        return color != null && !color.isDisposed() && COLORS.containsValue(color);
    }

    @NotNull
    private static Color translucent(int rgb, double opacity) {
        int alpha = Math.max(5, Math.min(51, (int) Math.round(opacity * 255)));
        int key = alpha << 24 | rgb;
        return COLORS.computeIfAbsent(key, ignored -> new Color(installedDisplay,
            new org.eclipse.swt.graphics.RGBA(rgb >>> 16 & 255, rgb >>> 8 & 255, rgb & 255, alpha)));
    }

    private static void rebaseOriginals() {
        for (var entry : ORIGINALS.entrySet()) {
            Control control = entry.getKey();
            if (!control.isDisposed()) {
                entry.setValue(entry.getValue().rebase(control));
            }
        }
        for (var entry : TAB_ORIGINALS.entrySet()) {
            CTabItem item = entry.getKey();
            if (!item.isDisposed()) {
                Color foreground = item.getForeground();
                Color selected = item.getSelectionForeground();
                entry.setValue(new TabOriginal(ownedColor(foreground) ? entry.getValue().foreground() : foreground,
                    ownedColor(selected) ? entry.getValue().selected() : selected));
            }
        }
    }

    private record Original(@NotNull Color background, @NotNull Color foreground,
        @Nullable Color selectedBackground, @Nullable Color selectedForeground) {
        @NotNull
        static Original capture(@NotNull Control control) {
            return new Original(control.getBackground(), control.getForeground(),
                control instanceof CTabFolder folder ? folder.getSelectionBackground() : null,
                control instanceof CTabFolder folder ? folder.getSelectionForeground() : null);
        }

        @NotNull
        Original rebase(@NotNull Control control) {
            Original current = capture(control);
            return new Original(base(current.background, background), base(current.foreground, foreground),
                base(current.selectedBackground, selectedBackground), base(current.selectedForeground, selectedForeground));
        }

        @Nullable
        private static Color base(@Nullable Color current, @Nullable Color original) {
            return ownedColor(current) ? original : current;
        }
    }

    @NotNull
    private static Color live(@Nullable Color original, @NotNull Display display, int fallback) {
        return original == null || original.isDisposed() ? display.getSystemColor(fallback) : original;
    }

    private static void reset(boolean reskin) {
        Display display = installedDisplay;
        if (display == null) {
            return;
        }
        if (display.isDisposed()) {
            forgetDisposed(display);
            return;
        }
        if (refreshFilter != null) {
            for (int event : new int[] { SWT.Show, SWT.Skin, SWT.Settings, SWT.Activate, SWT.Selection }) {
                display.removeFilter(event, refreshFilter);
            }
        }
        if (themeManager != null && themeListener != null) {
            themeManager.removePropertyChangeListener(themeListener);
        }
        WorkbenchThemeIntegration.restore();
        for (var entry : new ArrayList<>(ORIGINALS.entrySet())) {
            Control control = entry.getKey();
            final Original original = entry.getValue();
            if (control.isDisposed()) {
                continue;
            }
            control.removeDisposeListener(DISPOSE);
            if (control instanceof CTabFolder folder) {
                folder.removePaintListener(TAB_HIGHLIGHT);
            }
            control.setBackground(live(original.background(), display, SWT.COLOR_WIDGET_BACKGROUND));
            control.setForeground(live(original.foreground(), display, SWT.COLOR_WIDGET_FOREGROUND));
            if (control instanceof CTabFolder folder) {
                folder.setSelectionBackground(live(original.selectedBackground(), display, SWT.COLOR_LIST_SELECTION));
                folder.setSelectionForeground(live(original.selectedForeground(), display, SWT.COLOR_LIST_SELECTION_TEXT));
            }
            if (reskin) {
                control.reskin(SWT.NONE);
            }
            control.redraw();
        }
        for (var entry : new ArrayList<>(TAB_ORIGINALS.entrySet())) {
            CTabItem item = entry.getKey();
            if (!item.isDisposed()) {
                item.removeDisposeListener(TAB_DISPOSE);
                item.setForeground(live(entry.getValue().foreground(), display, SWT.COLOR_WIDGET_FOREGROUND));
                item.setSelectionForeground(live(entry.getValue().selected(), display, SWT.COLOR_LIST_SELECTION_TEXT));
                if (reskin) {
                    item.reskin(SWT.NONE);
                }
            }
        }
        WorkbenchTabMaterial.stop();
        // No native control may retain an owned Color when it is disposed.
        for (Color color : COLORS.values()) {
            if (!color.isDisposed())  {
                color.dispose();
            }
        }
        clearReferences();
    }

    private static void forgetDisposed(@NotNull Display display) {
        if (installedDisplay != display) {
            return;
        }
        if (themeManager != null && themeListener != null) {
            themeManager.removePropertyChangeListener(themeListener);
        }
        clearReferences();
    }

    private static void clearReferences() {
        ORIGINALS.clear();
        TAB_ORIGINALS.clear();
        WorkbenchThemeIntegration.forgetDisposed();
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

    @NotNull
    static String normalizeAccent(@Nullable String value) {
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

    static record Palette(int accent, int selectionForeground, int chrome, int chromeForeground, boolean dark) {
        int raisedSurface() {
            return mix(dark ? 0x000000 : 0xFFFFFF, chrome, 0.07);
        }

        int focus() {
            int focus = accent;
            for (int step = 0; step < 32; step++) {
                if (contrast(chrome, focus) >= 4.5 && contrast(raisedSurface(), focus) >= 4.5) {
                    return focus;
                }
                focus = mix(chromeForeground, focus, 0.15);
            }
            return chromeForeground;
        }

        int warningSurface() {
            return statusSurface(0xE5A000);
        }

        int errorSurface() {
            return statusSurface(0xCC3333);
        }

        private int statusSurface(int tone) {
            int tinted = mix(tone, chrome, 0.15);
            return contrast(tinted, chromeForeground) >= 4.5 ? tinted : chrome;
        }
    }

    @NotNull
    static Palette palette(@NotNull String accent, boolean dark) {
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

    private static int rgb(@NotNull RGB rgb) {
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

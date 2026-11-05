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

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.CoolBar;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.ai.chat.controls.AIPromptStyledText;
import org.jkiss.dbeaver.ui.ai.chat.controls.WebCSSInitializer;
import org.jkiss.dbeaver.ui.ai.chat.controls.WebViewMessageList;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Semantic design state, native geometry/typography and renderer material; never arbitrary CSS or script. */
public final class WorkbenchDesign {
    private static final String PREFIX = "ai.workbench.design.";
    private static final int MAX_CONTROLS = 1024;
    private static final int MAX_FONTS = 128;
    private static final int MAX_VIEWS = 64;
    private static final int MAX_SCAN = 8192;
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final Map<Control, NativeState> NATIVE = new IdentityHashMap<>();
    private static final Map<Font, Font> FONTS = new IdentityHashMap<>();
    private static final Map<WebViewMessageList, Map<String, Object>> VIEWS = new IdentityHashMap<>();
    private static final DisposeListener DISPOSE = event -> NATIVE.remove((Control) event.widget);
    private static final DisposeListener VIEW_DISPOSE = event -> VIEWS.remove((WebViewMessageList) event.widget);
    private static volatile Display storedDisplay;
    private static Map<String, Object> currentTokens = Map.of();
    private static List<Map<String, Object>> materialStatus = List.of();
    private static Map<String, Object> iconStatus = Map.of();
    private static boolean applying;
    private static boolean refreshing;
    private static boolean truncated;

    private WorkbenchDesign() {
    }

    /** Full inspect/apply/reset interface. Only named, bounded semantic tokens are accepted. */
    @NotNull
    public static Map<String, Object> execute(@NotNull Map<String, Object> parameters) {
        parameters = normalizedParameters(parameters);
        Display display = requireDisplay();
        Set<String> allowed = Set.of("operation", "material", "density", "fontScale", "radius", "opacity", "blur",
            "accent", "iconTint");
        if (parameters.size() > allowed.size() || !allowed.containsAll(parameters.keySet())) {
            throw new IllegalArgumentException("Unsupported design parameter");
        }
        Object operation = parameters.getOrDefault("operation", "inspect");
        if (!(operation instanceof String name) || name.length() > 16) {
            throw new IllegalArgumentException("Invalid design operation");
        }
        if (name.equals("inspect") || name.equals("get")) {
            return describe();
        }
        if (name.equals("reset")) {
            applying = true;
            try {
                GENERATION.incrementAndGet();
                restoreNative();
                currentTokens = Map.of();
                clearSaved();
                NativeWorkbenchMaterial.reset();
                WorkbenchIconTheme.reset();
                store().setValue(PREFIX + "iconTint", "");
                iconStatus = Map.of();
                WorkbenchAppearance.refreshForDesign();
                restoreAccent();
                materialStatus = List.of();
                return describe();
            } finally {
                applying = false;
            }
        }
        if (!name.equals("apply")) {
            throw new IllegalArgumentException("Design operation must be inspect, apply or reset");
        }
        Map<String, Object> tokens = new LinkedHashMap<>(current());
        parameters.forEach((key, value) -> {
            if (!key.equals("operation") && !key.equals("accent")) {
                tokens.put(key, value);
            }
        });
        Map<String, Object> validated = WebCSSInitializer.validateDesignTokens(tokens);
        String accent = parameters.containsKey("accent") ? validateAccent(parameters.get("accent")) : null;
        String iconTint = (String) validated.get("iconTint");
        applying = true;
        try {
            GENERATION.incrementAndGet();
            restoreNative();
            storedDisplay = display;
            currentTokens = validated;
            materialStatus = applyMaterial(display);
            if (accent != null) {
                rememberAccent(accent);
                WorkbenchAppearance.apply(accent);
            }
            WorkbenchAppearance.refreshForDesign();
            refreshNative();
            if (iconTint.equals("default")) {
                WorkbenchIconTheme.reset();
                iconStatus = Map.of("iconTint", "default", "restored", true);
            } else {
                iconStatus = Map.copyOf(WorkbenchIconTheme.apply(display, iconTint));
            }
            store().setValue(PREFIX + "iconTint", iconTint);
            save(validated);
            return describe();
        } finally {
            applying = false;
        }
    }

    @NotNull
    static Map<String, Object> normalizedParameters(@NotNull Map<String, Object> parameters) {
        if (parameters.size() > 9) {
            throw new IllegalArgumentException("Too many design parameters");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        parameters.forEach((key, value) -> {
            if (key.equals("accent") && value instanceof String accent
                && (accent.equalsIgnoreCase("system") || accent.equalsIgnoreCase("default"))) {
                return;
            }
            if (key.equals("operation") || value != null && !(value instanceof String text && text.isBlank())) {
                normalized.put(key, value);
            }
        });
        return normalized;
    }

    @NotNull
    private static Map<String, Object> describe() {
        Map<String, Object> schema = new LinkedHashMap<>(WebCSSInitializer.getDesignSchema());
        schema.put("accent", Map.of("type", "string", "format", "#RRGGBB", "optional", true));
        schema.put("iconTint", Map.of("type", "string", "format", "default or #RRGGBB", "default", "default",
            "browser", "monochrome SQL action icons", "native", "registered native image handles"));
        Map<String, Object> state = new LinkedHashMap<>(current());
        String accent = store().getString(WorkbenchAppearance.PREFERENCE_KEY);
        state.put("accent", accent.matches("#[0-9A-Fa-f]{6}") ? accent : "system");
        state.put("iconTint", validateIconTint(store().getString(PREFIX + "iconTint")));
        return Map.of("schema", Map.copyOf(schema), "current", Map.copyOf(state),
            "trackedNativeControls", NATIVE.size(), "styledChatViews", VIEWS.size(), "truncated", truncated,
            "nativeMaterial", materialStatus, "icons", iconStatus,
            "implementation", Map.of("browser", "clear translucent shell, rim, highlight and bounded backdrop blur",
                "native", "native material status, reversible spacing, typography and noninteractive rim",
                "refraction", false, "animation", false));
    }

    /** Native material providers read these only on the display thread. */
    public static boolean isGlass() {
        return currentTokens.getOrDefault("material", "flat").equals("glass");
    }

    public static double glassOpacity() {
        return ((Number) currentTokens.getOrDefault("opacity", 0.02)).doubleValue();
    }

    /** Called after native appearance refresh; does not recursively apply the appearance palette. */
    public static void refreshFromAppearance() {
        if (!applying) {
            refreshNative();
        }
    }

    private static void refreshNative() {
        Display display = storedDisplay;
        if (refreshing || currentTokens.isEmpty() || display == null || display.isDisposed() || Display.getCurrent() != display) {
            return;
        }
        refreshing = true;
        try {
            materialStatus = applyMaterial(display);
            ArrayDeque<Control> queue = new ArrayDeque<>();
            for (var shell : display.getShells()) {
                if (queue.size() == 2048) {
                    truncated = true;
                    break;
                }
                queue.addLast(shell);
            }
            int count = 0;
            while (!queue.isEmpty()) {
                if (++count > MAX_SCAN) {
                    truncated = true;
                    break;
                }
                Control control = queue.removeFirst();
                if (control.isDisposed() || control instanceof Browser) {
                    continue;
                }
                if (control instanceof WebViewMessageList view) {
                    styleChat(view);
                }
                if (!(control instanceof StyledText) || control instanceof AIPromptStyledText) {
                    styleNative(control);
                }
                if (control instanceof Composite composite) {
                    for (Control child : composite.getChildren()) {
                        if (queue.size() == 2048) {
                            truncated = true;
                            break;
                        }
                        queue.addLast(child);
                    }
                }
            }
            WorkbenchIconTheme.refresh(display);
        } finally {
            refreshing = false;
        }
    }

    @NotNull
    private static List<Map<String, Object>> applyMaterial(@NotNull Display display) {
        if (!isGlass()) {
            NativeWorkbenchMaterial.reset();
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (var shell : display.getShells()) {
            if (result.size() == 16) {
                truncated = true;
                break;
            }
            if (!shell.isDisposed()) {
                result.add(Map.copyOf(NativeWorkbenchMaterial.apply(shell)));
            }
        }
        return List.copyOf(result);
    }

    private static void styleChat(@NotNull WebViewMessageList view) {
        if (!VIEWS.containsKey(view)) {
            if (VIEWS.size() == MAX_VIEWS) {
                truncated = true;
                return;
            }
            VIEWS.put(view, Map.copyOf(view.getDesignTokens()));
            view.addDisposeListener(VIEW_DISPOSE);
        }
        if (!view.getDesignTokens().equals(currentTokens)) {
            view.setDesignTokens(currentTokens);
        }
    }

    private static void styleNative(@NotNull Control control) {
        NativeState state = NATIVE.get(control);
        if (state == null) {
            if (NATIVE.size() == MAX_CONTROLS) {
                truncated = true;
                return;
            }
            boolean chrome = control instanceof Shell || control instanceof CTabFolder
                || control instanceof CoolBar || control instanceof ToolBar;
            PaintListener rim = chrome ? event -> paintRim((Composite) control, event) : null;
            state = new NativeState(control.getFont(), LayoutState.capture(control), rim);
            NATIVE.put(control, state);
            control.addDisposeListener(DISPOSE);
            if (rim != null) {
                control.addPaintListener(rim);
            }
        }
        double scale = ((Number) currentTokens.getOrDefault("fontScale", 1.0)).doubleValue();
        Font original = state.font().isDisposed() ? control.getDisplay().getSystemFont() : state.font();
        Font target = original;
        if (scale != 1.0) {
            target = FONTS.get(original);
            if (target == null && FONTS.size() < MAX_FONTS) {
                FontData[] data = original.getFontData();
                for (FontData font : data) {
                    font.setHeight(Math.max(1, (int) Math.round(font.getHeight() * scale)));
                }
                target = new Font(control.getDisplay(), data);
                FONTS.put(original, target);
            }
            if (target == null) {
                target = original;
                truncated = true;
            }
        }
        if (!control.getFont().equals(target)) {
            control.setFont(target);
        }
        if (state.layout() != null && state.layout().apply(control,
            currentTokens.getOrDefault("density", "comfortable").equals("compact"))) {
            ((Composite) control).layout(true, false);
        }
        control.redraw();
    }

    private static void paintRim(@NotNull Composite control, @NotNull PaintEvent event) {
        if (!isGlass() || control.isDisposed()) {
            return;
        }
        int alpha = event.gc.getAlpha();
        Color foreground = event.gc.getForeground();
        int width = event.gc.getLineWidth();
        var area = control.getClientArea();
        int radius = ((Number) currentTokens.getOrDefault("radius", 8)).intValue();
        try {
            event.gc.setLineWidth(1);
            event.gc.setForeground(control.getDisplay().getSystemColor(SWT.COLOR_WHITE));
            event.gc.setAlpha(Math.max(30, (int) Math.round(glassOpacity() * 255)));
            event.gc.drawRoundRectangle(0, 0, Math.max(0, area.width - 1), Math.max(0, area.height - 1), radius, radius);
            event.gc.setAlpha(90);
            event.gc.drawLine(radius / 2, 0, Math.max(radius / 2, area.width - radius / 2 - 1), 0);
        } finally {
            event.gc.setAlpha(alpha);
            event.gc.setForeground(foreground);
            event.gc.setLineWidth(width);
        }
    }

    private record NativeState(@NotNull Font font, @Nullable LayoutState layout, @Nullable PaintListener rim) {
    }

    private record LayoutState(@NotNull Layout layout, @NotNull int[] values) {
        @Nullable
        static LayoutState capture(@NotNull Control control) {
            if (!(control instanceof Composite composite)) {
                return null;
            }
            Layout layout = composite.getLayout();
            if (layout instanceof GridLayout grid) {
                return new LayoutState(grid,
                    new int[] {grid.marginWidth, grid.marginHeight, grid.horizontalSpacing, grid.verticalSpacing});
            }
            if (layout instanceof FillLayout fill) {
                return new LayoutState(fill, new int[] {fill.marginWidth, fill.marginHeight, fill.spacing});
            }
            if (layout instanceof RowLayout row) {
                return new LayoutState(row, new int[] {row.marginLeft, row.marginRight, row.marginTop,
                    row.marginBottom, row.marginWidth, row.marginHeight, row.spacing});
            }
            return null;
        }

        boolean apply(@NotNull Control control, boolean compact) {
            if (!(control instanceof Composite composite) || composite.getLayout() != layout) {
                return false;
            }
            int[] target = values.clone();
            for (int index = 0; index < target.length; index++) {
                target[index] = spacing(target[index], compact);
            }
            LayoutState current = capture(control);
            if (current != null && java.util.Arrays.equals(current.values(), target)) {
                return false;
            }
            if (layout instanceof GridLayout grid) {
                grid.marginWidth = target[0];
                grid.marginHeight = target[1];
                grid.horizontalSpacing = target[2];
                grid.verticalSpacing = target[3];
            } else if (layout instanceof FillLayout fill) {
                fill.marginWidth = target[0];
                fill.marginHeight = target[1];
                fill.spacing = target[2];
            } else if (layout instanceof RowLayout row) {
                row.marginLeft = target[0];
                row.marginRight = target[1];
                row.marginTop = target[2];
                row.marginBottom = target[3];
                row.marginWidth = target[4];
                row.marginHeight = target[5];
                row.spacing = target[6];
            }
            return true;
        }
    }

    static int spacing(int original, boolean compact) {
        return compact ? (int) Math.round(original * 0.75) : original;
    }

    /** Restores native/user state before owned fonts are disposed. Preference values are retained for restart. */
    private static void restoreNative() {
        for (var entry : new ArrayList<>(VIEWS.entrySet())) {
            WebViewMessageList view = entry.getKey();
            if (!view.isDisposed()) {
                view.removeDisposeListener(VIEW_DISPOSE);
                view.setDesignTokens(entry.getValue());
            }
        }
        VIEWS.clear();
        for (var entry : new ArrayList<>(NATIVE.entrySet())) {
            Control control = entry.getKey();
            NativeState state = entry.getValue();
            if (!control.isDisposed()) {
                control.removeDisposeListener(DISPOSE);
                if (state.rim() != null) {
                    control.removePaintListener(state.rim());
                }
                control.setFont(state.font().isDisposed() ? control.getDisplay().getSystemFont() : state.font());
                if (state.layout() != null && state.layout().apply(control, false)) {
                    ((Composite) control).layout(true, false);
                }
                control.redraw();
            }
        }
        NATIVE.clear();
        for (Font font : FONTS.values()) {
            if (!font.isDisposed()) {
                font.dispose();
            }
        }
        FONTS.clear();
        truncated = false;
    }

    public static void restoreSaved() {
        requireDisplay();
        Map<String, Object> saved = readSaved();
        if (!saved.isEmpty()) {
            Map<String, Object> command = new LinkedHashMap<>(saved);
            command.put("operation", "apply");
            execute(command);
        }
    }

    public static void stop() {
        final long generation = GENERATION.incrementAndGet();
        NativeWorkbenchMaterial.stop();
        Display display = storedDisplay;
        if (display == null) {
            return;
        }
        if (display.isDisposed()) {
            forgetDisposed();
            return;
        }
        try {
            display.asyncExec(() -> {
                if (display == storedDisplay && generation == GENERATION.get()) {
                    restoreNative();
                    forgetDisposed();
                }
            });
        } catch (SWTException e) {
            if (!display.isDisposed()) {
                throw e;
            }
            forgetDisposed();
        }
    }

    private static void forgetDisposed() {
        NATIVE.clear();
        FONTS.clear();
        VIEWS.clear();
        storedDisplay = null;
        currentTokens = Map.of();
        materialStatus = List.of();
        iconStatus = Map.of();
    }

    @NotNull
    private static Display requireDisplay() {
        Display display = Display.getCurrent();
        if (display == null || display.isDisposed()) {
            throw new IllegalStateException("Design operations require the SWT display thread");
        }
        return display;
    }

    @NotNull
    private static DBPPreferenceStore store() {
        return DBWorkbench.getPlatform().getPreferenceStore();
    }

    @NotNull
    private static Map<String, Object> current() {
        Map<String, Object> values = currentTokens.isEmpty() ? readSaved() : currentTokens;
        return WebCSSInitializer.validateDesignTokens(values);
    }

    @NotNull
    private static Map<String, Object> readSaved() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (var entry : WebCSSInitializer.getDesignSchema().entrySet()) {
            String value = store().getString(PREFIX + entry.getKey());
            if (!value.isBlank()) {
                if (entry.getKey().equals("material") || entry.getKey().equals("density") || entry.getKey().equals("iconTint")) {
                    values.put(entry.getKey(), value);
                } else {
                    try {
                        values.put(entry.getKey(), Double.parseDouble(value));
                    } catch (NumberFormatException e) {
                        return Map.of();
                    }
                }
            }
        }
        try {
            return values.isEmpty() ? Map.of() : WebCSSInitializer.validateDesignTokens(values);
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    private static void save(@NotNull Map<String, Object> values) {
        values.forEach((key, value) -> store().setValue(PREFIX + key, value.toString()));
    }

    private static void clearSaved() {
        WebCSSInitializer.getDesignSchema().keySet().forEach(key -> store().setValue(PREFIX + key, ""));
    }

    @NotNull
    private static String validateAccent(@Nullable Object value) {
        if (!(value instanceof String text) || !text.matches("#[0-9A-Fa-f]{6}")) {
            throw new IllegalArgumentException("Design accent must be #RRGGBB");
        }
        return WorkbenchAppearance.normalizeAccent(text);
    }

    @NotNull
    private static String validateIconTint(@Nullable Object value) {
        if (value instanceof String text && (text.isBlank() || text.equals("default"))) {
            return "default";
        }
        return validateAccent(value);
    }

    private static void rememberAccent(@NotNull String accent) {
        if (!store().getBoolean(PREFIX + "accentOwned")) {
            store().setValue(PREFIX + "accentBefore", store().getString(WorkbenchAppearance.PREFERENCE_KEY));
        }
        store().setValue(PREFIX + "accentOwned", true);
        store().setValue(PREFIX + "accentLast", accent);
    }

    private static void restoreAccent() {
        if (store().getBoolean(PREFIX + "accentOwned") && store().getString(WorkbenchAppearance.PREFERENCE_KEY)
            .equals(store().getString(PREFIX + "accentLast"))) {
            String before = store().getString(PREFIX + "accentBefore");
            WorkbenchAppearance.apply(before.matches("#[0-9A-Fa-f]{6}") ? before : "default");
        }
        store().setValue(PREFIX + "accentOwned", false);
        store().setValue(PREFIX + "accentBefore", "");
        store().setValue(PREFIX + "accentLast", "");
    }
}

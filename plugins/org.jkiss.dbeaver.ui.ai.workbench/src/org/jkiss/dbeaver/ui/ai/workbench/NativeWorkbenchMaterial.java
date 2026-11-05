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
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Public AppKit material beneath existing native child views; never changes shell alpha or reparents content. */
public final class NativeWorkbenchMaterial {
    private static final int MAX_SHELLS = 4;
    private static final int RECHECK_MILLISECONDS = 2000;
    private static final long HUD_MATERIAL = 13;
    private static final long CLEAR_GLASS_STYLE = 1;
    private static final int MAX_REGIONS = 12;
    private static final int MAX_SURFACES = 500;
    private static final int MAX_SCANNED_VIEWS = 8192;
    private static final int MAX_BROWSER_VIEWS = 64;
    private static final int MAX_BROWSER_DEPTH = 6;
    private static final Map<String, String> BACKGROUND_BOOLEANS = Map.of(
        "drawsBackground", "setDrawsBackground:",
        "usesAlternatingRowBackgroundColors", "setUsesAlternatingRowBackgroundColors:");
    private static final Set<String> SURFACE_CLASSES = Set.of("NSScrollView", "NSClipView", "NSTextView",
        "NSTableView", "NSOutlineView", "NSTextField", "WebView", "WKWebView");
    private static final long BEHIND_WINDOW = 0;
    private static final long FOLLOWS_WINDOW_STATE = 0;
    private static final long BELOW_EXISTING_VIEWS = -1;
    private static final long WIDTH_AND_HEIGHT_SIZABLE = 18;
    private static final Set<String> SELECTORS = Set.of("alloc", "retain", "release", "sharedWorkspace",
        "accessibilityDisplayShouldReduceTransparency", "respondsToSelector:", "setMaterial:",
        "setBlendingMode:", "setState:", "backgroundColor", "isOpaque", "setStyle:", "setCornerRadius:",
        "isKindOfClass:", "drawsBackground", "setDrawsBackground:", "contentView", "documentView", "superview",
        "setBackgroundColor:", "usesAlternatingRowBackgroundColors", "setUsesAlternatingRowBackgroundColors:",
        "underPageBackgroundColor", "setUnderPageBackgroundColor:", "subviews", "count", "objectAtIndex:");
    private static final Map<Shell, Target> TARGETS = new IdentityHashMap<>();
    private static final Map<Control, Region> REGIONS = new IdentityHashMap<>();
    private static final Map<Long, Surface> SURFACES = new LinkedHashMap<>();
    private static final Map<Control, DisposeListener> SURFACE_OWNERS = new IdentityHashMap<>();
    private static int scannedViews;
    private static final AtomicLong GENERATION = new AtomicLong();
    private static long installedGeneration;
    private static volatile Display installedDisplay;
    private static Listener displayDispose;
    private static Runnable timer;
    private static Bridge bridge;
    private static boolean bridgeFailed;
    private static Boolean reduceTransparency;

    @FunctionalInterface
    interface ViewChildren {
        @NotNull
        List<Long> children(long view) throws ReflectiveOperationException;
    }

    /** Only view identities are inspected; no DOM, text, URLs or document content are read. */
    @NotNull
    static List<Long> boundedBrowserViews(long root, @NotNull ViewChildren children) throws ReflectiveOperationException {
        if (root == 0) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        queue.add(new long[] {root, 0});
        seen.add(root);
        while (!queue.isEmpty() && result.size() < MAX_BROWSER_VIEWS) {
            long[] next = queue.removeFirst();
            result.add(next[0]);
            if (next[1] >= MAX_BROWSER_DEPTH) {
                continue;
            }
            List<Long> descendants = children.children(next[0]);
            int inspected = 0;
            for (Long child : descendants) {
                if (++inspected > MAX_BROWSER_VIEWS || seen.size() >= MAX_BROWSER_VIEWS) {
                    break;
                }
                if (child != null && child.longValue() != 0 && seen.add(child)) {
                    queue.addLast(new long[] {child.longValue(), next[1] + 1});
                }
            }
        }
        return List.copyOf(result);
    }

    private NativeWorkbenchMaterial() {
    }

    /** The pure platform predicate does not load Cocoa or create a display. */
    public static boolean supportedPlatform(@Nullable String platform) {
        return "cocoa".equals(platform);
    }

    static boolean materialAllowed(@Nullable String platform, @Nullable Boolean reducedTransparency) {
        return supportedPlatform(platform) && Boolean.FALSE.equals(reducedTransparency);
    }

    static boolean selectorAllowed(@Nullable String selector) {
        return selector != null && SELECTORS.contains(selector);
    }

    static boolean surfaceClassAllowed(@Nullable String name) {
        return name != null && SURFACE_CLASSES.contains(name);
    }

    public static boolean isApplied(@Nullable Shell shell) {
        if (shell == null || shell.isDisposed() || Display.getCurrent() == null
            || installedGeneration != GENERATION.get()) {
            return false;
        }
        Target target = TARGETS.get(shell);
        return target != null && target.layer != null;
    }

    @NotNull
    public static Map<String, Object> apply(@NotNull Shell shell) {
        String platform = SWT.getPlatform();
        if (!supportedPlatform(platform)) {
            return status(platform, false, false, "native-material-unavailable-on-this-platform");
        }
        if (shell.isDisposed() || Display.getCurrent() == null || Display.getCurrent() != shell.getDisplay()) {
            return status(platform, false, false, "native-material-requires-live-display-thread");
        }
        if (installedDisplay != null && installedGeneration != GENERATION.get()) {
            resetNow(GENERATION.get());
        }
        try {
            if (bridge == null && !bridgeFailed) {
                bridge = new Bridge();
            }
            if (bridge == null) {
                return status(platform, false, false, "public-cocoa-material-bridge-unavailable");
            }
            Target target = TARGETS.get(shell);
            if (target != null && target.layer != null) {
                return status(platform, true, true, "already-applied");
            }
            if (target == null && TARGETS.size() >= MAX_SHELLS) {
                return status(platform, true, false, "native-material-window-limit");
            }
            ensureDisplay(shell.getDisplay());
            final long epoch = installedGeneration;
            reduceTransparency = bridge.reduceTransparency();
            if (target == null) {
                target = new Target(shell);
                TARGETS.put(shell, target);
                shell.addDisposeListener(target.dispose);
            }
            if (!materialAllowed(platform, reduceTransparency)) {
                releaseLayers();
                return status(platform, reduceTransparency != null, false,
                    reduceTransparency == null ? "reduce-transparency-setting-unavailable" : "reduce-transparency-enabled");
            }
            target.layer = bridge.create(shell);
            if (epoch != GENERATION.get()) {
                bridge.release(target.layer);
                target.layer = null;
                return status(platform, true, false, "native-material-operation-canceled");
            }
            return status(platform, true, true, "native-material-applied");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            if (bridge == null) {
                bridgeFailed = true;
            }
            return status(platform, false, false, "native-material-could-not-be-applied");
        }
    }

    /** Clears public AppKit default backgrounds inside one SWT widget, without changing cell or text foregrounds. */
    public static boolean prepareControl(@Nullable Control control) {
        if (control == null || control.isDisposed() || Display.getCurrent() == null || bridge == null
            || !isApplied(control.getShell())) {
            return false;
        }
        boolean changed = false;
        try {
            int examined = 0;
            for (long view : bridge.relatedViews(control)) {
                if (++examined > MAX_SCANNED_VIEWS) {
                    break;
                }
                scannedViews = Math.min(MAX_SCANNED_VIEWS, scannedViews + 1);
                Surface existing = SURFACES.get(view);
                if (existing != null) {
                    bridge.applySurface(existing);
                    changed = true;
                    continue;
                }
                if (SURFACES.size() >= MAX_SURFACES) {
                    continue;
                }
                Surface surface = bridge.clearSurface(control, view);
                if (surface != null) {
                    SURFACES.put(view, surface);
                    changed = true;
                }
            }
            if (changed && !SURFACE_OWNERS.containsKey(control)) {
                DisposeListener dispose = event -> restoreControl(control);
                SURFACE_OWNERS.put(control, dispose);
                control.addDisposeListener(dispose);
            }
            return changed;
        } catch (ReflectiveOperationException | RuntimeException e) {
            restoreControl(control);
            return false;
        }
    }

    /** Material is confined to the tab client area, so headers and existing controls remain above it. */
    @NotNull
    public static Map<String, Object> applyRegion(@Nullable Control control) {
        String platform = SWT.getPlatform();
        if (!(control instanceof CTabFolder folder) || control.isDisposed() || Display.getCurrent() == null
            || bridge == null || !isApplied(control.getShell())) {
            return status(platform, false, false, "region-requires-live-native-tab-folder");
        }
        int depth = 0;
        for (Control parent = folder.getParent(); parent != null && depth < 64; parent = parent.getParent(), depth++) {
            if (parent instanceof CTabFolder) {
                return status(platform, true, false, "nested-material-region-not-needed");
            }
        }
        Rectangle area = folder.getClientArea();
        if (area.width <= 0 || area.height <= 0) {
            return status(platform, true, false, "region-not-yet-laid-out");
        }
        try {
            Region existing = REGIONS.get(control);
            if (existing != null) {
                refreshRegion(control);
                boolean retained = REGIONS.containsKey(control);
                return status(platform, true, retained, retained ? "native-region-already-applied"
                    : "native-region-could-not-refresh");
            }
            if (REGIONS.size() >= MAX_REGIONS) {
                return status(platform, true, false, "native-material-region-limit");
            }
            Object view = bridge.createRegion(folder, area);
            Region region = new Region(folder, view, area);
            REGIONS.put(folder, region);
            folder.addDisposeListener(region.dispose);
            folder.addListener(SWT.Resize, region.resize);
            return status(platform, true, true, "native-tab-client-material-applied");
        } catch (ReflectiveOperationException | RuntimeException e) {
            removeRegion(control);
            return status(platform, false, false, "native-region-could-not-be-applied");
        }
    }

    private static void refreshRegion(@NotNull Control control) {
        Region region = REGIONS.get(control);
        if (region == null || bridge == null || control.isDisposed()) {
            return;
        }
        Rectangle area = ((CTabFolder) control).getClientArea();
        if (!area.equals(region.area)) {
            try {
                bridge.frame(region.view, area);
                region.area = area;
            } catch (ReflectiveOperationException | RuntimeException e) {
                removeRegion(control);
            }
        }
    }

    private static void removeRegion(@NotNull Control control) {
        Region region = REGIONS.remove(control);
        if (region != null) {
            if (!control.isDisposed()) {
                control.removeDisposeListener(region.dispose);
                control.removeListener(SWT.Resize, region.resize);
            }
            if (bridge != null) {
                bridge.releaseRegion(region.view);
            }
        }
    }

    private static void restoreControl(@NotNull Control control) {
        DisposeListener listener = SURFACE_OWNERS.remove(control);
        if (listener != null && !control.isDisposed()) {
            control.removeDisposeListener(listener);
        }
        for (Surface surface : new ArrayList<>(SURFACES.values())) {
            if (surface.owner() == control) {
                SURFACES.remove(surface.id());
                if (bridge != null) {
                    bridge.restoreSurface(surface);
                }
            }
        }
    }

    /** Restores native backgrounds and releases only objects retained by this addon. */
    public static void reset() {
        long expected = GENERATION.incrementAndGet();
        Display display = installedDisplay;
        if (display == null) {
            return;
        }
        if (Display.getCurrent() == display) {
            resetNow(expected);
        } else if (!display.isDisposed()) {
            display.asyncExec(() -> resetNow(expected));
        }
    }

    public static void stop() {
        reset();
    }

    private static void ensureDisplay(@NotNull Display display) {
        if (installedDisplay == null) {
            installedGeneration = GENERATION.incrementAndGet();
            installedDisplay = display;
            final long epoch = installedGeneration;
            displayDispose = event -> {
                GENERATION.incrementAndGet();
                resetNow(GENERATION.get());
            };
            display.addListener(SWT.Dispose, displayDispose);
            timer = () -> poll(epoch);
            display.timerExec(RECHECK_MILLISECONDS, timer);
        }
    }

    private static void poll(long epoch) {
        Display display = installedDisplay;
        if (display == null || display.isDisposed() || epoch != GENERATION.get()) {
            return;
        }
        try {
            scannedViews = 0;
            if (bridge != null) {
                reduceTransparency = bridge.reduceTransparency();
                if (!materialAllowed(SWT.getPlatform(), reduceTransparency)) {
                    releaseLayers();
                } else {
                    for (Target target : new ArrayList<>(TARGETS.values())) {
                        if (target.shell.isDisposed()) {
                            remove(target.shell);
                        } else if (target.layer == null) {
                            target.layer = bridge.create(target.shell);
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            releaseLayers();
        } finally {
            if (installedDisplay == display && epoch == GENERATION.get() && timer != null) {
                display.timerExec(RECHECK_MILLISECONDS, timer);
            }
        }
    }

    private static void resetNow(long expected) {
        if (expected != GENERATION.get()) {
            return;
        }
        Display display = installedDisplay;
        if (display != null && !display.isDisposed()) {
            if (timer != null) {
                display.timerExec(-1, timer);
            }
            if (displayDispose != null) {
                display.removeListener(SWT.Dispose, displayDispose);
            }
        }
        releaseLayers();
        for (Target target : new ArrayList<>(TARGETS.values())) {
            remove(target.shell);
        }
        installedDisplay = null;
        displayDispose = null;
        timer = null;
        reduceTransparency = null;
    }

    private static void releaseLayers() {
        for (Control control : new ArrayList<>(REGIONS.keySet())) {
            removeRegion(control);
        }
        for (Control control : new ArrayList<>(SURFACE_OWNERS.keySet())) {
            restoreControl(control);
        }
        if (bridge != null) {
            for (Target target : TARGETS.values()) {
                if (target.layer != null) {
                    bridge.release(target.layer);
                    target.layer = null;
                }
            }
        }
    }

    private static void remove(@NotNull Shell shell) {
        for (Region region : new ArrayList<>(REGIONS.values())) {
            if (region.shell == shell) {
                removeRegion(region.owner);
            }
        }
        for (Surface surface : new ArrayList<>(SURFACES.values())) {
            if (surface.shell() == shell) {
                restoreControl(surface.owner());
            }
        }
        Target target = TARGETS.remove(shell);
        if (target == null) {
            return;
        }
        if (!shell.isDisposed()) {
            shell.removeDisposeListener(target.dispose);
        }
        if (bridge != null && target.layer != null) {
            bridge.release(target.layer);
            target.layer = null;
        }
    }

    @NotNull
    private static Map<String, Object> status(@NotNull String platform, boolean supported, boolean applied,
        @NotNull String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platform", platform);
        result.put("supported", supported);
        result.put("applied", applied);
        result.put("material", bridge == null ? "unavailable" : bridge.modern
            ? "NSGlassEffectView.clear" : "NSVisualEffectView.hudWindow");
        result.put("reason", reason);
        result.put("shellAlphaChanged", false);
        result.put("reduceTransparency", reduceTransparency == null ? "unknown" : reduceTransparency);
        result.put("scope", "native underlays and restored AppKit default background surfaces");
        result.put("windows", TARGETS.size());
        result.put("regions", REGIONS.size());
        result.put("nativeBackgroundViews", SURFACES.size());
        Map<String, Integer> kinds = new LinkedHashMap<>();
        int drawingStillEnabled = 0;
        int drawingDisabled = 0;
        int observationFailures = 0;
        for (Surface surface : SURFACES.values()) {
            kinds.merge(surface.kind(), 1, Integer::sum);
            if (bridge != null && surface.booleans().containsKey("drawsBackground")) {
                try {
                    if (bridge.drawingBackground(surface)) {
                        drawingStillEnabled++;
                    } else {
                        drawingDisabled++;
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    observationFailures++;
                }
            }
        }
        result.put("retainedSurfaceKinds", Map.copyOf(kinds));
        result.put("observedDrawsBackgroundTrue", drawingStillEnabled);
        result.put("observedDrawsBackgroundFalse", drawingDisabled);
        result.put("backgroundObservationFailures", observationFailures);
        result.put("ownerBackgroundAlpha", ownerAlphaSummary(SURFACE_OWNERS.keySet()));
        return Map.copyOf(result);
    }

    @NotNull
    static Map<String, Integer> alphaSummary(@NotNull List<Integer> alphas) {
        int transparent = 0;
        int translucent = 0;
        int opaque = 0;
        for (int alpha : alphas) {
            if (alpha == 0) {
                transparent++;
            } else if (alpha > 0 && alpha < 255) {
                translucent++;
            } else if (alpha == 255) {
                opaque++;
            }
        }
        return Map.of("transparent", transparent, "translucent", translucent, "opaque", opaque);
    }

    @NotNull
    private static Map<String, Object> ownerAlphaSummary(@NotNull Set<Control> controls) {
        List<Integer> nativeAlphas = new ArrayList<>();
        List<Integer> editorAlphas = new ArrayList<>();
        for (Control control : controls) {
            if (!control.isDisposed()) {
                int alpha = control.getBackground().getAlpha();
                nativeAlphas.add(alpha);
                if (control instanceof StyledText) {
                    editorAlphas.add(alpha);
                }
            }
        }
        return Map.of("allRetainedOwners", alphaSummary(nativeAlphas), "styledTextOwners", alphaSummary(editorAlphas));
    }

    private static final class Target {
        private final Shell shell;
        private final DisposeListener dispose;
        private Layer layer;

        private Target(@NotNull Shell shell) {
            this.shell = shell;
            dispose = event -> remove(shell);
        }
    }

    private static final class Region {
        private final Control owner;
        private final Shell shell;
        private final Object view;
        private final DisposeListener dispose;
        private final Listener resize;
        private Rectangle area;

        private Region(@NotNull CTabFolder owner, @NotNull Object view, @NotNull Rectangle area) {
            this.owner = owner;
            shell = owner.getShell();
            this.view = view;
            this.area = area;
            dispose = event -> removeRegion(owner);
            resize = event -> refreshRegion(owner);
        }
    }

    private record Surface(long id, long originalColor, boolean hasColor, @NotNull String colorSetter,
        @NotNull String kind, @NotNull Map<String, Boolean> booleans, @NotNull Control owner, @NotNull Shell shell) {
    }

    private record Layer(@Nullable Object view, @NotNull Object window, @NotNull Object background, boolean opaque) {
    }

    /** Reflection is limited to public SWT Cocoa wrappers; no user-supplied class or selector is accepted. */
    private static final class Bridge {
        private final Class<?> viewClass;
        private final Class<?> rectangleClass;
        private final Class<?> colorClass;
        private final Class<?> windowClass;
        private final Method lookupClass;
        private final Method lookupSelector;
        private final Method send;
        private final Method sendArgument;
        private final Method sendBoolean;
        private final Method sendBooleanArgument;
        private final Field viewField;
        private final Field idField;
        private final long effectClass;
        private final long workspace;
        private final boolean modern;
        private final Map<String, Long> surfaceClasses = new LinkedHashMap<>();
        private final Method sendBooleanValue;
        private final Method sendDouble;

        private Bridge() throws ReflectiveOperationException {
            ClassLoader loader = Control.class.getClassLoader();
            final Class<?> os = Class.forName("org.eclipse.swt.internal.cocoa.OS", true, loader);
            viewClass = Class.forName("org.eclipse.swt.internal.cocoa.NSView", true, loader);
            rectangleClass = Class.forName("org.eclipse.swt.internal.cocoa.NSRect", true, loader);
            colorClass = Class.forName("org.eclipse.swt.internal.cocoa.NSColor", true, loader);
            windowClass = Class.forName("org.eclipse.swt.internal.cocoa.NSWindow", true, loader);
            lookupClass = os.getMethod("objc_getClass", String.class);
            lookupSelector = os.getMethod("sel_registerName", String.class);
            send = os.getMethod("objc_msgSend", long.class, long.class);
            sendArgument = os.getMethod("objc_msgSend", long.class, long.class, long.class);
            sendBoolean = os.getMethod("objc_msgSend_bool", long.class, long.class);
            sendBooleanArgument = os.getMethod("objc_msgSend_bool", long.class, long.class, long.class);
            sendBooleanValue = os.getMethod("objc_msgSend", long.class, long.class, boolean.class);
            sendDouble = os.getMethod("objc_msgSend", long.class, long.class, double.class);
            viewField = Control.class.getField("view");
            idField = viewClass.getField("id");
            long glassClass = ((Number) lookupClass.invoke(null, "NSGlassEffectView")).longValue();
            modern = glassClass != 0;
            effectClass = modern ? glassClass : ((Number) lookupClass.invoke(null, "NSVisualEffectView")).longValue();
            for (String name : SURFACE_CLASSES) {
                surfaceClasses.put(name, ((Number) lookupClass.invoke(null, name)).longValue());
            }
            long workspaceClass = ((Number) lookupClass.invoke(null, "NSWorkspace")).longValue();
            workspace = message(workspaceClass, "sharedWorkspace");
            if (effectClass == 0 || workspace == 0) {
                throw new IllegalStateException("Public AppKit material classes are unavailable");
            }
        }

        @Nullable
        private Boolean reduceTransparency() throws ReflectiveOperationException {
            long preference = selector("accessibilityDisplayShouldReduceTransparency");
            if (!((Boolean) sendBooleanArgument.invoke(null, workspace, selector("respondsToSelector:"), preference))) {
                return null;
            }
            return (Boolean) sendBoolean.invoke(null, workspace, preference);
        }

        @NotNull
        private Layer create(@NotNull Shell shell) throws ReflectiveOperationException {
            Object parent = viewField.get(shell);
            Object window = viewClass.getMethod("window").invoke(parent);
            if (parent == null || window == null) {
                throw new IllegalStateException("Native shell content is unavailable");
            }
            long backgroundId = message(id(window), "backgroundColor");
            if (backgroundId == 0) {
                throw new IllegalStateException("Native window background is unavailable");
            }
            Object background = colorClass.getConstructor(long.class).newInstance(backgroundId);
            boolean opaque = (Boolean) sendBoolean.invoke(null, id(window), selector("isOpaque"));
            message(id(window), "retain");
            message(id(background), "retain");
            Object effect = null;
            try {
                long allocated = message(effectClass, "alloc");
                if (allocated == 0) {
                    throw new IllegalStateException("Native material allocation failed");
                }
                effect = viewClass.getConstructor(long.class).newInstance(allocated);
                Object bounds = viewClass.getMethod("bounds").invoke(parent);
                effect = viewClass.getMethod("initWithFrame", rectangleClass).invoke(effect, bounds);
                if (effect == null) {
                    throw new IllegalStateException("Native material initialization failed");
                }
                configure(effect);
                viewClass.getMethod("setAutoresizingMask", long.class).invoke(effect, WIDTH_AND_HEIGHT_SIZABLE);
                // Backmost native child: existing SWT controls remain above the material and retain input ownership.
                viewClass.getMethod("addSubview", viewClass, long.class, viewClass)
                    .invoke(parent, effect, BELOW_EXISTING_VIEWS, null);
                windowClass.getMethod("setOpaque", boolean.class).invoke(window, false);
                Object clear = colorClass.getMethod("clearColor").invoke(null);
                windowClass.getMethod("setBackgroundColor", colorClass).invoke(window, clear);
                return new Layer(effect, window, background, opaque);
            } catch (ReflectiveOperationException | RuntimeException e) {
                release(new Layer(effect, window, background, opaque));
                throw e;
            }
        }

        private void configure(@NotNull Object effect) throws ReflectiveOperationException {
            if (modern) {
                set(id(effect), "setStyle:", CLEAR_GLASS_STYLE);
            } else {
                set(id(effect), "setMaterial:", HUD_MATERIAL);
                set(id(effect), "setBlendingMode:", BEHIND_WINDOW);
                set(id(effect), "setState:", FOLLOWS_WINDOW_STATE);
            }
        }

        @NotNull
        private Object createRegion(@NotNull CTabFolder owner, @NotNull Rectangle area)
                throws ReflectiveOperationException {
            Object parent = viewField.get(owner);
            if (parent == null) {
                throw new IllegalStateException("Native tab content is unavailable");
            }
            Object effect = null;
            try {
                long allocated = message(effectClass, "alloc");
                if (allocated == 0) {
                    throw new IllegalStateException("Native material allocation failed");
                }
                effect = viewClass.getConstructor(long.class).newInstance(allocated);
                effect = viewClass.getMethod("initWithFrame", rectangleClass).invoke(effect, rectangle(area));
                if (effect == null) {
                    throw new IllegalStateException("Native material initialization failed");
                }
                configure(effect);
                if (responds(id(effect), "setCornerRadius:")) {
                    sendDouble.invoke(null, id(effect), selector("setCornerRadius:"), 12.0);
                }
                viewClass.getMethod("setAutoresizingMask", long.class).invoke(effect, WIDTH_AND_HEIGHT_SIZABLE);
                viewClass.getMethod("addSubview", viewClass, long.class, viewClass)
                    .invoke(parent, effect, BELOW_EXISTING_VIEWS, null);
                return effect;
            } catch (ReflectiveOperationException | RuntimeException e) {
                if (effect != null) {
                    releaseRegion(effect);
                }
                throw e;
            }
        }

        @NotNull
        private Object rectangle(@NotNull Rectangle area) throws ReflectiveOperationException {
            Object rectangle = rectangleClass.getConstructor().newInstance();
            rectangleClass.getField("x").setDouble(rectangle, area.x);
            rectangleClass.getField("y").setDouble(rectangle, area.y);
            rectangleClass.getField("width").setDouble(rectangle, area.width);
            rectangleClass.getField("height").setDouble(rectangle, area.height);
            return rectangle;
        }

        private void frame(@NotNull Object view, @NotNull Rectangle area) throws ReflectiveOperationException {
            viewClass.getMethod("setFrame", rectangleClass).invoke(view, rectangle(area));
        }

        private void releaseRegion(@NotNull Object view) {
            try {
                viewClass.getMethod("removeFromSuperview").invoke(view);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // The owner may already have detached the underlay during disposal.
            } finally {
                releaseObject(view);
            }
        }

        @NotNull
        private List<Long> relatedViews(@NotNull Control owner) throws ReflectiveOperationException {
            Object view = viewField.get(owner);
            if (view == null) {
                return List.of();
            }
            long current = id(view);
            if (owner instanceof Browser) {
                return boundedBrowserViews(current, this::children);
            }
            Control parent = owner.getParent();
            Object parentView = parent == null ? null : viewField.get(parent);
            long stop = parentView == null ? 0 : id(parentView);
            List<Long> result = new ArrayList<>();
            // The public superview chain reaches the clip/scroll wrappers without private SWT fields.
            for (int depth = 0; current != 0 && current != stop && depth < 4; depth++) {
                result.add(current);
                if (owner instanceof Shell) {
                    break;
                }
                current = message(current, "superview");
            }
            return result;
        }

        @NotNull
        private List<Long> children(long view) throws ReflectiveOperationException {
            long array = message(view, "subviews");
            long count = array == 0 ? 0 : message(array, "count");
            List<Long> children = new ArrayList<>();
            for (long index = 0; index < Math.min(count, MAX_BROWSER_VIEWS); index++) {
                long child = ((Number) sendArgument.invoke(null, array, selector("objectAtIndex:"), index)).longValue();
                children.add(child);
            }
            return children;
        }

        private boolean isSurface(long view) throws ReflectiveOperationException {
            for (long type : surfaceClasses.values()) {
                if (type != 0 && (Boolean) sendBooleanArgument.invoke(null, view, selector("isKindOfClass:"), type)) {
                    return true;
                }
            }
            return false;
        }

        private boolean kind(long view, @NotNull String name) throws ReflectiveOperationException {
            Long type = surfaceClasses.get(name);
            return type != null && type.longValue() != 0
                && (Boolean) sendBooleanArgument.invoke(null, view, selector("isKindOfClass:"), type.longValue());
        }

        private boolean responds(long receiver, @NotNull String name) throws ReflectiveOperationException {
            return (Boolean) sendBooleanArgument.invoke(null, receiver, selector("respondsToSelector:"), selector(name));
        }

        @Nullable
        private Surface clearSurface(@NotNull Control owner, long view) throws ReflectiveOperationException {
            if (!isSurface(view)) {
                return null;
            }
            boolean web = kind(view, "WKWebView");
            boolean table = kind(view, "NSTableView") || kind(view, "NSOutlineView");
            boolean drawing = kind(view, "NSScrollView") || kind(view, "NSClipView")
                || kind(view, "NSTextView") || kind(view, "NSTextField") || kind(view, "WebView");
            Map<String, Boolean> original = new LinkedHashMap<>();
            for (var property : BACKGROUND_BOOLEANS.entrySet()) {
                // Legacy WebView drawsBackground is public. WKWebView only exposes the under-page color publicly.
                if (web || property.getKey().equals("drawsBackground") && !drawing
                    || property.getKey().equals("usesAlternatingRowBackgroundColors") && !table) {
                    continue;
                }
                if (responds(view, property.getKey()) && responds(view, property.getValue())) {
                    original.put(property.getKey(), (Boolean) sendBoolean.invoke(null, view, selector(property.getKey())));
                }
            }
            String colorGetter = web ? "underPageBackgroundColor" : "backgroundColor";
            String colorSetter = web ? "setUnderPageBackgroundColor:" : "setBackgroundColor:";
            boolean hasColor = responds(view, colorGetter) && responds(view, colorSetter);
            if (!hasColor && original.isEmpty()) {
                return null;
            }
            long originalColor = hasColor ? message(view, colorGetter) : 0;
            Surface surface = new Surface(view, originalColor, hasColor, colorSetter, surfaceKind(view),
                Map.copyOf(original), owner, owner.getShell());
            message(view, "retain");
            if (originalColor != 0) {
                message(originalColor, "retain");
            }
            try {
                applySurface(surface);
                return surface;
            } catch (ReflectiveOperationException | RuntimeException e) {
                restoreSurface(surface);
                throw e;
            }
        }

        @NotNull
        private String surfaceKind(long view) throws ReflectiveOperationException {
            // Most-specific browser types first; output never includes arbitrary native class names.
            for (String type : List.of("WKWebView", "WebView", "NSOutlineView", "NSTableView", "NSTextView",
                "NSTextField", "NSScrollView", "NSClipView")) {
                if (kind(view, type)) {
                    return type;
                }
            }
            return "unknown";
        }

        private boolean drawingBackground(@NotNull Surface surface) throws ReflectiveOperationException {
            return (Boolean) sendBoolean.invoke(null, surface.id(), selector("drawsBackground"));
        }

        /** Reassert the material after SWT background updates, without recapturing or retaining original state. */
        private void applySurface(@NotNull Surface surface) throws ReflectiveOperationException {
            for (String property : surface.booleans().keySet()) {
                sendBooleanValue.invoke(null, surface.id(), selector(BACKGROUND_BOOLEANS.get(property)), false);
            }
            if (surface.hasColor()) {
                Object clear = colorClass.getMethod("clearColor").invoke(null);
                set(surface.id(), surface.colorSetter(), id(clear));
            }
        }

        private void restoreSurface(@NotNull Surface surface) {
            try {
                for (var property : surface.booleans().entrySet()) {
                    sendBooleanValue.invoke(null, surface.id(), selector(BACKGROUND_BOOLEANS.get(property.getKey())),
                        property.getValue().booleanValue());
                }
                if (surface.hasColor()) {
                    set(surface.id(), surface.colorSetter(), surface.originalColor());
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Retained views may be detached while their owning SWT widget is disposed.
            } finally {
                try {
                    if (surface.originalColor() != 0) {
                        message(surface.originalColor(), "release");
                    }
                    message(surface.id(), "release");
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // Each retained object is released at most once after an unknown native failure.
                }
            }
        }

        private void release(@NotNull Layer layer) {
            try {
                if (layer.view() != null) {
                    viewClass.getMethod("removeFromSuperview").invoke(layer.view());
                }
                windowClass.getMethod("setBackgroundColor", colorClass).invoke(layer.window(), layer.background());
                windowClass.getMethod("setOpaque", boolean.class).invoke(layer.window(), layer.opaque());
            } catch (ReflectiveOperationException | RuntimeException e) {
                // The shell may be closing; retained objects are still released below.
            } finally {
                releaseObject(layer.view());
                releaseObject(layer.background());
                releaseObject(layer.window());
            }
        }

        private void releaseObject(@Nullable Object object) {
            if (object != null) {
                try {
                    message(id(object), "release");
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // Do not issue a second release after an unknown native failure.
                }
            }
        }

        private long id(@NotNull Object object) throws IllegalAccessException {
            return idField.getLong(object);
        }

        private long selector(@NotNull String name) throws ReflectiveOperationException {
            if (!selectorAllowed(name)) {
                throw new IllegalArgumentException("Selector is outside the fixed public AppKit bridge");
            }
            return ((Number) lookupSelector.invoke(null, name)).longValue();
        }

        private long message(long receiver, @NotNull String name) throws ReflectiveOperationException {
            return ((Number) send.invoke(null, receiver, selector(name))).longValue();
        }

        private void set(long receiver, @NotNull String name, long value) throws ReflectiveOperationException {
            sendArgument.invoke(null, receiver, selector(name), value);
        }
    }
}

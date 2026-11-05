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
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolderRenderer;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/** Public e4 renderer extension: only the existing tab-body fill honors its background alpha. */
public final class WorkbenchTabMaterial {
    private static final int MAX_FOLDERS = 128;
    private static final Map<CTabFolder, State> STATES = Collections.synchronizedMap(new IdentityHashMap<>());

    private WorkbenchTabMaterial() {
    }

    @NotNull
    public static Map<String, Object> apply(@Nullable CTabFolder folder, boolean enabled) {
        if (folder == null || folder.isDisposed() || Display.getCurrent() == null
            || Display.getCurrent() != folder.getDisplay()) {
            return status(false, false, 0, "live-tab-folder-required");
        }
        if (enabled && !"cocoa".equals(SWT.getPlatform())) {
            return status(false, false, 0, "native-glass-requires-cocoa");
        }
        CTabFolderRenderer current = folder.getRenderer();
        if (current instanceof AlphaRendering installed) {
            installed.refreshPalette();
            installed.enabled = enabled;
            if (enabled && !track(folder, installed)) {
                installed.enabled = false;
                return status(false, false, 0, "renderer-limit");
            }
            folder.redraw();
            return status(true, enabled, installed.copiedProperties, "body-alpha-updated");
        }
        State stale = STATES.remove(folder);
        if (stale != null) {
            stale.renderer.enabled = false;
            folder.removeDisposeListener(stale.dispose);
        }
        if (!enabled) {
            return status(false, false, 0, "original-renderer-already-active");
        }
        if (!knownRenderer(current.getClass())) {
            return status(false, false, 0, "custom-renderer-preserved");
        }
        if (STATES.size() >= MAX_FOLDERS) {
            return status(false, false, 0, "renderer-limit");
        }
        AlphaRendering renderer = new AlphaRendering(folder);
        // Resources owned by the old renderer may be disposed by setRenderer. Copy only value properties.
        renderer.copiedProperties = copyValueProperties(current, renderer);
        folder.setRenderer(renderer);
        renderer.refreshPalette();
        track(folder, renderer);
        folder.redraw();
        return status(true, true, renderer.copiedProperties, "e4-body-alpha-extension-installed");
    }

    /** Disable in place: SWT disposes replaced renderers, so retaining a disposed original would be unsafe. */
    public static void stop() {
        ArrayList<State> states;
        synchronized (STATES) {
            states = new ArrayList<>(STATES.values());
            STATES.clear();
        }
        for (State state : states) {
            state.renderer.enabled = false;
            Runnable cleanup = () -> {
                if (!state.folder.isDisposed()) {
                    state.renderer.refreshPalette();
                    state.folder.removeDisposeListener(state.dispose);
                    state.folder.redraw();
                }
            };
            if (Display.getCurrent() == state.display) {
                cleanup.run();
            } else if (!state.display.isDisposed()) {
                state.display.asyncExec(cleanup);
            }
        }
    }

    static boolean knownRenderer(@Nullable Class<?> type) {
        return type == CTabRendering.class;
    }

    static int effectiveAlpha(int foreground, int background) {
        if (foreground < 0 || foreground > 255 || background < 0 || background > 255) {
            throw new IllegalArgumentException("GC alpha must be within 0..255");
        }
        return (foreground * background + 127) / 255;
    }

    private static boolean track(@NotNull CTabFolder folder, @NotNull AlphaRendering renderer) {
        synchronized (STATES) {
            if (!STATES.containsKey(folder)) {
                if (STATES.size() >= MAX_FOLDERS) {
                    return false;
                }
                State state = new State(folder, renderer);
                STATES.put(folder, state);
                folder.addDisposeListener(state.dispose);
            }
            return true;
        }
    }

    private static int copyValueProperties(@NotNull CTabFolderRenderer original, @NotNull AlphaRendering replacement) {
        int copied = 0;
        for (Method getter : CTabRendering.class.getMethods()) {
            String name = getter.getName();
            int prefix = name.startsWith("get") ? 3 : name.startsWith("is") ? 2 : 0;
            Class<?> type = getter.getReturnType();
            if (prefix == 0 || name.length() <= prefix || getter.getParameterCount() != 0
                || !(type.isPrimitive() || type == String.class || type.isEnum()) || type == void.class) {
                continue;
            }
            try {
                Method setter = CTabRendering.class.getMethod("set" + name.substring(prefix), type);
                setter.invoke(replacement, getter.invoke(original));
                copied++;
                if (copied >= 64) {
                    break;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Setter-only and non-value style properties remain managed by the existing appearance palette.
            }
        }
        return copied;
    }

    @NotNull
    private static Map<String, Object> status(boolean changed, boolean enabled, int copied, @NotNull String reason) {
        return Map.of("changed", changed, "bodyAlphaEnabled", enabled, "copiedValueProperties", copied,
            "reason", reason, "signedBundleModified", false, "geometry", "existing-e4-renderer");
    }

    private static final class State {
        private final CTabFolder folder;
        private final Display display;
        private final AlphaRendering renderer;
        private final DisposeListener dispose;

        private State(@NotNull CTabFolder folder, @NotNull AlphaRendering renderer) {
            this.folder = folder;
            display = folder.getDisplay();
            this.renderer = renderer;
            dispose = event -> {
                STATES.remove(folder);
                renderer.enabled = false;
            };
        }
    }

    private static final class AlphaRendering extends CTabRendering {
        private volatile boolean enabled = true;
        private int copiedProperties;

        private AlphaRendering(@NotNull CTabFolder parent) {
            super(parent);
        }

        private void refreshPalette() {
            // e4 owns separate fill properties; SWT folder setters do not initialize them.
            setSelectedTabFill(parent.getSelectionBackground());
            setUnselectedTabsColor(parent.getBackground());
        }

        @Override
        protected void draw(int part, int state, @NotNull Rectangle bounds, @NotNull GC gc) {
            if (!enabled || part != PART_BODY) {
                super.draw(part, state, bounds, gc);
                return;
            }
            int saved = gc.getAlpha();
            try {
                gc.setAlpha(effectiveAlpha(saved, parent.getBackground().getAlpha()));
                super.draw(part, state, bounds, gc);
            } finally {
                gc.setAlpha(saved);
            }
        }
    }
}

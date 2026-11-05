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
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DateTime;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Native control geometry and state only; contents, selections and browser DOM are not inspected. */
public final class NativeSceneInspector {
    private static final int MAX_NODES = 120;
    private static final int MAX_DEPTH = 12;
    private static final int MAX_QUEUE = 256;
    private static final Set<String> TITLES = Set.of(
        "Tip of the day", "Tip of the Day", "Preferences", "Show View", "Find Actions",
        "Settings", "Find/Replace", "About DBeaver", "Error", "Warning", "Information", "Confirm");
    private static final Set<String> BUTTONS = Set.of(
        "Close", "Cancel", "Allow", "Decline", "Deny", "Apply", "OK", "Yes", "No",
        "Next", "Next >", "Back", "< Back", "Finish", "Help", "Retry", "Ignore", "Abort",
        "Save", "Don't Save", "Discard", "Open", "Browse...", "Browse…", "Reset", "Restore Defaults");
    private static long sequence;

    private NativeSceneInspector() {
    }

    private record Pending(Control control, String parentId, int depth) { }

    /** Appends metadata to a graph that already contains the workbench root. No UI actions occur. */
    @NotNull
    static Map<String, NativeAction> append(@NotNull WorkbenchSceneGraph.Builder graph, @NotNull Shell mainShell) {
        final Map<String, NativeAction> actions = new LinkedHashMap<>();
        Objects.requireNonNull(graph);
        Display display = Display.getCurrent();
        if (display == null || display.isDisposed()) {
            throw new IllegalStateException("Native scene inspection requires the SWT display thread");
        }
        ArrayDeque<Pending> queue = new ArrayDeque<>();
        for (Shell shell : display.getShells()) {
            if (shell.isDisposed() || !shell.isVisible()) {
                continue;
            }
            if (queue.size() == MAX_QUEUE) {
                graph.markTruncated();
                break;
            }
            queue.addLast(new Pending(shell, "workbench", 0));
        }
        int added = 0;
        while (!queue.isEmpty()) {
            if (added == MAX_NODES) {
                graph.markTruncated();
                break;
            }
            Pending pending = queue.removeFirst();
            Control control = pending.control();
            if (control.isDisposed() || !control.isVisible()) {
                continue;
            }
            String id = "native-" + ++sequence;
            boolean browser = control instanceof Browser;
            boolean input = isInput(control);
            String type = control instanceof Shell ? "shell" : browser ? "browser"
                : control instanceof Button ? "button" : input ? "input" : "control";
            Map<String, Object> properties = properties(control, input, browser);
            if (control instanceof Browser embedded && available(embedded, mainShell)) {
                properties.put("operations", java.util.List.of("browser-back", "browser-forward"));
                properties.put("history", Map.of("backEnabled", embedded.isBackEnabled(),
                    "forwardEnabled", embedded.isForwardEnabled()));
                actions.put(id + ":browser-back", new NativeAction("browser-back",
                    () -> navigateBrowser(embedded, mainShell, true)));
                actions.put(id + ":browser-forward", new NativeAction("browser-forward",
                    () -> navigateBrowser(embedded, mainShell, false)));
            }
            if (focusable(control, mainShell)) {
                properties.put("operation", "focus");
            }
            if (!graph.addNode(id, type, properties)) {
                graph.markTruncated();
                break;
            }
            added++;
            if (focusable(control, mainShell)) {
                actions.put(id, new NativeAction("focus", () -> focusable(control, mainShell)
                    && display.getActiveShell() == mainShell && control.setFocus() && control.isFocusControl()));
            }
            if (control instanceof TabFolder || control instanceof CTabFolder) {
                appendTabs(graph, actions, control, mainShell, id);
            }
            graph.addEdge(pending.parentId(), "contains", id);
            if (control.isFocusControl() || control instanceof Shell && display.getActiveShell() == control) {
                graph.addEdge("workbench", "active", id);
            }
            // A Browser is a native boundary. Never traverse its implementation or web document.
            if (browser || !(control instanceof Composite composite)) {
                continue;
            }
            for (Control child : composite.getChildren()) {
                if (child.isDisposed() || !child.isVisible()) {
                    continue;
                }
                if (pending.depth() == MAX_DEPTH || queue.size() == MAX_QUEUE) {
                    graph.markTruncated();
                    break;
                }
                queue.addLast(new Pending(child, id, pending.depth() + 1));
            }
        }
        return actions;
    }

    record NativeAction(String operation, java.util.function.BooleanSupplier perform) { }

    private static boolean navigateBrowser(@NotNull Browser browser, @NotNull Shell shell, boolean back) {
        if (!available(browser, shell) || browser.getDisplay().getActiveShell() != shell) {
            return false;
        }
        return back ? browser.back() : browser.forward();
    }

    private static boolean focusable(@NotNull Control control, @NotNull Shell shell) {
        return available(control, shell) && isInput(control) && (control.getStyle() & SWT.PASSWORD) == 0;
    }

    private static boolean available(@NotNull Control control, @NotNull Shell shell) {
        return !control.isDisposed() && control.getShell() == shell && control.isVisible() && control.isEnabled();
    }

    private static void appendTabs(@NotNull WorkbenchSceneGraph.Builder graph, @NotNull Map<String, NativeAction> actions,
        @NotNull Control control, @NotNull Shell mainShell, @NotNull String parentId) {
        if (!available(control, mainShell)) {
            return;
        }
        int count = control instanceof TabFolder folder ? folder.getItemCount() : ((CTabFolder) control).getItemCount();
        for (int index = 0; index < Math.min(count, 16); index++) {
            final int target = index;
            var item = control instanceof TabFolder folder ? folder.getItem(index) : ((CTabFolder) control).getItem(index);
            int selection = control instanceof TabFolder folder ? folder.getSelectionIndex()
                : ((CTabFolder) control).getSelectionIndex();
            String id = "native-" + ++sequence;
            if (!graph.addNode(id, "tab", Map.of("index", index, "selected", index == selection,
                "operation", "select-tab"))) {
                graph.markTruncated();
                break;
            }
            graph.addEdge(parentId, "contains", id);
            actions.put(id, new NativeAction("select-tab", () -> {
                if (!available(control, mainShell) || control.getDisplay().getActiveShell() != mainShell
                    || item.isDisposed()) {
                    return false;
                }
                if (control instanceof TabFolder folder && item instanceof TabItem tab) {
                    if (folder.indexOf(tab) != target) {
                        return false;
                    }
                    folder.setSelection(tab);
                } else if (control instanceof CTabFolder folder && item instanceof CTabItem tab) {
                    if (folder.indexOf(tab) != target) {
                        return false;
                    }
                    folder.setSelection(tab);
                }
                Event event = new Event();
                event.item = item;
                control.notifyListeners(SWT.Selection, event);
                return available(control, mainShell) && (control instanceof TabFolder folder
                    ? folder.getSelectionIndex() : ((CTabFolder) control).getSelectionIndex()) == target;
            }));
        }
        if (count > 16) {
            graph.markTruncated();
        }
    }

    private static boolean isInput(@NotNull Control control) {
        return control instanceof Text || control instanceof StyledText || control instanceof Combo
            || control instanceof CCombo || control instanceof Spinner || control instanceof DateTime;
    }

    @NotNull
    private static Map<String, Object> properties(@NotNull Control control, boolean input, boolean browser) {
        Map<String, Object> properties = new LinkedHashMap<>();
        String className = control.getClass().getSimpleName();
        int classEnd = Math.min(className.length(), 128);
        if (classEnd > 0 && Character.isHighSurrogate(className.charAt(classEnd - 1))) {
            classEnd--;
        }
        properties.put("class", className.substring(0, classEnd));
        Rectangle bounds = control.getBounds();
        if (control instanceof Shell shell && shell.getParent() != null) {
            Point origin = shell.getParent().toDisplay(0, 0);
            bounds.x -= origin.x;
            bounds.y -= origin.y;
        }
        properties.put("bounds", Map.of("x", bounds.x, "y", bounds.y, "width", bounds.width, "height", bounds.height));
        properties.put("enabled", control.isEnabled());
        properties.put("visible", control.isVisible());
        properties.put("focused", control.isFocusControl());
        if (input) {
            properties.put("multiline", (control.getStyle() & SWT.MULTI) != 0);
            properties.put("sensitive", (control.getStyle() & SWT.PASSWORD) != 0);
        }
        if (control instanceof Shell shell) {
            properties.put("boundsSpace", shell.getParent() == null ? "display" : "parent");
            String title = known(shell.getText(), TITLES);
            if (title != null) {
                properties.put("title", title);
            }
        } else if (control instanceof Button button) {
            String label = known(button.getText(), BUTTONS);
            if (label != null) {
                properties.put("buttonLabel", label);
            }
        } else if (browser) {
            properties.put("browserDom", "opaque");
        }
        return properties;
    }

    @Nullable
    private static String known(@Nullable String text, @NotNull Set<String> whitelist) {
        if (text == null || text.length() > 64) {
            return null;
        }
        return whitelist.contains(text) ? text : null;
    }
}

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
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DateTime;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

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
    public static void append(WorkbenchSceneGraph.Builder graph) {
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
            if (!graph.addNode(id, type, properties)) {
                graph.markTruncated();
                break;
            }
            added++;
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
    }

    private static boolean isInput(Control control) {
        return control instanceof Text || control instanceof StyledText || control instanceof Combo
            || control instanceof CCombo || control instanceof Spinner || control instanceof DateTime;
    }

    private static Map<String, Object> properties(Control control, boolean input, boolean browser) {
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

    private static String known(String text, Set<String> whitelist) {
        if (text == null || text.length() > 64) {
            return null;
        }
        return whitelist.contains(text) ? text : null;
    }
}

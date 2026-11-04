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
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.IStartup;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.ai.chat.controls.AIChatControl;
import org.jkiss.dbeaver.ui.ai.chat.controls.AIPromptStyledText;
import org.jkiss.dbeaver.ui.ai.chat.controls.PromptComposite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicLong;

/** Enter sends only from the AI prompt; normal editor and IME keys are untouched. */
public final class PromptSubmitStartup implements IStartup {
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final int MAX_HINTS = 64;
    private static final String HINT_KEY = "org.jkiss.dbeaver.ui.ai.workbench.promptHint";
    private static final Map<AIPromptStyledText, HintState> HINTS = new LinkedHashMap<>();
    private static volatile Display installedDisplay;
    private static long installedGeneration;
    private static Listener keyFilter;
    private static Listener focusFilter;

    @Override
    public void earlyStartup() {
        long generation = GENERATION.get();
        UIUtils.asyncExec(() -> {
            if (generation != GENERATION.get()
                || installedDisplay != null && installedGeneration == generation) {
                return;
            }
            if (installedDisplay != null) {
                removeHooks(installedDisplay);
            }
            Display display = UIUtils.getDisplay();
            if (display.isDisposed()) {
                return;
            }
            keyFilter = PromptSubmitStartup::keyDown;
            focusFilter = event -> {
                if (event.widget instanceof AIPromptStyledText prompt) {
                    installHint(prompt);
                }
            };
            display.addFilter(SWT.KeyDown, keyFilter);
            display.addFilter(SWT.FocusIn, focusFilter);
            installedGeneration = generation;
            installedDisplay = display;
            for (var shell : display.getShells()) {
                installExistingHints(shell, 0);
            }
            // Stop can arrive while startup is traversing controls on the display thread.
            if (generation != GENERATION.get()) {
                removeHooks(display);
            }
        });
    }

    /** Detaches only this lifecycle's hooks; does not initialize an unused UI. */
    public static void uninstall() {
        long generation = GENERATION.incrementAndGet();
        Display display = installedDisplay;
        if (display == null) {
            return;
        }
        if (display.isDisposed()) {
            removeHooks(display);
            return;
        }
        try {
            display.asyncExec(() -> {
                if (installedDisplay == display && installedGeneration < generation) {
                    removeHooks(display);
                }
            });
        } catch (org.eclipse.swt.SWTException e) {
            if (!display.isDisposed()) {
                throw e;
            }
            removeHooks(display);
        }
    }

    private static void removeHooks(@NotNull Display display) {
        if (installedDisplay != display) {
            return;
        }
        if (!display.isDisposed()) {
            if (keyFilter != null) {
                display.removeFilter(SWT.KeyDown, keyFilter);
            }
            if (focusFilter != null) {
                display.removeFilter(SWT.FocusIn, focusFilter);
            }
            for (var entry : new ArrayList<>(HINTS.entrySet())) {
                AIPromptStyledText prompt = entry.getKey();
                HintState hint = entry.getValue();
                if (!prompt.isDisposed()) {
                    prompt.removePaintListener(hint.paint());
                    prompt.removeDisposeListener(hint.dispose());
                    prompt.setToolTipText(hint.previousTooltip());
                    prompt.setData(HINT_KEY, null);
                    prompt.redraw();
                }
            }
        }
        HINTS.clear();
        keyFilter = null;
        focusFilter = null;
        installedDisplay = null;
    }

    private static void keyDown(@NotNull Event event) {
        if (!(event.widget instanceof AIPromptStyledText prompt) || !prompt.isFocusControl()) {
            return;
        }
        boolean composing = prompt.getIME() != null && prompt.getIME().getCompositionOffset() >= 0;
        if (!shouldSubmit(event.keyCode, event.stateMask, composing)) {
            return;
        }
        event.doit = false;
        PromptComposite owner = UIUtils.getParentOfType(prompt, PromptComposite.class);
        AIChatControl chat = UIUtils.getParentOfType(prompt, AIChatControl.class);
        if (owner != null && chat != null && !chat.getChatSession().isBusy()
            && (!prompt.getText().isBlank() || !owner.getImages().isEmpty())) {
            owner.submitPrompt();
        }
    }

    static boolean shouldSubmit(int keyCode, int modifiers, boolean composing) {
        return !composing && modifiers == 0 && (keyCode == SWT.CR || keyCode == SWT.KEYPAD_CR);
    }

    private static void installExistingHints(@NotNull Composite parent, int depth) {
        if (depth > 20 || HINTS.size() >= MAX_HINTS) {
            return;
        }
        for (Control child : parent.getChildren()) {
            if (child instanceof AIPromptStyledText prompt) {
                installHint(prompt);
            } else if (child instanceof Composite composite) {
                installExistingHints(composite, depth + 1);
            }
        }
    }

    private static void installHint(@NotNull AIPromptStyledText prompt) {
        if (prompt.isDisposed() || prompt.getData(HINT_KEY) != null || HINTS.size() >= MAX_HINTS) {
            return;
        }
        String hint = ResourceBundle.getBundle("org.jkiss.dbeaver.ui.ai.workbench.messages").getString("prompt.hint");
        String previousTooltip = prompt.getToolTipText();
        prompt.setData(HINT_KEY, Boolean.TRUE);
        prompt.setToolTipText(hint);
        PaintListener paint = event -> {
            if (prompt.getCharCount() == 0 && !prompt.isFocusControl()) {
                // Replace the original empty hint without changing the editor or its listeners.
                event.gc.setBackground(prompt.getBackground());
                event.gc.fillRectangle(prompt.getClientArea());
                event.gc.setFont(prompt.getFont());
                event.gc.setForeground(prompt.getDisplay().getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
                int top = Math.max(0, (prompt.getClientArea().height - event.gc.textExtent(hint).y) / 2);
                event.gc.drawText(hint, 4, top, true);
            }
        };
        DisposeListener dispose = event -> HINTS.remove((AIPromptStyledText) event.widget);
        HINTS.put(prompt, new HintState(paint, dispose, previousTooltip));
        prompt.addPaintListener(paint);
        prompt.addDisposeListener(dispose);
        prompt.redraw();
    }

    private record HintState(PaintListener paint, DisposeListener dispose, String previousTooltip) { }
}

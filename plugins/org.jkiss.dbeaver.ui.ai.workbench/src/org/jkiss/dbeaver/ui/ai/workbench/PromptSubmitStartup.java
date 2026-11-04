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
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IStartup;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.ai.chat.controls.AIChatControl;
import org.jkiss.dbeaver.ui.ai.chat.controls.AIPromptStyledText;
import org.jkiss.dbeaver.ui.ai.chat.controls.PromptComposite;

import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicBoolean;

/** Enter sends only from the AI prompt; normal editor and IME keys are untouched. */
public final class PromptSubmitStartup implements IStartup {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final String HINT_KEY = "org.jkiss.dbeaver.ui.ai.workbench.promptHint";

    @Override
    public void earlyStartup() {
        UIUtils.asyncExec(() -> {
            if (!INSTALLED.compareAndSet(false, true)) {
                return;
            }
            Display display = UIUtils.getDisplay();
            display.addFilter(SWT.KeyDown, this::keyDown);
            display.addFilter(SWT.FocusIn, event -> {
                if (event.widget instanceof AIPromptStyledText prompt) {
                    installHint(prompt);
                }
            });
            for (var shell : display.getShells()) {
                installExistingHints(shell, 0);
            }
        });
    }

    private void keyDown(Event event) {
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

    private static void installExistingHints(Composite parent, int depth) {
        if (depth > 20) {
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

    private static void installHint(AIPromptStyledText prompt) {
        if (prompt.getData(HINT_KEY) != null) {
            return;
        }
        prompt.setData(HINT_KEY, Boolean.TRUE);
        String hint = ResourceBundle.getBundle("org.jkiss.dbeaver.ui.ai.workbench.messages").getString("prompt.hint");
        prompt.setToolTipText(hint);
        prompt.addPaintListener(event -> {
            if (prompt.getCharCount() == 0 && !prompt.isFocusControl()) {
                // Replace the original empty hint without changing the editor or its listeners.
                event.gc.setBackground(prompt.getBackground());
                event.gc.fillRectangle(prompt.getClientArea());
                event.gc.setFont(prompt.getFont());
                event.gc.setForeground(prompt.getDisplay().getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
                int top = Math.max(0, (prompt.getClientArea().height - event.gc.textExtent(hint).y) / 2);
                event.gc.drawText(hint, 4, top, true);
            }
        });
        prompt.redraw();
    }
}

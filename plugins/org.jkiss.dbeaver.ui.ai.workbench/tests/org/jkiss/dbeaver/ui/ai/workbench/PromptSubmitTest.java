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

/** Key policy requires no display or private prompt content. */
public final class PromptSubmitTest {
    public static void main(String[] args) {
        check(PromptSubmitStartup.shouldSubmit(SWT.CR, 0, false));
        check(PromptSubmitStartup.shouldSubmit(SWT.KEYPAD_CR, 0, false));
        check(!PromptSubmitStartup.shouldSubmit(SWT.CR, SWT.SHIFT, false));
        check(!PromptSubmitStartup.shouldSubmit(SWT.CR, SWT.CTRL, false));
        check(!PromptSubmitStartup.shouldSubmit(SWT.CR, SWT.COMMAND, false));
        check(!PromptSubmitStartup.shouldSubmit(SWT.CR, SWT.ALT, false));
        check(!PromptSubmitStartup.shouldSubmit(SWT.CR, 0, true));
        check(!PromptSubmitStartup.shouldSubmit('a', 0, false));
    }

    private static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Prompt key policy failed");
        }
    }
}

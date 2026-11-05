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

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.AIFunction;
import org.jkiss.dbeaver.model.ai.AIFunctionContext;
import org.jkiss.dbeaver.model.ai.AIFunctionResult;
import org.jkiss.dbeaver.model.ai.AIFunctionType;
import org.jkiss.dbeaver.ui.UIUtils;

import java.nio.file.Path;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicBoolean;

/** The source root comes only from a human-operated native directory picker. */
public final class SourceWorkspaceFunction implements AIFunction {
    private static final AtomicBoolean CHOOSING = new AtomicBoolean();
    private static final ResourceBundle TEXT = ResourceBundle.getBundle("org.jkiss.dbeaver.ui.ai.workbench.messages");

    @NotNull
    @Override
    public AIFunctionResult callFunction(@NotNull AIFunctionContext context,
        @NotNull Map<String, Object> parameters) throws DBException {
        if (context.getMonitor().isCanceled()) {
            throw new DBException("Source workspace request canceled");
        }
        Object mode = parameters.get("mode");
        if ("list".equals(mode)) {
            Object value = parameters.getOrDefault("offset", 0);
            if (!(value instanceof Number offset) || offset.doubleValue() != offset.intValue()) {
                throw new DBException("Source listing offset must be an integer");
            }
            try {
                return new AIFunctionResult(AIFunctionType.INFORMATION,
                    WorkbenchResponse.serialize(WorkbenchSourceTools.list(offset.intValue())).json());
            } catch (Exception e) {
                throw new DBException("Choose a source workspace first, then request its bounded source list");
            }
        }
        if (!"choose".equals(mode) || !CHOOSING.compareAndSet(false, true)) {
            throw new DBException("Choose mode choose or list; only one source picker may be open");
        }
        // Do not block an AI function or SWT thread while waiting for a human picker.
        UIUtils.asyncExec(() -> {
            try {
                var window = UIUtils.getActiveWorkbenchWindow();
                if (window == null || window.getShell().isDisposed()) {
                    return;
                }
                DirectoryDialog dialog = new DirectoryDialog(window.getShell());
                dialog.setText(TEXT.getString("source.workspace.title"));
                dialog.setMessage(TEXT.getString("source.workspace.message"));
                String selected = dialog.open();
                if (selected != null) {
                    try {
                        WorkbenchSourceTools.choose(Path.of(selected));
                    } catch (Exception e) {
                        MessageDialog.openError(window.getShell(), TEXT.getString("source.workspace.title"),
                            TEXT.getString("source.workspace.error"));
                    }
                }
            } finally {
                CHOOSING.set(false);
            }
        });
        return new AIFunctionResult(AIFunctionType.ACTION,
            "Requested the source folder picker. Choose the checkout, then request its source file list.");
    }
}

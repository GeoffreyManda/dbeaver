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

import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IPathEditorInput;
import org.eclipse.ui.IURIEditorInput;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.AIFunction;
import org.jkiss.dbeaver.model.ai.AIFunctionContext;
import org.jkiss.dbeaver.model.ai.AIFunctionResult;
import org.jkiss.dbeaver.model.ai.AIFunctionType;
import org.jkiss.dbeaver.ui.UIUtils;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Profile only the CSV/TSV already selected in the native editor, never a model-supplied path. */
public final class ProfileSelectedFileFunction implements AIFunction {
    @NotNull
    @Override
    public AIFunctionResult callFunction(@NotNull AIFunctionContext context,
        @NotNull Map<String, Object> parameters) throws DBException {
        Object engine = parameters.get("engine");
        if (!(engine instanceof String language) || !(language.equals("python") || language.equals("r"))) {
            throw new DBException("Choose profiling engine python or r");
        }
        if (context.getMonitor().isCanceled()) {
            throw new DBException("Profiling canceled");
        }
        CompletableFuture<Path> selection = new CompletableFuture<>();
        UIUtils.getDisplay().asyncExec(() -> {
            if (selection.isCancelled() || context.getMonitor().isCanceled()) {
                selection.cancel(false);
                return;
            }
            try {
                selection.complete(selectedFile());
            } catch (Exception e) {
                selection.completeExceptionally(new IllegalStateException("No selected CSV or TSV editor"));
            }
        });
        try {
            Path input = selection.get(5, TimeUnit.SECONDS);
            var response = WorkbenchResponse.serialize(EngineeringProfileRunner.run(
                input, language, context.getMonitor()::isCanceled));
            return new AIFunctionResult(AIFunctionType.INFORMATION, response.json());
        } catch (InterruptedException e) {
            selection.cancel(false);
            Thread.currentThread().interrupt();
            throw new DBException("Profiling interrupted");
        } catch (Exception e) {
            selection.cancel(false);
            // Never surface a path or input value from editor, filesystem or interpreter errors.
            throw new DBException("Unable to profile the selected file. Open a CSV or TSV editor and check the local interpreter.");
        }
    }

    @NotNull
    private static Path selectedFile() {
        var window = UIUtils.getActiveWorkbenchWindow();
        var page = window == null ? null : window.getActivePage();
        var editor = page == null ? null : page.getActiveEditor();
        return selectedFile(editor == null ? null : editor.getEditorInput());
    }

    @NotNull
    static Path selectedFile(@Nullable IEditorInput input) {
        Path path = null;
        if (input instanceof IURIEditorInput uriInput) {
            var uri = uriInput.getURI();
            if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())
                || uri.getAuthority() != null && !uri.getAuthority().isEmpty()) {
                throw new IllegalStateException("No local file editor");
            }
            path = Path.of(uri);
        } else if (input instanceof IPathEditorInput pathInput && pathInput.getPath() != null) {
            path = pathInput.getPath().toFile().toPath();
        }
        if (path == null || !path.isAbsolute() || path.getFileName() == null) {
            throw new IllegalStateException("No local file editor");
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".csv") && !name.endsWith(".tsv")) {
            throw new IllegalStateException("No CSV or TSV editor");
        }
        return path.toAbsolutePath().normalize();
    }
}

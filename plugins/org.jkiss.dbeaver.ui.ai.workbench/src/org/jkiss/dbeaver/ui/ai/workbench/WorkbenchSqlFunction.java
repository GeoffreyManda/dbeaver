/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ui.ai.workbench;

import org.eclipse.swt.widgets.Display;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.AIFunction;
import org.jkiss.dbeaver.model.ai.AIFunctionContext;
import org.jkiss.dbeaver.model.ai.AIFunctionResult;
import org.jkiss.dbeaver.model.ai.AIFunctionType;
import org.jkiss.dbeaver.ui.UIUtils;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Confirmed native SQL actions with bounded evidence for continued reasoning. */
public final class WorkbenchSqlFunction implements AIFunction {
    @NotNull
    @Override
    public AIFunctionResult callFunction(@NotNull AIFunctionContext context,
        @NotNull Map<String, Object> parameters) throws DBException {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        Runnable action = () -> {
            if (future.isCancelled() || context.getMonitor().isCanceled()) {
                future.cancel(false);
                return;
            }
            try {
                future.complete(WorkbenchSqlTools.execute(parameters));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        };
        Display display = UIUtils.getDisplay();
        if (display.isDisposed()) {
            throw new DBException("Workbench display is closed");
        }
        if (Display.getCurrent() == display) {
            action.run();
        } else {
            display.asyncExec(action);
        }
        try {
            var response = WorkbenchResponse.serialize(future.get(10, TimeUnit.SECONDS));
            return new AIFunctionResult(AIFunctionType.INFORMATION, response.json());
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw new DBException("SQL editor operation interrupted", e);
        } catch (Exception e) {
            future.cancel(false);
            throw new DBException("SQL editor operation failed; inspect current editor state before retrying", e);
        }
    }
}

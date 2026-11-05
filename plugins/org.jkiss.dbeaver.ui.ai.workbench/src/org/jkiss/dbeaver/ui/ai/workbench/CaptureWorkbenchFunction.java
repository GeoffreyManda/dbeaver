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
import org.jkiss.dbeaver.model.ai.AIVisualToolOutput;
import org.jkiss.dbeaver.ui.UIUtils;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Shares an approved window image with the configured vision-capable provider; no file persistence. */
public final class CaptureWorkbenchFunction implements AIFunction {
    @NotNull
    @Override
    public AIFunctionResult callFunction(@NotNull AIFunctionContext context,
        @NotNull Map<String, Object> parameters) throws DBException {
        if (!parameters.isEmpty() || context.getMonitor().isCanceled()) {
            throw new DBException("Capture accepts no paths or regions and must not be canceled");
        }
        CompletableFuture<AIVisualToolOutput> future = new CompletableFuture<>();
        Runnable action = () -> {
            if (future.isCancelled() || context.getMonitor().isCanceled()) {
                future.cancel(false);
                return;
            }
            try {
                future.complete(WorkbenchVisualCapture.capture());
            } catch (Exception error) {
                future.completeExceptionally(error);
            }
        };
        if (Display.getCurrent() == UIUtils.getDisplay()) {
            action.run();
        } else {
            UIUtils.asyncExec(action);
        }
        try {
            return new AIFunctionResult(AIFunctionType.INFORMATION, future.get(10, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw new DBException("Window sharing interrupted", error);
        } catch (ExecutionException error) {
            future.cancel(false);
            Throwable cause = error.getCause();
            String message = cause == null ? "Window capture failed" : cause.getMessage();
            return new AIFunctionResult(AIFunctionType.INFORMATION, Map.of(
                "captured", false, "visualVerificationAvailable", false,
                "reason", message == null ? "Window capture failed; no image was shared" : message));
        } catch (Exception error) {
            future.cancel(false);
            throw new DBException("Could not capture the active workbench window", error);
        }
    }
}

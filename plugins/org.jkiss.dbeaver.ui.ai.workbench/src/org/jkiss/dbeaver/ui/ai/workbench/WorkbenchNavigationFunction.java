/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ui.ai.workbench;

import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.IHandlerService;
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

/** Explicit native destinations; never executes a model-supplied command ID. */
public final class WorkbenchNavigationFunction implements AIFunction {
    private static final String ER_DIAGRAM = "er-diagram";
    private static final String VIEW_DIAGRAM = "org.jkiss.dbeaver.erd.diagram.view";

    static final Map<String, String> VIEWS = Map.of(
        "database-navigator", "org.jkiss.dbeaver.core.databaseNavigator",
        "project-explorer", "org.jkiss.dbeaver.core.projectExplorer",
        "ai-workbench", WorkbenchAgentView.ID);

    @Override
    @NotNull
    public AIFunctionResult callFunction(
        @NotNull AIFunctionContext context,
        @NotNull Map<String, Object> parameters
    ) throws DBException {
        Object value = parameters.get("destination");
        if (!(value instanceof String destination) ||
            !(VIEWS.containsKey(destination) || destination.equals("preferences")
                || destination.equals("new-connection") || destination.equals(ER_DIAGRAM))) {
            throw new DBException("Choose database-navigator, project-explorer, ai-workbench, preferences, new-connection or er-diagram");
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        Runnable action = () -> {
            if (future.isCancelled() || context.getMonitor().isCanceled()) {
                future.cancel(false);
                return;
            }
            try {
                var window = UIUtils.getActiveWorkbenchWindow();
                if (window == null || window.getActivePage() == null) {
                    throw new DBException("No active workbench");
                }
                if (VIEWS.containsKey(destination)) {
                    window.getActivePage().showView(VIEWS.get(destination));
                    future.complete("Opened " + destination.replace('-', ' ') + ".");
                } else if (destination.equals(ER_DIAGRAM)) {
                    var page = window.getActivePage();
                    String navigatorId = VIEWS.get("database-navigator");
                    var selection = window.getSelectionService().getSelection(navigatorId);
                    if (!(selection instanceof IStructuredSelection selected) || selected.size() != 1) {
                        throw new DBException("Select one table or table container in Database Navigator to open its diagram");
                    }
                    var service = window.getService(IHandlerService.class);
                    if (service == null) {
                        throw new DBException("Native command service unavailable");
                    }
                    page.activate(page.showView(navigatorId));
                    service.executeCommand(VIEW_DIAGRAM, null);
                    future.complete("Requested DBeaver's View Diagram for the selected object. Observe the workbench to verify it opened.");
                } else {
                    // Complete before a modal native wizard enters its event loop.
                    var service = window.getService(IHandlerService.class);
                    if (service == null) {
                        throw new DBException("Native command service unavailable");
                    }
                    String id = destination.equals("preferences") ? "org.eclipse.ui.window.preferences"
                        : "org.jkiss.dbeaver.core.new.connection";
                    UIUtils.asyncExec(() -> {
                        try {
                            service.executeCommand(id, null);
                        } catch (Exception e) {
                            org.jkiss.dbeaver.runtime.DBWorkbench.getPlatformUI().showError(
                                "Navigation failed", "Could not open the native dialog", e);
                        }
                    });
                    future.complete("Requested the native " + destination.replace('-', ' ')
                        + " dialog. Observe the workbench to verify it opened.");
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        };
        if (org.eclipse.swt.widgets.Display.getCurrent() == UIUtils.getDisplay()) {
            action.run();
        } else {
            UIUtils.asyncExec(action);
        }
        try {
            return new AIFunctionResult(AIFunctionType.INFORMATION, future.get(10, TimeUnit.SECONDS));
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof DBException error) {
                throw error;
            }
            throw new DBException("Could not open the requested workbench destination", e.getCause());
        } catch (Exception e) {
            future.cancel(false);
            throw new DBException("Could not open the requested workbench destination", e);
        }
    }
}

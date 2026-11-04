/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ui.ai.workbench;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.commands.ICommandService;
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

/** Native destinations and confirmed execution of registered DBeaver commands. */
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
        Object commandId = parameters.get("commandId");
        Object value = parameters.get("destination");
        if (!(value instanceof String destination) ||
            !(VIEWS.containsKey(destination) || destination.equals("preferences")
                || destination.equals("new-connection") || destination.equals(ER_DIAGRAM) || destination.equals("registered-command"))) {
            throw new DBException("Choose a destination listed in the tool schema");
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
                } else if (destination.equals("registered-command")) {
                    if (!(commandId instanceof String id) || !id.matches("org\\.jkiss\\.dbeaver\\.[A-Za-z0-9_.-]{1,100}")) {
                        throw new DBException("Supply an exact DBeaver command ID from capability search");
                    }
                    var commands = window.getService(ICommandService.class);
                    var handlers = window.getService(IHandlerService.class);
                    if (commands == null || handlers == null) {
                        throw new DBException("Native command service unavailable");
                    }
                    var command = commands.getCommand(id);
                    if (!command.isDefined() || !command.isHandled() || !command.isEnabled()
                        || command.getParameters() != null && command.getParameters().length != 0) {
                        throw new DBException("The command is unavailable in the current selection or needs a dedicated parameter adapter");
                    }
                    var page = window.getActivePage();
                    var part = page.getActivePart();
                    var selection = window.getSelectionService().getSelection();
                    String label = command.getName();
                    UIUtils.asyncExec(() -> {
                        if (window.getShell().isDisposed() || !MessageDialog.openConfirm(window.getShell(),
                            "Run DBeaver command", label + "\n" + id + "\n\nRun against the current workbench selection?")) {
                            return;
                        }
                        try {
                            if (window.getActivePage() != page || page.getActivePart() != part
                                || !java.util.Objects.equals(selection, window.getSelectionService().getSelection())
                                || !command.isEnabled()) {
                                throw new DBException("The workbench context changed. Request the command again.");
                            }
                            handlers.executeCommand(id, null);
                        } catch (Exception e) {
                            org.jkiss.dbeaver.runtime.DBWorkbench.getPlatformUI().showError(
                                "Command failed", "Could not run " + label, e);
                        }
                    });
                    future.complete("Requested confirmation for " + label + ". Wait for the user and observe the result.");
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

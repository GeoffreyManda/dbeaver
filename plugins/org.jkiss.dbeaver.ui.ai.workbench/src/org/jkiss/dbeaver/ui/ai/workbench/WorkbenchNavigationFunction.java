/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ui.ai.workbench;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
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
import org.jkiss.dbeaver.model.ai.utils.AIUtils;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.editors.MultiPageAbstractEditor;
import org.jkiss.dbeaver.ui.navigator.actions.NavigatorHandlerObjectOpen;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Native destinations and confirmed execution of registered DBeaver commands. */
public final class WorkbenchNavigationFunction implements AIFunction {
    private static final String ER_DIAGRAM = "er-diagram";
    private static final String EMBEDDED_DIAGRAM = "org.jkiss.dbeaver.ui.editors.erd.editor.ERDEditorEmbedded";
    private static final String VIEW_DIAGRAM = "org.jkiss.dbeaver.erd.diagram.view";

    // Named tools live for this app session. Store command IDs only, never arguments or connection data.
    private static final Map<String, String> RECIPES = new LinkedHashMap<>();
    private static final int MAX_RECIPES = 32;

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
                || destination.equals("new-connection") || destination.equals(ER_DIAGRAM) || (destination.equals("registered-command")
                    || destination.equals("inspect-command") || destination.equals("create-tool")
                    || destination.equals("run-tool") || destination.equals("list-tools")))) {
            throw new DBException("Choose a destination listed in the tool schema");
        }
        var workflow = WorkbenchWorkflow.dispatch(this, context, destination, parameters);
        if (workflow != null) {
            return workflow;
        }
        var table = destination.equals(ER_DIAGRAM) ? resolveDiagramTable(context, parameters) : null;
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
                boolean runRecipe = destination.equals("run-tool");
                boolean createRecipe = destination.equals("create-tool");
                if (destination.equals("list-tools")) {
                    future.complete("Saved workflows: " + WorkbenchWorkflow.names() + "; session command tools: " + RECIPES);
                    return;
                }
                String recipe = "";
                if (runRecipe || createRecipe) {
                    if (!(parameters.get("toolName") instanceof String name) || !name.matches("[a-z][a-z0-9-]{0,47}")) {
                        throw new DBException("Supply a lowercase tool name with letters, digits or hyphens");
                    }
                    recipe = name;
                }
                if (VIEWS.containsKey(destination)) {
                    window.getActivePage().showView(VIEWS.get(destination));
                    future.complete("Opened " + destination.replace('-', ' ') + ".");
                } else if (destination.equals("registered-command") || destination.equals("inspect-command")
                    || createRecipe || runRecipe) {
                    Object target = runRecipe ? RECIPES.get(recipe) : commandId;
                    if (!(target instanceof String id) || !id.matches("org\\.jkiss\\.dbeaver\\.[A-Za-z0-9_.-]{1,100}")) {
                        throw new DBException("Supply an exact DBeaver command ID from capability search");
                    }
                    var commands = window.getService(ICommandService.class);
                    var handlers = window.getService(IHandlerService.class);
                    if (commands == null || handlers == null) {
                        throw new DBException("Native command service unavailable");
                    }
                    var command = commands.getCommand(id);
                    if (!command.isDefined()) {
                        throw new DBException("This DBeaver command is not registered");
                    }
                    if (destination.equals("inspect-command")) {
                        future.complete(describe(command));
                        return;
                    }
                    if (createRecipe) {
                        if (RECIPES.containsKey(recipe) || RECIPES.size() >= MAX_RECIPES) {
                            throw new DBException("Tool name already exists or the session tool catalog is full");
                        }
                        RECIPES.put(recipe, id);
                        future.complete("Created session tool " + recipe + ". Use run-tool with that name. " + describe(command));
                        return;
                    }
                    if (!command.isHandled() || !command.isEnabled()) {
                        throw new DBException("The command is unavailable in the current selection");
                    }
                    var arguments = parseArguments(parameters.get("commandArguments"));
                    var declared = command.getParameters();
                    if (declared != null) {
                        for (var parameter : declared) {
                            if (!parameter.isOptional() && !arguments.containsKey(parameter.getId())) {
                                throw new DBException("Missing command parameter: " + parameter.getId());
                            }
                        }
                    }
                    var bound = ParameterizedCommand.generateCommand(command, arguments);
                    if (bound == null || !bound.getParameterMap().equals(arguments)) {
                        throw new DBException("Command arguments do not match the declared parameters");
                    }
                    var page = window.getActivePage();
                    var part = page.getActivePart();
                    var selection = window.getSelectionService().getSelection();
                    String label = command.getName();
                    UIUtils.asyncExec(() -> {
                        if (window.getShell().isDisposed() || !MessageDialog.openConfirm(window.getShell(),
                            "Run DBeaver command", label + "\n" + id + "\n" + arguments
                                + "\n\nRun against the current workbench selection?")) {
                            return;
                        }
                        try {
                            if (window.getActivePage() != page || page.getActivePart() != part
                                || !java.util.Objects.equals(selection, window.getSelectionService().getSelection())
                                || !command.isEnabled()) {
                                throw new DBException("The workbench context changed. Request the command again.");
                            }
                            handlers.executeCommand(bound, null);
                        } catch (Exception e) {
                            org.jkiss.dbeaver.runtime.DBWorkbench.getPlatformUI().showError(
                                "Command failed", "Could not run " + label, e);
                        }
                    });
                    future.complete("Requested confirmation for " + label + ". Wait for the user and observe the result.");
                } else if (destination.equals(ER_DIAGRAM)) {
                    var page = window.getActivePage();
                    if (table != null) {
                        var node = NavigatorHandlerObjectOpen.getNodeByObject(table);
                        if (node == null) {
                            throw new DBException("The table could not be located in the database model");
                        }
                        NavigatorHandlerObjectOpen.openEntityEditor(node, EMBEDDED_DIAGRAM, null, null, window, true);
                    } else {
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
                    }
                    var editor = page.getActiveEditor();
                    var diagram = editor instanceof MultiPageAbstractEditor multipage ? multipage.getActiveEditor() : editor;
                    if (diagram == null || !diagram.getClass().getName().startsWith("org.jkiss.dbeaver.ui.editors.erd.editor.")) {
                        throw new DBException("View Diagram returned without activating a diagram editor. Observe the workbench.");
                    }
                    future.complete(table == null ? "Opened the ER diagram for the selected Database Navigator object."
                        : "Opened the ER diagram for " + table.getName() + ".");
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

    @org.jkiss.code.Nullable
    private static DBSEntity resolveDiagramTable(@NotNull AIFunctionContext context,
        @NotNull Map<String, Object> parameters) throws DBException {
        Object value = parameters.get("tableName");
        if (value == null) {
            return null;
        }
        if (!(value instanceof String name) || name.isBlank() || name.length() > 512) {
            throw new DBException("Supply a valid table name in the chat connection");
        }
        var database = context.getContext();
        if (database == null) {
            throw new DBException("Select a database connection in chat first");
        }
        if (database.hasDataSourceCustomScope()) {
            throw new DBException("A relationship diagram may include tables outside the custom chat scope");
        }
        var table = AIUtils.findTableInContext(context, name);
        if (!(table instanceof DBSEntity entity)
            || !AIUtils.isObjectInScope(database, database.getExecutionContext(), entity)) {
            throw new DBException("The table does not support diagrams in the current chat context");
        }
        return entity;
    }

    @NotNull
    private static String describe(@NotNull Command command) throws Exception {
        var result = new StringBuilder(command.getId()).append(": ").append(command.getName())
            .append("; enabled=").append(command.isEnabled()).append("; handled=").append(command.isHandled());
        var declared = command.getParameters();
        if (declared != null) {
            for (var parameter : declared) {
                result.append("\n").append(parameter.getId()).append(" (").append(parameter.getName())
                    .append(parameter.isOptional() ? ", optional)" : ", required)");
                if (result.length() > 4000) {
                    return result.substring(0, 4000) + "…";
                }
            }
        }
        return result.toString();
    }

    @NotNull
    private static Map<String, String> parseArguments(@org.jkiss.code.Nullable Object raw) throws DBException {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof String json) || json.length() > 4096) {
            throw new DBException("Command arguments must be a JSON object of strings, at most 4096 characters");
        }
        Map<String, String> result = new LinkedHashMap<>();
        try (var reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (result.size() == 16 || reader.peek() != JsonToken.STRING || result.containsKey(key)) {
                    throw new DBException("Use at most 16 distinct command parameters with string values");
                }
                String value = reader.nextString();
                if (value.length() > 512 || value.chars().anyMatch(Character::isISOControl)) {
                    throw new DBException("Command values must be single-line strings of at most 512 characters");
                }
                result.put(key, value);
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new DBException("Supply exactly one JSON object");
            }
        } catch (java.io.IOException | IllegalStateException e) {
            throw new DBException("Invalid command arguments JSON", e);
        }
        return result;
    }
}

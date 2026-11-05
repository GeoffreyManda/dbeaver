/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ui.ai.workbench;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.AIFunctionContext;
import org.jkiss.dbeaver.model.ai.AIFunctionResult;
import org.jkiss.dbeaver.model.ai.AIFunctionType;
import org.osgi.service.prefs.BackingStoreException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Workspace-local recipes composed of view navigation and named diagram actions. */
final class WorkbenchWorkflow {
    private static final int MAX_TOOLS = 32;
    private static final int MAX_STEPS = 8;
    private static final int MAX_LENGTH = 4096;

    private WorkbenchWorkflow() {
    }

    @Nullable
    static AIFunctionResult dispatch(@NotNull WorkbenchNavigationFunction navigation,
        @NotNull AIFunctionContext context, @NotNull String destination,
        @NotNull Map<String, Object> parameters) throws DBException {
        boolean creating = destination.equals("create-tool") && parameters.containsKey("steps");
        if (!creating && !destination.equals("run-tool")) {
            return null;
        }
        if (!(parameters.get("toolName") instanceof String name) || !name.matches("[a-z][a-z0-9-]{0,47}")) {
            throw new DBException("Supply a lowercase tool name with letters, digits or hyphens");
        }
        if (context.getMonitor().isCanceled()) {
            throw new DBException("Workflow canceled");
        }
        if (creating) {
            Object commandId = parameters.get("commandId");
            if (commandId != null && (!(commandId instanceof String id) || !id.isBlank())) {
                throw new DBException("Supply workflow steps without a command ID");
            }
            Object input = parameters.get("steps");
            String definition;
            if (input instanceof String text) {
                definition = text;
            } else if (input instanceof List<?> list && list.size() <= MAX_STEPS) {
                definition = new Gson().toJson(list);
            } else {
                throw new DBException("Supply a JSON array of workflow steps");
            }
            int size = parse(definition).size();
            save(name, definition);
            return result("Saved workflow " + name + " with " + size + " steps in this workspace.");
        }
        String definition = InstanceScope.INSTANCE.getNode("org.jkiss.dbeaver.ui.ai.workbench").node("workflows").get(name, null);
        if (definition == null) {
            return null;
        }
        return run(navigation, context, name, parse(definition));
    }

    @NotNull
    private static AIFunctionResult run(@NotNull WorkbenchNavigationFunction navigation,
        @NotNull AIFunctionContext context, @NotNull String name,
        @NotNull List<Map<String, Object>> steps) throws DBException {
        var output = new StringBuilder("Ran workflow ").append(name);
        for (int index = 0; index < steps.size(); index++) {
            try {
                if (context.getMonitor().isCanceled()) {
                    throw new DBException("Workflow canceled");
                }
                var outcome = navigation.callFunction(context, steps.get(index));
                output.append("\n").append(index + 1).append(". ").append(outcome.getValue());
            } catch (DBException e) {
                throw new DBException("Workflow " + name + " stopped at step " + (index + 1)
                    + " after " + index + " completed steps: " + e.getMessage(), e);
            }
        }
        return result(output.toString());
    }

    private static synchronized void save(@NotNull String name, @NotNull String definition) throws DBException {
        var node = InstanceScope.INSTANCE.getNode("org.jkiss.dbeaver.ui.ai.workbench").node("workflows");
        try {
            if (node.get(name, null) != null || node.keys().length >= MAX_TOOLS) {
                throw new DBException("A workflow with that name exists or the workspace catalog is full");
            }
            node.put(name, definition);
            try {
                node.flush();
            } catch (BackingStoreException e) {
                node.remove(name);
                throw e;
            }
        } catch (BackingStoreException e) {
            throw new DBException("Could not save the workflow to this workspace", e);
        }
    }

    @NotNull
    static List<String> names() throws DBException {
        try {
            return Arrays.stream(InstanceScope.INSTANCE.getNode("org.jkiss.dbeaver.ui.ai.workbench")
                .node("workflows").keys()).sorted().toList();
        } catch (BackingStoreException e) {
            throw new DBException("Could not read saved workflows", e);
        }
    }

    @NotNull
    private static List<Map<String, Object>> parse(@NotNull String definition) throws DBException {
        if (definition.length() > MAX_LENGTH) {
            throw new DBException("Workflow definition exceeds 4096 characters");
        }
        try {
            var array = JsonParser.parseString(definition).getAsJsonArray();
            if (array.size() < 2 || array.size() > MAX_STEPS) {
                throw new DBException("A workflow needs 2 to 8 steps");
            }
            var steps = new ArrayList<Map<String, Object>>();
            for (JsonElement element : array) {
                var step = element.getAsJsonObject();
                String target = field(step.get("destination"));
                if (WorkbenchNavigationFunction.VIEWS.containsKey(target) && step.size() == 1) {
                    steps.add(Map.of("destination", target));
                } else if (target.equals("er-diagram") && step.size() == 2) {
                    steps.add(Map.of("destination", target, "tableName", field(step.get("tableName"))));
                } else {
                    throw new DBException("Unsupported workflow step: " + target);
                }
            }
            return List.copyOf(steps);
        } catch (JsonParseException | IllegalStateException e) {
            throw new DBException("Supply a JSON array of workflow step objects", e);
        }
    }

    @NotNull
    private static String field(@Nullable JsonElement value) throws DBException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
            || value.getAsString().isBlank() || value.getAsString().length() > 512) {
            throw new DBException("Workflow step fields must be nonempty strings");
        }
        return value.getAsString();
    }

    @NotNull
    private static AIFunctionResult result(@NotNull String message) {
        return new AIFunctionResult(AIFunctionType.INFORMATION, message);
    }
}

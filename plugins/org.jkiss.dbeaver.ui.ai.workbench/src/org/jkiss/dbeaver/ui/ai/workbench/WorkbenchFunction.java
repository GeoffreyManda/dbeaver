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

/** Bounded dispatch to the workbench display; no shell or accessibility bridge. */
abstract class WorkbenchFunction implements AIFunction {
    private static final int UI_TIMEOUT_SECONDS = 10;
    private final Operation operation;

    enum Operation { OBSERVE, ACTIVATE, PRESENTATION, CATALOG, APPEARANCE }

    WorkbenchFunction(Operation operation) {
        this.operation = operation;
    }

    @NotNull
    @Override
    public AIFunctionResult callFunction(@NotNull AIFunctionContext context,
        @NotNull Map<String, Object> parameters) throws DBException {
        if (context.getMonitor().isCanceled()) {
            throw new DBException("Workbench operation canceled");
        }
        boolean targetedAction = operation == Operation.ACTIVATE || operation == Operation.PRESENTATION;
        String snapshotId = targetedAction ? parameter(parameters, "snapshotId") : "";
        String nodeId = targetedAction ? parameter(parameters, "nodeId") : "";
        String query = operation == Operation.CATALOG ? parameter(parameters, "query") : "";
        String accent = operation == Operation.APPEARANCE ? parameter(parameters, "accent") : "";
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        Runnable action = () -> {
            // A timed-out queued request must never execute later.
            if (future.isCancelled() || context.getMonitor().isCanceled()) {
                future.cancel(false);
                return;
            }
            try {
                future.complete(switch (operation) {
                    case OBSERVE -> WorkbenchTools.observe();
                    case CATALOG -> NativeCapabilityCatalog.query(query);
                    case APPEARANCE -> WorkbenchAppearance.apply(accent);
                    case ACTIVATE, PRESENTATION -> WorkbenchTools.act(snapshotId, nodeId,
                        operation == Operation.PRESENTATION);
                });
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
            var result = future.get(UI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (operation == Operation.APPEARANCE) {
                String applied = String.valueOf(result.get("accent"));
                String message = "default".equals(applied) ? "Restored the original app colors."
                    : "Applied " + ("#2563EB".equals(applied) ? "blue"
                        : "#FF0000".equals(applied) ? "red"
                        : "#008000".equals(applied) ? "green"
                        : "#FFD700".equals(applied) ? "gold" : applied)
                        + " across the native interface.";
                return new AIFunctionResult(AIFunctionType.ACTION, message);
            }
            var response = WorkbenchResponse.serialize(result);
            if (response.snapshotId() != null) {
                WorkbenchTools.disclose(response.snapshotId(), response.disclosedNodes());
            }
            // ACTION results terminate upstream chat with their raw value. Return tool
            // evidence for a normal, localized assistant reply after the action.
            return new AIFunctionResult(AIFunctionType.INFORMATION,
                response.json());
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw new DBException("Workbench operation interrupted; observe current state before retrying", e);
        } catch (Exception e) {
            future.cancel(false);
            throw new DBException("Workbench operation failed; observe current state before retrying", e);
        }
    }

    @NotNull
    private static String parameter(@NotNull Map<String, Object> parameters, @NotNull String name)
        throws DBException {
        Object value = parameters.get(name);
        if (!(value instanceof String text) || text.isBlank() || text.length() > 128) {
            throw new DBException("Missing or invalid workbench parameter: " + name);
        }
        return text;
    }
}

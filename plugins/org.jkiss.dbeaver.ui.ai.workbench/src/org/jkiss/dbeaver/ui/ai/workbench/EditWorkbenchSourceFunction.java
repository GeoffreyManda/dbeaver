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

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.AIFunction;
import org.jkiss.dbeaver.model.ai.AIFunctionContext;
import org.jkiss.dbeaver.model.ai.AIFunctionResult;
import org.jkiss.dbeaver.model.ai.AIFunctionType;

import java.util.Map;

/** Exact, hash-guarded source replacements; deployment and arbitrary commands are absent. */
public final class EditWorkbenchSourceFunction implements AIFunction {
    @NotNull
    @Override
    public AIFunctionResult callFunction(@NotNull AIFunctionContext context,
        @NotNull Map<String, Object> parameters) throws DBException {
        Object action = parameters.get("action");
        Object path = parameters.get("relativePath");
        Object hash = parameters.get("expectedSha256");
        if (!(path instanceof String relative) || !(hash instanceof String expected)
            || context.getMonitor().isCanceled()) {
            throw new DBException("Provide a relative source path and its current SHA-256");
        }
        try {
            Map<String, Object> result;
            if ("rollback".equals(action)) {
                result = WorkbenchSourceTools.rollback(relative, expected);
            } else if ("replace".equals(action)
                && parameters.get("before") instanceof String before
                && parameters.get("after") instanceof String after) {
                result = WorkbenchSourceTools.replace(relative, expected, before, after);
            } else {
                throw new DBException("Choose replace with exact before/after text, or rollback");
            }
            return new AIFunctionResult(AIFunctionType.INFORMATION, WorkbenchResponse.serialize(result).json());
        } catch (Exception e) {
            throw new DBException("Source edit refused; reread the current hash and preserve any external changes");
        }
    }
}

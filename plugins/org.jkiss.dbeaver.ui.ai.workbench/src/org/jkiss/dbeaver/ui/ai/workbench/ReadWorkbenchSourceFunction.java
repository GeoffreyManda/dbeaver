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

/** Bounded text reads within the previously native-selected addon workspace. */
public final class ReadWorkbenchSourceFunction implements AIFunction {
    @NotNull
    @Override
    public AIFunctionResult callFunction(@NotNull AIFunctionContext context,
        @NotNull Map<String, Object> parameters) throws DBException {
        Object path = parameters.get("relativePath");
        Object start = parameters.getOrDefault("offset", 0);
        if (!(path instanceof String relative) || !(start instanceof Number offset)
            || offset.doubleValue() != offset.intValue() || context.getMonitor().isCanceled()) {
            throw new DBException("Provide a relative source path and integer chunk offset");
        }
        try {
            return new AIFunctionResult(AIFunctionType.INFORMATION,
                WorkbenchResponse.serialize(WorkbenchSourceTools.read(relative, offset.intValue())).json());
        } catch (Exception e) {
            throw new DBException("Source read refused; use an allowed relative text file and a valid chunk offset");
        }
    }
}

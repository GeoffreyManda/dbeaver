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

import org.eclipse.core.runtime.Platform;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.AIFunction;
import org.jkiss.dbeaver.model.ai.AIFunctionContext;
import org.jkiss.dbeaver.model.ai.AIFunctionResult;
import org.jkiss.dbeaver.model.ai.AIFunctionType;

import java.nio.file.Path;
import java.util.Map;

/** Compile-only verification with fixed installed compiler inputs, never an editable builder. */
public final class VerifyWorkbenchSourceFunction implements AIFunction {
    @NotNull
    @Override
    public AIFunctionResult callFunction(@NotNull AIFunctionContext context,
        @NotNull Map<String, Object> parameters) throws DBException {
        if (context.getMonitor().isCanceled()) {
            throw new DBException("Source verification canceled");
        }
        try {
            var location = Platform.getInstallLocation();
            if (location == null || !"file".equalsIgnoreCase(location.getURL().getProtocol())) {
                throw new DBException("The running application has no local API installation");
            }
            Path installation = Path.of(location.getURL().toURI());
            return new AIFunctionResult(AIFunctionType.INFORMATION, WorkbenchResponse.serialize(
                WorkbenchSourceTools.verify(installation, context.getMonitor()::isCanceled)).json());
        } catch (Exception e) {
            throw new DBException("Source verification did not complete; check the selected checkout and existing local compiler");
        }
    }
}

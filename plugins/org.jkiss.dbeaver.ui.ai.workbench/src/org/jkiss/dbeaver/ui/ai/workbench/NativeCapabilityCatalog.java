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

import org.eclipse.core.commands.Command;
import org.eclipse.ui.commands.ICommandService;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Describe installed capabilities without treating discovery as permission to execute them. */
final class NativeCapabilityCatalog {
    private NativeCapabilityCatalog() {
    }

    @NotNull
    static Map<String, Object> query(@NotNull String terms) {
        List<WorkbenchCapabilityCatalog.Capability> entries = new ArrayList<>();
        var registry = DBWorkbench.getPlatform().getDataSourceProviderRegistry();
        boolean truncated = false;
        for (var provider : registry.getDataSourceProviders()) {
            for (var driver : provider.getEnabledDrivers()) {
                if (entries.size() >= 2048) {
                    truncated = true;
                    break;
                }
                entries.add(new WorkbenchCapabilityCatalog.Capability(
                    clip("driver:" + provider.getId() + ":" + driver.getId(), 128), "driver", "connections",
                    clip(driver.getFullName(), 160), "Registered driver metadata; connection setup uses DBeaver's native wizard",
                    true, true, WorkbenchCapabilityCatalog.Risk.READ_ONLY));
            }
        }
        var window = UIUtils.getActiveWorkbenchWindow();
        ICommandService service = window == null ? null : window.getService(ICommandService.class);
        if (service != null) {
            for (Command command : service.getDefinedCommands()) {
                if (!command.getId().startsWith("org.jkiss.dbeaver.")) {
                    continue;
                }
                if (entries.size() >= 4096) {
                    truncated = true;
                    break;
                }
                try {
                    entries.add(new WorkbenchCapabilityCatalog.Capability(
                        clip(command.getId(), 128), "command", "native-commands", clip(command.getName(), 160),
                        "Native command metadata only; risk requires separate review before execution",
                        false, command.isEnabled(), WorkbenchCapabilityCatalog.Risk.UNKNOWN));
                } catch (org.eclipse.core.commands.common.NotDefinedException e) {
                    // A command removed during plugin activation is not a current capability.
                    truncated = true;
                }
            }
        }
        return new WorkbenchCapabilityCatalog(entries, truncated).query(terms, "", false, false, 40).toMap();
    }

    @NotNull
    private static String clip(@Nullable String value, int limit) {
        if (value == null || value.isBlank()) {
            return "unspecified";
        }
        int end = Math.min(value.length(), limit);
        if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}

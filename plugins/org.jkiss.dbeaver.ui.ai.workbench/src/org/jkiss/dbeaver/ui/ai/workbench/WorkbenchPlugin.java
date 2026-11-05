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
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/** Release native hooks when the addon is stopped or updated. */
public final class WorkbenchPlugin implements BundleActivator {
    @Override
    public void start(@NotNull BundleContext context) {
        // UI initialization belongs to the normal workbench startup extension.
    }

    @Override
    public void stop(@NotNull BundleContext context) {
        WorkbenchIconTheme.stop();
        WorkbenchDesign.stop();
        NativeWorkbenchMaterial.stop();
        WorkbenchAppearance.stop();
        WorkbenchSourceTools.stop();
        PromptSubmitStartup.uninstall();
        WorkbenchTools.dispose();
    }
}

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

import org.eclipse.core.runtime.Path;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IPathEditorInput;
import org.eclipse.ui.IURIEditorInput;

import java.lang.reflect.Proxy;
import java.net.URI;

/** Native editor adapters must never turn remote or arbitrary input into a file read. */
public final class SelectedFileInputTest {
    public static void main(String[] args) {
        var csv = input(IURIEditorInput.class, URI.create("file:///tmp/public-fixture.csv"));
        check(ProfileSelectedFileFunction.selectedFile(csv).isAbsolute());
        check(ProfileSelectedFileFunction.selectedFile(input(IURIEditorInput.class,
            URI.create("file:///tmp/public-fixture.TSV"))).getFileName().toString().endsWith(".TSV"));
        reject(null);
        reject(input(IURIEditorInput.class, URI.create("https://example.org/source.csv")));
        reject(input(IURIEditorInput.class, URI.create("file://example.org/source.csv")));
        reject(input(IURIEditorInput.class, URI.create("file:///tmp/source.sql")));
        reject(input(IURIEditorInput.class, null));
        reject(input(IPathEditorInput.class, new Path("relative/source.csv")));
        check(ProfileSelectedFileFunction.selectedFile(input(IPathEditorInput.class,
            new Path("/tmp/public-fixture.csv"))).isAbsolute());
    }

    private static IEditorInput input(Class<?> type, Object value) {
        return (IEditorInput) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (proxy, method, arguments) -> {
                if (method.getName().equals("getURI") || method.getName().equals("getPath")) {
                    return value;
                }
                throw new AssertionError("Unneeded editor data was accessed: " + method.getName());
            });
    }

    private static void reject(IEditorInput input) {
        try {
            ProfileSelectedFileFunction.selectedFile(input);
            throw new AssertionError("Unsupported input accepted");
        } catch (IllegalStateException expected) {
            // Selection failed before any filesystem or interpreter access.
        }
    }

    private static void check(boolean condition) {
        if (!condition) {
            throw new AssertionError("Selected input adapter failed");
        }
    }
}

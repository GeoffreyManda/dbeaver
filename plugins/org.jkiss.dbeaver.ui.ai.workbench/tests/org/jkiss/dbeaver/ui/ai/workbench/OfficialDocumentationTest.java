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

import com.google.gson.JsonParser;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.AIFunctionContext;
import org.jkiss.dbeaver.model.ai.prompt.AIPromptGenerateSql;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Public-source smoke is opt-in; default tests make no network requests. */
public final class OfficialDocumentationTest {
    public static void main(@NotNull String[] args) throws Exception {
        var function = new SearchOfficialDocumentationFunction();
        boolean[] canceled = {false};
        var monitor = new VoidProgressMonitor() {
            @Override
            public boolean isCanceled() {
                return canceled[0];
            }
        };
        var context = new AIFunctionContext(monitor, null, new AIPromptGenerateSql());
        try {
            function.callFunction(context, Map.of("query", ""));
            throw new AssertionError("Blank query accepted");
        } catch (DBException expected) {
            // Invalid input must fail before retrieval.
        }
        String noMatch = (String) function.callFunction(context, Map.of("query", "zzzzzzzzzzzz")).getValue();
        var empty = JsonParser.parseString(noMatch).getAsJsonObject();
        if (empty.getAsJsonArray("results").size() != 0 || empty.get("queryTransmitted").getAsBoolean()) {
            throw new AssertionError("Unmatched query should not request any page");
        }
        canceled[0] = true;
        try {
            function.callFunction(context, Map.of("query", "zzzzzzzzzzzz"));
            throw new AssertionError("Canceled query accepted");
        } catch (DBException expected) {
            // Cancellation also applies when no topic matches.
        }
        if (Boolean.getBoolean("dbeaver.workbench.onlineTests")) {
            canceled[0] = false;
            String json = (String) function.callFunction(context, Map.of("query", "python")).getValue();
            var result = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("results");
            if (json.getBytes(StandardCharsets.UTF_8).length > 32768 || result.size() != 1) {
                throw new AssertionError("Unexpected official Python result bounds");
            }
            var page = result.get(0).getAsJsonObject();
            if (!page.has("excerpt") || !page.get("url").getAsString().equals("https://docs.python.org/3/library/csv.html")
                || !page.get("excerpt").getAsString().toLowerCase().contains("csv")) {
                throw new AssertionError("Official Python documentation was not retrieved");
            }
        }
    }
}

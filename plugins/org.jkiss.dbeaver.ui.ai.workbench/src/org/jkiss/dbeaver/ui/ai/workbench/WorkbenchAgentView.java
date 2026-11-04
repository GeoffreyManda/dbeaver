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

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.part.ViewPart;
import org.jkiss.dbeaver.model.ai.AIConstants;
import org.jkiss.dbeaver.runtime.DBWorkbench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ResourceBundle;

/** Native, theme-following connection and workflow companion; does not replace DBeaver editors. */
public final class WorkbenchAgentView extends ViewPart {
    public static final String ID = "org.jkiss.dbeaver.ui.ai.workbench.view";
    private static final ResourceBundle TEXT = ResourceBundle.getBundle(
        "org.jkiss.dbeaver.ui.ai.workbench.messages");
    private static final Map<String, String> TEMPLATES = new LinkedHashMap<>();
    static {
        TEMPLATES.put("template.quality", "sql/data-quality.sql");
        TEMPLATES.put("template.python", "python/profile_csv.py");
        TEMPLATES.put("template.r", "r/profile_csv.R");
        TEMPLATES.put("template.incremental", "python/incremental_load.py");
        TEMPLATES.put("template.delta", "sql/databricks-delta.sql");
    }
    private CTabFolder tabs;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        tabs = new CTabFolder(parent, SWT.BORDER);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Composite connections = page("connections");
        Label note = new Label(connections, SWT.WRAP);
        note.setText(text("connections.note"));
        note.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        Composite actions = new Composite(connections, SWT.NONE);
        actions.setLayout(new GridLayout(2, false));
        actions.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        responsiveColumns(actions, 2);
        button(actions, "connection.new", () -> command("org.jkiss.dbeaver.core.new.connection"));
        button(actions, "connection.navigator", () -> {
            try {
                getSite().getPage().showView("org.jkiss.dbeaver.core.databaseNavigator");
            } catch (Exception e) {
                status(connections, e.getMessage());
            }
        });
        Composite languageRow = new Composite(connections, SWT.NONE);
        languageRow.setLayout(new GridLayout(3, false));
        languageRow.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        Label language = new Label(languageRow, SWT.NONE);
        language.setText(text("language"));
        Text languageValue = new Text(languageRow, SWT.BORDER);
        languageValue.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        languageValue.setText(DBWorkbench.getPlatform().getPreferenceStore().getString(AIConstants.AI_RESPONSE_LANGUAGE));
        button(languageRow, "language.apply", () -> DBWorkbench.getPlatform().getPreferenceStore()
            .setValue(AIConstants.AI_RESPONSE_LANGUAGE, languageValue.getText().strip()));
        Composite tutorials = page("tutorials");
        Text content = new Text(tutorials, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
        GridData contentData = new GridData(SWT.FILL, SWT.FILL, true, true);
        contentData.heightHint = 120;
        content.setLayoutData(contentData);
        Composite choices = new Composite(tutorials, SWT.NONE);
        choices.setLayout(new GridLayout(3, false));
        choices.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        responsiveColumns(choices, 3);
        button(choices, "tutorial.guide", () -> content.setText(resource("resources/tutorials/agentic-tutorials.md")));
        for (var entry : TEMPLATES.entrySet()) {
            button(choices, entry.getKey(), () -> content.setText(resource("resources/templates/" + entry.getValue())));
        }
        content.setText(resource("resources/tutorials/agentic-tutorials.md"));
        tabs.setSelection(0);
    }

    private Composite page(String key) {
        CTabItem item = new CTabItem(tabs, SWT.NONE);
        item.setText(text(key));
        ScrolledComposite scroll = new ScrolledComposite(tabs, SWT.V_SCROLL);
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);
        Composite body = new Composite(scroll, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 16;
        layout.marginHeight = 16;
        layout.verticalSpacing = 10;
        body.setLayout(layout);
        scroll.setContent(body);
        scroll.addListener(SWT.Resize, event -> scroll.setMinSize(body.computeSize(
            Math.max(200, scroll.getClientArea().width), SWT.DEFAULT)));
        body.addListener(SWT.Resize, event -> scroll.setMinSize(body.computeSize(
            Math.max(200, scroll.getClientArea().width), SWT.DEFAULT)));
        item.setControl(scroll);
        return body;
    }

    private static void responsiveColumns(Composite row, int maximum) {
        row.addListener(SWT.Resize, event -> {
            int columns = Math.max(1, Math.min(maximum, row.getClientArea().width / 260));
            GridLayout layout = (GridLayout) row.getLayout();
            if (layout.numColumns != columns) {
                layout.numColumns = columns;
                row.layout(true, true);
            }
        });
    }

    private void button(Composite parent, String key, Runnable action) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(text(key));
        button.addListener(SWT.Selection, event -> action.run());
    }

    private void command(String id) {
        try {
            IHandlerService service = getSite().getService(IHandlerService.class);
            if (service == null) {
                throw new IllegalStateException("Workbench command service unavailable");
            }
            service.executeCommand(id, null);
        } catch (Exception e) {
            status(tabs.getSelection().getControl().getParent(), e.getMessage());
        }
    }

    private void status(Composite parent, String message) {
        getViewSite().getActionBars().getStatusLineManager().setErrorMessage(message);
    }

    private static String text(String key) {
        return TEXT.getString(key);
    }

    private static String resource(String name) {
        try (var stream = WorkbenchAgentView.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                return text("resource.missing");
            }
            byte[] bytes = stream.readNBytes(32769);
            if (bytes.length > 32768) {
                return text("resource.limit");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return text("resource.missing");
        }
    }

    @Override
    public void setFocus() {
        if (tabs != null && !tabs.isDisposed()) {
            tabs.setFocus();
        }
    }
}

/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ui.ai.workbench;

import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorPart;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLQueryContainer;
import org.jkiss.dbeaver.model.sql.SQLQueryType;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.ui.UIServiceSQL;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Live SQL document operations; query execution stays in the existing editor pipeline. */
final class WorkbenchSqlTools {
    private static final int MAX_SQL = 16 * 1024;
    private static final Map<IEditorPart, String> EDITORS = new WeakHashMap<>();

    private WorkbenchSqlTools() {
    }

    @NotNull
    static Map<String, Object> execute(@NotNull Map<String, Object> parameters) throws DBException {
        String operation = required(parameters, "operation");
        SQLEditor editor = activeEditor();
        if (operation.equals("open")) {
            String text = required(parameters, "text");
            checkSize(text);
            var container = editor.getDataSourceContainer();
            if (container == null) {
                throw new DBException("The active SQL editor has no selected connection");
            }
            UIServiceSQL service = DBWorkbench.getService(UIServiceSQL.class);
            if (service == null) {
                throw new DBException("Native SQL console service is unavailable");
            }
            Object opened = service.openSQLConsole(container, editor.getExecutionContext(), null,
                "AI SQL " + UUID.randomUUID().toString().substring(0, 8), text);
            if (!(opened instanceof SQLEditor newEditor)) {
                throw new DBException("Native SQL console did not open");
            }
            Map<String, Object> result = inspect(newEditor);
            result.put("openedNewConsole", true);
            result.put("executed", false);
            return result;
        }
        if (operation.equals("inspect")) {
            return inspect(editor);
        }
        String editorId = required(parameters, "editorId");
        if (!identity(editor).equals(editorId)) {
            throw new DBException("The active editor changed; inspect it before retrying");
        }
        IDocument document = document(editor);
        String current = document.get();
        expectHash(required(parameters, "expectedSha256"), hash(current));
        if (operation.equals("replace")) {
            String changed = exactReplacement(current, required(parameters, "before"), required(parameters, "after"));
            document.set(changed);
            Map<String, Object> result = inspect(editor);
            result.put("edited", true);
            result.put("savedToDisk", false);
            return result;
        }
        if (operation.equals("execute-read") || operation.equals("format-wkt") || operation.equals("fetch-all")) {
            var container = editor.getDataSourceContainer();
            if (container == null || !container.isConnectionReadOnly()) {
                throw new DBException("Read execution requires a connection explicitly marked read-only");
            }
            var statements = editor.extractScriptQueries(0, document.getLength(), true, false, false);
            if (statements.size() != 1 || !(statements.getFirst() instanceof SQLQuery query)) {
                throw new DBException("Execute-read requires exactly one parsed SQL SELECT statement");
            }
            if (!isReadQuery(query)) {
                throw new DBException("Only a single non-modifying parsed SELECT may run through this tool");
            }
            if (operation.equals("fetch-all")) {
                if (!"sqlite_jdbc".equals(container.getDriver().getId())) {
                    throw new DBException("Bounded fetch-all currently supports the native SQLite driver only");
                }
                var viewer = WorkbenchTools.observedResult(required(parameters, "snapshotId"));
                var dataContainer = viewer.getDataContainer();
                if (!(dataContainer instanceof SQLQueryContainer queryContainer)
                    || !(queryContainer.getQuery() instanceof SQLQuery fetchedQuery)
                    || !isReadQuery(fetchedQuery)
                    || !canonicalQuery(query.getOriginalText()).equals(canonicalQuery(fetchedQuery.getOriginalText()))) {
                    throw new DBException("The fetched result no longer belongs to the inspected SQL statement");
                }
                int limit = boundedSqliteLimit(fetchedQuery.getOriginalText());
                final int fetchedRows = viewer.getModel().getRowCount();
                UIUtils.asyncExec(() -> {
                    if (!viewer.getControl().isDisposed() && !viewer.isRefreshInProgress()
                        && viewer.getDataContainer() == dataContainer && queryContainer.getQuery() == fetchedQuery
                        && viewer.getModel().getRowCount() == fetchedRows && !viewer.getModel().isDirty()) {
                        viewer.readAllData();
                    }
                });
                return Map.of("editorId", identity(editor), "sha256", hash(current), "fetchRequested", true,
                    "sqlRowLimit", limit, "fetchedRowsBefore", fetchedRows, "completed", false, "mapRendered", false,
                    "nextStep", "Respond to the native fetch-all confirmation if shown, then observe the final fetched count."
                        + " The SQL statement bounds this SQLite result; the request alone does not prove completion.");
            }
            if (operation.equals("format-wkt")) {
                return WorkbenchGeometryTools.format(required(parameters, "snapshotId"),
                    required(parameters, "attributeName"), parameters.get("srid"));
            }
            boolean submitted = editor.processQueries(statements, false, true, false, true, null, null);
            return Map.of("editorId", identity(editor), "sha256", hash(current), "submitted", submitted,
                "readOnly", true, "completed", false,
                "nextStep", "Execution is asynchronous. Observe fetched results before claiming success or a rendered map.");
        }
        throw new DBException("Choose inspect, open, replace, execute-read, format-wkt or fetch-all");
    }

    @NotNull
    private static SQLEditor activeEditor() throws DBException {
        if (!UIUtils.isUIThread()) {
            throw new DBException("SQL editor operations require the native display thread");
        }
        var window = UIUtils.getActiveWorkbenchWindow();
        if (window == null || window.getActivePage() == null
            || !(window.getActivePage().getActiveEditor() instanceof SQLEditor editor)) {
            throw new DBException("Open a SQL editor with the intended connection first");
        }
        return editor;
    }

    @NotNull
    private static IDocument document(@NotNull SQLEditor editor) throws DBException {
        var provider = editor.getDocumentProvider();
        IDocument document = provider == null ? null : provider.getDocument(editor.getEditorInput());
        if (document == null) {
            throw new DBException("The SQL document is unavailable");
        }
        checkSize(document.get());
        return document;
    }

    @NotNull
    private static Map<String, Object> inspect(@NotNull SQLEditor editor) throws DBException {
        String text = document(editor).get();
        var container = editor.getDataSourceContainer();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("editorId", identity(editor));
        result.put("sha256", hash(text));
        result.put("text", text);
        result.put("dirty", editor.isDirty());
        result.put("readOnly", container != null && container.isConnectionReadOnly());
        result.put("scope", "active SQL document; no connection URL, credentials or result cells");
        return result;
    }

    @NotNull
    private static String identity(@NotNull IEditorPart editor) {
        return EDITORS.computeIfAbsent(editor, ignored -> UUID.randomUUID().toString());
    }

    @NotNull
    private static String canonicalQuery(@NotNull String sql) {
        String result = sql.strip();
        return result.endsWith(";") ? result.substring(0, result.length() - 1).stripTrailing() : result;
    }

    /** Recognizes a trailing top-level SQLite LIMIT without treating comments or quoted text as SQL tokens. */
    static int boundedSqliteLimit(@NotNull String sql) throws DBException {
        checkSize(sql);
        List<String> tokens = sqliteTopLevelTokens(sql);
        if (!tokens.isEmpty() && tokens.getLast().equals(";")) {
            tokens.removeLast();
        }
        if (tokens.size() < 3 || tokens.contains(";") || !tokens.get(tokens.size() - 2).equalsIgnoreCase("LIMIT")) {
            throw new DBException("Fetch-all requires an explicit trailing top-level LIMIT between 1 and 10000");
        }
        String literal = tokens.getLast();
        if (!literal.matches("[0-9]{1,5}")) {
            throw new DBException("Fetch-all LIMIT must be a positive integer literal, not an expression or parameter");
        }
        int limit = Integer.parseInt(literal);
        if (limit < 1 || limit > 10000) {
            throw new DBException("Fetch-all LIMIT must be between 1 and 10000");
        }
        return limit;
    }

    @NotNull
    private static List<String> sqliteTopLevelTokens(@NotNull String sql) throws DBException {
        List<String> tokens = new ArrayList<>();
        int depth = 0;
        for (int index = 0; index < sql.length();) {
            char value = sql.charAt(index);
            if (Character.isWhitespace(value)) {
                index++;
            } else if (value == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                int end = sql.indexOf('\n', index + 2);
                index = end < 0 ? sql.length() : end + 1;
            } else if (value == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                int end = sql.indexOf("*/", index + 2);
                if (end < 0) {
                    throw new DBException("Unterminated SQLite comment");
                }
                index = end + 2;
            } else if (value == '\'' || value == '"' || value == '`' || value == '[') {
                index = skipSqliteQuote(sql, index);
                if (depth == 0) {
                    tokens.add("quoted");
                }
            } else if (value == '(') {
                if (depth++ == 0) {
                    tokens.add("(");
                }
                index++;
            } else if (value == ')') {
                if (--depth < 0) {
                    throw new DBException("Unbalanced SQLite parentheses");
                }
                if (depth == 0) {
                    tokens.add(")");
                }
                index++;
            } else if (Character.isLetterOrDigit(value) || value == '_' || value == '$') {
                int start = index++;
                while (index < sql.length()) {
                    char next = sql.charAt(index);
                    if (!(Character.isLetterOrDigit(next) || next == '_' || next == '$')) {
                        break;
                    }
                    index++;
                }
                if (depth == 0) {
                    tokens.add(sql.substring(start, index));
                }
            } else {
                if (depth == 0) {
                    tokens.add(Character.toString(value));
                }
                index++;
            }
        }
        if (depth != 0) {
            throw new DBException("Unbalanced SQLite parentheses");
        }
        return tokens;
    }

    private static int skipSqliteQuote(@NotNull String sql, int start) throws DBException {
        char quote = sql.charAt(start) == '[' ? ']' : sql.charAt(start);
        for (int index = start + 1; index < sql.length(); index++) {
            if (sql.charAt(index) == quote) {
                if (quote != ']' && index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                    index++;
                } else {
                    return index + 1;
                }
            }
        }
        throw new DBException("Unterminated SQLite quoted value or identifier");
    }

    static boolean isReadQuery(@NotNull SQLQuery query) {
        return mayExecuteRead(query.getType(), query.isModifying(), query.representsOneStatement());
    }

    static boolean mayExecuteRead(@NotNull SQLQueryType type, boolean modifying, boolean singleStatement) {
        return type == SQLQueryType.SELECT && !modifying && singleStatement;
    }

    @NotNull
    static String exactReplacement(@NotNull String current, @NotNull String before, @NotNull String after) throws DBException {
        checkSize(current);
        checkSize(before);
        checkSize(after);
        int start = current.indexOf(before);
        if (before.isEmpty() || start < 0 || current.indexOf(before, start + 1) >= 0) {
            throw new DBException("The expected SQL segment must occur exactly once");
        }
        String changed = current.substring(0, start) + after + current.substring(start + before.length());
        checkSize(changed);
        if (changed.equals(current) || changed.isBlank()) {
            throw new DBException("SQL replacement must change content without deleting the document");
        }
        return changed;
    }

    static void expectHash(@NotNull String expected, @NotNull String actual) throws DBException {
        if (!expected.matches("[0-9a-fA-F]{64}") || !actual.equalsIgnoreCase(expected)) {
            throw new DBException("The SQL document changed; inspect it again before editing or executing");
        }
    }

    @NotNull
    static String hash(@NotNull String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void checkSize(@NotNull String text) throws DBException {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_SQL) {
            throw new DBException("SQL document exceeds the 16 KiB tool limit");
        }
    }

    @NotNull
    private static String required(@NotNull Map<String, Object> parameters, @NotNull String key) throws DBException {
        Object value = parameters.get(key);
        if (!(value instanceof String text)) {
            throw new DBException("Missing SQL editor parameter: " + key);
        }
        return text;
    }
}

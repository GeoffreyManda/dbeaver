/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ui.ai.workbench;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.sql.SQLQueryType;

/** Document guards exercise no workbench, connection or database operations. */
public final class WorkbenchSqlToolsTest {
    public static void main(@NotNull String[] arguments) throws Exception {
        String original = "SELECT 1 AS example;";
        String edited = WorkbenchSqlTools.exactReplacement(original, "1 AS example", "2 AS example");
        check(edited.equals("SELECT 2 AS example;"), "Exact edit preserves unrelated SQL");
        check(WorkbenchSqlTools.hash(original).equals("205e27c8f6666c96b58fb46090aaca1a8fbd61733a3fb32aaf37e0b9fcee6ac1"),
            "SHA256 is deterministic for SQL text");
        WorkbenchSqlTools.expectHash(WorkbenchSqlTools.hash(original), WorkbenchSqlTools.hash(original));
        rejects(() -> WorkbenchSqlTools.expectHash(WorkbenchSqlTools.hash(original), WorkbenchSqlTools.hash(edited)));
        rejects(() -> WorkbenchSqlTools.exactReplacement("SELECT 1, 1", "1", "2"));
        rejects(() -> WorkbenchSqlTools.exactReplacement(original, "absent", "2"));
        rejects(() -> WorkbenchSqlTools.exactReplacement(original, "", "2"));
        rejects(() -> WorkbenchSqlTools.exactReplacement(original, original, ""));
        rejects(() -> WorkbenchSqlTools.exactReplacement(original, "1", "1"));
        rejects(() -> WorkbenchSqlTools.exactReplacement(original, "1", "x".repeat(17 * 1024)));
        rejects(() -> WorkbenchSqlTools.exactReplacement("é".repeat(9 * 1024), "é", "x"));
        check(WorkbenchSqlTools.mayExecuteRead(SQLQueryType.SELECT, false, true), "Parsed SELECT is admitted");
        check(!WorkbenchSqlTools.mayExecuteRead(SQLQueryType.DELETE, true, true), "DELETE is rejected");
        check(!WorkbenchSqlTools.mayExecuteRead(SQLQueryType.SELECT, false, false), "Multiple statements are rejected");
        check(!WorkbenchSqlTools.mayExecuteRead(SQLQueryType.SELECT, true, true), "Mutating SELECT is rejected");
        check(!WorkbenchSqlTools.mayExecuteRead(SQLQueryType.UNKNOWN, false, true), "Unparsed statements are rejected");
        check(WorkbenchGeometryTools.validAttributeName("geometry_wkt"), "Named WKT column is admitted");
        check(!WorkbenchGeometryTools.validAttributeName("geometry;DROP"), "Commands are not column names");
        check(!WorkbenchGeometryTools.validAttributeName("x".repeat(129)), "Attribute names are bounded");
        check(WorkbenchGeometryTools.srid(null) == 4326, "Default WKT SRID is explicit");
        check(WorkbenchGeometryTools.srid(4326.0) == 4326, "Integral numeric SRID is admitted");
        rejects(() -> WorkbenchGeometryTools.srid(4326.5));
        rejects(() -> WorkbenchGeometryTools.srid(-1));
        rejects(() -> WorkbenchGeometryTools.srid(Double.NaN));
        rejects(() -> WorkbenchGeometryTools.srid("4326"));
        check(WorkbenchSqlTools.boundedSqliteLimit("SELECT * FROM places LIMIT 10000;") == 10000, "SQL row cap is admitted");
        check(WorkbenchSqlTools.boundedSqliteLimit("SELECT 'LIMIT 999999' AS note LIMIT 6841 -- retained comment") == 6841,
            "Quoted contents and comments cannot supply a limit");
        check(WorkbenchSqlTools.boundedSqliteLimit("SELECT * FROM (SELECT 1 LIMIT 1) LIMIT /* cap */ 25;") == 25,
            "Nested limits do not replace the outer row cap");
        check(WorkbenchSqlTools.boundedSqliteLimit("SELECT \"LIMIT\", `LIMIT`, [LIMIT] FROM places LIMIT 10") == 10,
            "Quoted identifiers are not limit operators");
        check(WorkbenchSqlTools.boundedSqliteLimit("SELECT 'it''s a LIMIT 20000' LIMIT 10") == 10,
            "Escaped quote remains inside its literal");
        rejects(() -> WorkbenchSqlTools.boundedSqliteLimit("SELECT * FROM places /* LIMIT 10 */"));
        rejects(() -> WorkbenchSqlTools.boundedSqliteLimit("SELECT * FROM places -- LIMIT 10"));
        rejects(() -> WorkbenchSqlTools.boundedSqliteLimit("SELECT 'LIMIT 10'"));
        rejects(() -> WorkbenchSqlTools.boundedSqliteLimit("SELECT * FROM (SELECT 1 LIMIT 10)"));
        rejects(() -> WorkbenchSqlTools.boundedSqliteLimit("SELECT * FROM places LIMIT 10001"));
        rejects(() -> WorkbenchSqlTools.boundedSqliteLimit("SELECT * FROM places LIMIT 0"));
        rejects(() -> WorkbenchSqlTools.boundedSqliteLimit("SELECT * FROM places LIMIT -1"));
        rejects(() -> WorkbenchSqlTools.boundedSqliteLimit("SELECT * FROM places LIMIT ?"));
        rejects(() -> WorkbenchSqlTools.boundedSqliteLimit("SELECT * FROM places LIMIT :limit"));
        rejects(() -> WorkbenchSqlTools.boundedSqliteLimit("SELECT * FROM places LIMIT 10 OFFSET 1"));
        rejects(() -> WorkbenchSqlTools.boundedSqliteLimit("SELECT * FROM places LIMIT 1, 10"));
        rejects(() -> WorkbenchSqlTools.boundedSqliteLimit("SELECT * FROM places LIMIT 1e3"));
        rejects(() -> WorkbenchSqlTools.boundedSqliteLimit("SELECT * FROM places LIMIT 10; SELECT 1"));
        rejects(() -> WorkbenchSqlTools.boundedSqliteLimit("SELECT * FROM places LIMIT 10; /* missing end"));
        rejects(() -> WorkbenchSqlTools.boundedSqliteLimit("SELECT * FROM places WHERE note='LIMIT 10"));
        rejects(() -> WorkbenchSqlTools.boundedSqliteLimit("SELECT * FROM places) LIMIT 10"));
        System.out.println("SQL document and parser-result guard tests passed; no query executed");
    }

    private static void rejects(@NotNull Checked action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected a guarded SQL operation rejection");
        } catch (DBException expected) {
            // Expected exact-match, identity or byte-budget rejection.
        }
    }

    private static void check(boolean condition, @NotNull String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }
}

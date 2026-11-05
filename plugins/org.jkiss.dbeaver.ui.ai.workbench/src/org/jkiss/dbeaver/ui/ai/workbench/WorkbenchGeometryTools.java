/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ui.ai.workbench;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.virtual.DBVUtils;
import org.jkiss.dbeaver.runtime.DBWorkbench;

import java.util.LinkedHashMap;
import java.util.Map;

/** Applies the existing GIS format to a fresh observed result, then requests a native read-only refresh. */
final class WorkbenchGeometryTools {
    private static final String TRANSFORMER = "GeometryAttributeTransformer";

    private WorkbenchGeometryTools() {
    }

    @NotNull
    static Map<String, Object> format(@NotNull String snapshotId, @NotNull String attributeName,
        @Nullable Object requestedSrid) throws DBException {
        if (!validAttributeName(attributeName)) {
            throw new DBException("Use the exact bounded name of the intended WKT result column");
        }
        final int srid = srid(requestedSrid);
        var viewer = WorkbenchTools.observedResult(snapshotId);
        DBDAttributeBinding selected = null;
        for (var attribute : viewer.getModel().getAttributes()) {
            if (attribute.getName().equals(attributeName)) {
                if (selected != null) {
                    throw new DBException("The result column name is ambiguous");
                }
                selected = attribute;
            }
        }
        if (selected == null || selected.getDataKind() != DBPDataKind.STRING) {
            throw new DBException("The observed result has no unique text column with that exact name");
        }
        var dataSource = selected.getDataSource();
        if (dataSource == null || !dataSource.getContainer().isConnectionReadOnly()) {
            throw new DBException("Geometry formatting requires the observed result's read-only connection");
        }
        var registry = DBWorkbench.getPlatform().getValueHandlerRegistry();
        var available = registry.findTransformers(dataSource, selected, true);
        if (available == null || available.stream().noneMatch(value -> TRANSFORMER.equals(value.getId()))) {
            throw new DBException("The native GIS geometry formatter is not available for this column");
        }
        var settings = DBVUtils.getTransformSettings(selected, true);
        if (settings == null) {
            throw new DBException("Native column-format settings are unavailable");
        }
        String previousTransformer = settings.getCustomTransformer();
        Map<String, Object> previousOptions = settings.getTransformOptions() == null ? null
            : new LinkedHashMap<>(settings.getTransformOptions());
        settings.setCustomTransformer(TRANSFORMER);
        settings.setTransformOption("srid", Integer.toString(srid));
        settings.setTransformOption("invertCoordinates", false);
        settings.setTransformOption("leadingSrid", false);
        boolean submitted = false;
        try {
            submitted = viewer.refreshData(null);
            if (!submitted) {
                throw new DBException("Native result refresh did not start; geometry format was restored");
            }
            dataSource.getContainer().persistConfiguration();
        } finally {
            if (!submitted) {
                settings.setCustomTransformer(previousTransformer);
                settings.setTransformOptions(previousOptions);
            }
        }
        return Map.of("attributeName", attributeName, "srid", srid, "formatter", TRANSFORMER,
            "refetchSubmitted", true, "mapRendered", false,
            "nextStep", "Wait for the read-only result refresh, observe available presentations, then select Spatial."
                + " A submitted refresh is not evidence of a rendered map or complete geographic coverage.");
    }

    static boolean validAttributeName(@NotNull String name) {
        return !name.isBlank() && name.length() <= 128 && name.codePoints().allMatch(value ->
            Character.isLetterOrDigit(value) || value == '_' || value == ' ' || value == '-' || value == '.');
    }

    static int srid(@Nullable Object value) throws DBException {
        if (value == null) {
            return 4326;
        }
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
            || number.doubleValue() != number.intValue() || number.intValue() < 1 || number.intValue() > 999999) {
            throw new DBException("SRID must be an integer between 1 and 999999");
        }
        return number.intValue();
    }
}

/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ui.ai.workbench;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;
import org.jkiss.code.NotNull;

/** Pure image-data checks; no Display or shared native image resources are created. */
public final class WorkbenchIconThemeTest {
    public static void main(@NotNull String[] arguments) {
        RGB blue = new RGB(20, 130, 230);
        RGB red = new RGB(230, 40, 30);
        RGB orange = new RGB(245, 145, 20);
        RGB grey = new RGB(150, 150, 150);
        RGB white = new RGB(255, 255, 255);
        check(!WorkbenchIconTheme.blue(new RGB(190, 70, 230)), "Purple semantic colors must not count as blue");
        ImageData source = new ImageData(4, 1, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));
        RGB[] colors = {blue, red, orange, grey};
        for (int column = 0; column < colors.length; column++) {
            source.setPixel(column, 0, source.palette.getPixel(colors[column]));
            source.setAlpha(column, 0, 64 + column * 50);
        }
        ImageData tinted = WorkbenchIconTheme.recolor(source, white);
        check(tinted != null, "Blue icons should produce a variant");
        check(tinted.palette.getRGB(tinted.getPixel(0, 0)).equals(white), "Blue should become white");
        check(source.palette.getRGB(source.getPixel(0, 0)).equals(blue), "Original source must remain unchanged");
        for (int column = 0; column < colors.length; column++) {
            check(tinted.getAlpha(column, 0) == source.getAlpha(column, 0), "Per-pixel alpha must survive");
            if (column > 0) {
                check(tinted.palette.getRGB(tinted.getPixel(column, 0)).equals(colors[column]), "Semantic colors must survive");
            }
        }
        source.alphaData = null;
        source.alpha = 91;
        tinted = WorkbenchIconTheme.recolor(source, white);
        check(tinted.getAlpha(0, 0) == 91, "Global alpha must survive");
        source.transparentPixel = source.getPixel(0, 0);
        tinted = WorkbenchIconTheme.recolor(source, white);
        check(tinted.getAlpha(0, 0) == 0, "Transparent pixels must remain transparent");
        ImageData indexed = new ImageData(2, 1, 1, new PaletteData(blue, red));
        indexed.setPixel(0, 0, 0);
        indexed.setPixel(1, 0, 1);
        indexed.transparentPixel = 1;
        tinted = WorkbenchIconTheme.recolor(indexed, white);
        check(tinted.getAlpha(1, 0) == 0, "Indexed transparent pixels must survive palette conversion");
        ImageData masked = new ImageData(2, 1, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));
        masked.setPixel(0, 0, masked.palette.getPixel(blue));
        masked.setPixel(1, 0, masked.palette.getPixel(blue));
        masked.maskPad = 1;
        masked.maskData = new byte[] {(byte) 0x80};
        tinted = WorkbenchIconTheme.recolor(masked, white);
        check(tinted.getAlpha(0, 0) == 255 && tinted.getAlpha(1, 0) == 0, "Transparency masks must survive");
        ImageData noBlue = new ImageData(1, 1, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));
        noBlue.setPixel(0, 0, noBlue.palette.getPixel(red));
        check(WorkbenchIconTheme.recolor(noBlue, white) == null, "Unchanged icons must not allocate native variants");
        check(WorkbenchIconTheme.normalize(" #ffffff ").equals("#FFFFFF"), "Hex colors must normalize");
        check(WorkbenchIconTheme.normalize("reset").equals("default"), "Reset must normalize");
        try {
            WorkbenchIconTheme.normalize("white;script()");
            throw new AssertionError("Invalid icon color was accepted");
        } catch (IllegalArgumentException expected) {
            // Only bounded color values enter the renderer.
        }
        System.out.println("Icon tint tests passed; alpha, masks and semantic colors preserved");
    }

    private static void check(boolean condition, @NotNull String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

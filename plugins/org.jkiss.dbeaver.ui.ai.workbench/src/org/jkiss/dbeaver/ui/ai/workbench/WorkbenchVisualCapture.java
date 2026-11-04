/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ui.ai.workbench;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.ai.AIImageAttachment;
import org.jkiss.dbeaver.model.ai.AIVisualToolOutput;
import org.jkiss.dbeaver.ui.UIUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Captures only the foreground workbench window region after the normal tool confirmation. */
final class WorkbenchVisualCapture {
    static final int MAX_EDGE = 1600;
    private static final long MAX_SOURCE_PIXELS = 16_000_000;

    private WorkbenchVisualCapture() {
    }

    @NotNull
    static AIVisualToolOutput capture() throws IOException {
        Display display = Display.getCurrent();
        var window = UIUtils.getActiveWorkbenchWindow();
        if (display == null || window == null || window.getShell().isDisposed()
            || display.getActiveShell() != window.getShell() || display.getFocusControl() == null) {
            throw new IOException("Bring the intended DBeaver window to the foreground before sharing its image");
        }
        var shell = window.getShell();
        var bounds = shell.getBounds();
        if (!shell.isVisible() || bounds.width <= 0 || bounds.height <= 0
            || (long) bounds.width * bounds.height > MAX_SOURCE_PIXELS) {
            throw new IOException("The workbench window is unavailable or exceeds the capture budget");
        }
        var screen = display.getBounds();
        if (!screen.contains(bounds.x, bounds.y)
            || !screen.contains(bounds.x + bounds.width - 1, bounds.y + bounds.height - 1)) {
            throw new IOException("Move the complete DBeaver window onto a visible display before capture");
        }
        ImageData data = "cocoa".equals(SWT.getPlatform()) ? captureCocoa(shell) : captureScreen(display, shell);
        if (!hasVisualDetail(data)) {
            throw new IOException("Native capture returned a blank image; no visual verification is available");
        }
        byte[] bytes = encode(data);
        AIImageAttachment attachment = AIImageAttachment.fromBytes("DBeaver window.jpg", bytes);
        return new AIVisualToolOutput("Captured the foreground DBeaver content view at no more than 1600 pixels per edge. "
            + "The image may include visible SQL and rows. AppKit view snapshots can omit protected or remote-rendered layers "
            + "and window decorations; inspect the actual image before claiming a rendered result", attachment);
    }

    @NotNull
    private static ImageData captureScreen(@NotNull Display display, @NotNull Shell shell) {
        var bounds = shell.getBounds();
        Image image = new Image(display, bounds.width, bounds.height);
        try {
            GC gc = new GC(display);
            try {
                gc.copyArea(image, bounds.x, bounds.y);
            } finally {
                gc.dispose();
            }
            return image.getImageData();
        } finally {
            image.dispose();
        }
    }

    /** Public AppKit view rendering captures only our own window without screen-recording access. */
    @NotNull
    private static ImageData captureCocoa(@NotNull Shell shell) throws IOException {
        try {
            ClassLoader loader = Control.class.getClassLoader();
            Class<?> viewClass = Class.forName("org.eclipse.swt.internal.cocoa.NSView", true, loader);
            Class<?> rectangleClass = Class.forName("org.eclipse.swt.internal.cocoa.NSRect", true, loader);
            Class<?> bitmapClass = Class.forName("org.eclipse.swt.internal.cocoa.NSBitmapImageRep", true, loader);
            final Class<?> dataClass = Class.forName("org.eclipse.swt.internal.cocoa.NSData", true, loader);
            final Class<?> osClass = Class.forName("org.eclipse.swt.internal.cocoa.OS", true, loader);
            Object view = Control.class.getField("view").get(shell);
            viewClass.getMethod("displayIfNeeded").invoke(view);
            Object rectangle = viewClass.getMethod("bounds").invoke(view);
            Object bitmap = viewClass.getMethod("bitmapImageRepForCachingDisplayInRect", rectangleClass).invoke(view, rectangle);
            if (bitmap == null) {
                throw new IOException("AppKit cannot allocate a snapshot of this DBeaver view");
            }
            viewClass.getMethod("cacheDisplayInRect", rectangleClass, bitmapClass).invoke(view, rectangle, bitmap);
            long selector = ((Number) osClass.getMethod("sel_registerName", String.class)
                .invoke(null, "TIFFRepresentation")).longValue();
            long bitmapId = bitmapClass.getField("id").getLong(bitmap);
            long dataId = ((Number) osClass.getMethod("objc_msgSend", long.class, long.class)
                .invoke(null, bitmapId, selector)).longValue();
            if (dataId == 0) {
                throw new IOException("AppKit returned no snapshot image data");
            }
            Object nativeData = dataClass.getConstructor(long.class).newInstance(dataId);
            long length = ((Number) dataClass.getMethod("length").invoke(nativeData)).longValue();
            if (length <= 0 || length > 64L * 1024 * 1024) {
                throw new IOException("AppKit snapshot exceeds the in-memory capture budget");
            }
            byte[] bytes = new byte[(int) length];
            dataClass.getMethod("getBytes", byte[].class).invoke(nativeData, (Object) bytes);
            ImageData image = new ImageData(new ByteArrayInputStream(bytes));
            fit(image.width, image.height);
            return image;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IOException("AppKit own-window capture failed; no screenshot was shared", error);
        }
    }

    @NotNull
    static byte[] encode(@NotNull ImageData source) throws IOException {
        int[] size = fit(source.width, source.height);
        ImageData data = source.width == size[0] && source.height == size[1]
            ? source : source.scaledTo(size[0], size[1]);
        for (int quality : new int[] {85, 65, 45, 25}) {
            ImageLoader loader = new ImageLoader();
            loader.data = new ImageData[] {data};
            loader.compression = quality;
            BoundedBytes output = new BoundedBytes(AIVisualToolOutput.MAX_BYTES);
            try {
                loader.save(output, SWT.IMAGE_JPEG);
                return output.bytes();
            } catch (RuntimeException error) {
                if (!output.exceeded()) {
                    throw new IOException("Native image encoding failed", error);
                }
            }
        }
        throw new IOException("Captured image exceeds the 512 KiB sharing limit");
    }

    @NotNull
    static int[] fit(int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height > MAX_SOURCE_PIXELS) {
            throw new IllegalArgumentException("Invalid capture dimensions");
        }
        double scale = Math.min(1, (double) MAX_EDGE / Math.max(width, height));
        return new int[] {Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale))};
    }

    static boolean hasVisualDetail(@NotNull ImageData image) {
        int first = image.getPixel(0, 0);
        for (int row = 0; row < image.height; row += Math.max(1, image.height / 32)) {
            for (int column = 0; column < image.width; column += Math.max(1, image.width / 32)) {
                if (image.getPixel(column, row) != first) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class BoundedBytes extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;
        private boolean exceeded;

        private BoundedBytes(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            if (bytes.size() >= limit) {
                exceeded = true;
                throw new IOException("Image budget exceeded");
            }
            bytes.write(value);
        }

        @Override
        public void write(@NotNull byte[] values, int offset, int length) throws IOException {
            if (length > limit - bytes.size()) {
                exceeded = true;
                throw new IOException("Image budget exceeded");
            }
            bytes.write(values, offset, length);
        }

        @NotNull
        private byte[] bytes() {
            return bytes.toByteArray();
        }

        private boolean exceeded() {
            return exceeded;
        }
    }
}

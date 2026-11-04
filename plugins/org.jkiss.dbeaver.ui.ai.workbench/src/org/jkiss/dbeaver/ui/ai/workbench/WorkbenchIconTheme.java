/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ui.ai.workbench;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Item;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.Widget;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reversible blue-icon tint without modifying shared image resources or reading cell contents. */
public final class WorkbenchIconTheme {
    private static final int MAX_IMAGES = 1024;
    private static final int MAX_SLOTS = 4096;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Map<Image, Variant> VARIANTS = new IdentityHashMap<>();
    private static final Map<Image, Boolean> UNCHANGED = new IdentityHashMap<>();
    private static final Map<Widget, List<Slot>> SLOTS = new IdentityHashMap<>();
    private static final DisposeListener DISPOSE = event -> releaseWidget(event.widget, false);
    private static Display installedDisplay;
    private static String tint;
    private static int retainedBytes;
    private static int slotCount;
    private static int virtualTrees;
    private static boolean truncated;

    private WorkbenchIconTheme() {
    }

    @NotNull
    public static Map<String, Object> apply(@NotNull Display display, @NotNull String requestedTint) {
        requireDisplay(display);
        String normalized = normalize(requestedTint);
        if (!normalized.equals(tint)) {
            reset();
        }
        if (normalized.equals("default")) {
            return Map.of("iconTint", "default", "restored", true, "scope", "native blue icon pixels");
        }
        installedDisplay = display;
        tint = normalized;
        refresh(display);
        return Map.of("iconTint", tint, "styledIconSlots", slotCount, "imageVariants", VARIANTS.size(),
            "retainedImageBytes", retainedBytes, "skippedVirtualTrees", virtualTrees,
            "truncated", truncated, "scope", "native toolbar, menu, tab, button, label and non-virtual tree blue icon pixels");
    }

    public static void refresh(@NotNull Display display) {
        requireDisplay(display);
        if (tint == null || display != installedDisplay) {
            return;
        }
        ArrayDeque<Widget> queue = new ArrayDeque<>();
        Map<Widget, Boolean> visited = new IdentityHashMap<>();
        for (Shell shell : display.getShells()) {
            enqueue(queue, shell);
        }
        int scanned = 0;
        virtualTrees = 0;
        while (!queue.isEmpty()) {
            if (++scanned > MAX_SLOTS || queue.size() > MAX_SLOTS) {
                truncated = true;
                break;
            }
            Widget widget = queue.removeFirst();
            if (widget.isDisposed() || visited.put(widget, Boolean.TRUE) != null) {
                continue;
            }
            if (widget instanceof Item || widget instanceof Label || widget instanceof Button) {
                style(widget, Kind.NORMAL);
                if (widget instanceof ToolItem) {
                    style(widget, Kind.HOT);
                    style(widget, Kind.DISABLED);
                }
            }
            appendChildren(widget, queue);
        }
    }

    private static void enqueue(@NotNull ArrayDeque<Widget> queue, @NotNull Widget widget) {
        if (queue.size() < MAX_SLOTS) {
            queue.addLast(widget);
        } else {
            truncated = true;
        }
    }

    private static void appendChildren(@NotNull Widget widget, @NotNull ArrayDeque<Widget> queue) {
        if (widget instanceof Shell shell && shell.getMenuBar() != null) {
            enqueue(queue, shell.getMenuBar());
        }
        if (widget instanceof Control control && control.getMenu() != null) {
            enqueue(queue, control.getMenu());
        }
        if (widget instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                enqueue(queue, child);
            }
        }
        if (widget instanceof CTabFolder folder) {
            for (CTabItem item : folder.getItems()) {
                enqueue(queue, item);
            }
        } else if (widget instanceof TabFolder folder) {
            for (TabItem item : folder.getItems()) {
                enqueue(queue, item);
            }
        } else if (widget instanceof ToolBar toolbar) {
            for (ToolItem item : toolbar.getItems()) {
                enqueue(queue, item);
            }
        } else if (widget instanceof Menu menu) {
            for (MenuItem item : menu.getItems()) {
                enqueue(queue, item);
            }
        } else if (widget instanceof MenuItem item && item.getMenu() != null) {
            enqueue(queue, item.getMenu());
        } else if (widget instanceof Tree tree) {
            if ((tree.getStyle() & SWT.VIRTUAL) != 0) {
                virtualTrees++;
            } else {
                int count = Math.min(tree.getItemCount(), MAX_SLOTS - queue.size());
                truncated |= count < tree.getItemCount();
                for (int index = 0; index < count; index++) {
                    enqueue(queue, tree.getItem(index));
                }
            }
        } else if (widget instanceof TreeItem item) {
            int count = Math.min(item.getItemCount(), MAX_SLOTS - queue.size());
            truncated |= count < item.getItemCount();
            for (int index = 0; index < count; index++) {
                enqueue(queue, item.getItem(index));
            }
        }
    }

    private static void style(@NotNull Widget widget, @NotNull Kind kind) {
        List<Slot> slots = SLOTS.get(widget);
        Slot existing = slots == null ? null : slots.stream().filter(slot -> slot.kind() == kind).findFirst().orElse(null);
        Image current = image(widget, kind);
        if (existing != null) {
            if (current == existing.variant().image) {
                return;
            }
            slots.remove(existing);
            slotCount--;
            release(existing.variant());
        }
        if (current == null || current.isDisposed() || UNCHANGED.containsKey(current)) {
            return;
        }
        if (slotCount == MAX_SLOTS) {
            truncated = true;
            return;
        }
        Variant variant = VARIANTS.get(current);
        if (variant == null) {
            var bounds = current.getBounds();
            if (bounds.width > 256 || bounds.height > 256) {
                truncated = true;
                return;
            }
            ImageData data = current.getImageData();
            long bytes = (long) data.width * data.height * 5;
            if (data.width > 256 || data.height > 256 || bytes > MAX_BYTES - retainedBytes || VARIANTS.size() == MAX_IMAGES) {
                truncated = true;
                return;
            }
            ImageData changed = recolor(data, parse(tint));
            if (changed == null) {
                if (UNCHANGED.size() < MAX_IMAGES) {
                    UNCHANGED.put(current, Boolean.TRUE);
                }
                return;
            }
            variant = new Variant(current, new Image(installedDisplay, changed), (int) bytes);
            VARIANTS.put(current, variant);
            retainedBytes += (int) bytes;
        }
        if (slots == null) {
            slots = new ArrayList<>();
            SLOTS.put(widget, slots);
            widget.addDisposeListener(DISPOSE);
        }
        slots.add(new Slot(kind, current, variant));
        variant.references++;
        slotCount++;
        setImage(widget, kind, variant.image);
    }

    /** Creates direct-color data only when blue pixels changed, preserving all transparency forms. */
    @Nullable
    static ImageData recolor(@NotNull ImageData original, @NotNull RGB target) {
        ImageData result = new ImageData(original.width, original.height, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));
        ImageData mask = original.maskData == null ? null : original.getTransparencyMask();
        boolean changed = false;
        for (int row = 0; row < original.height; row++) {
            for (int column = 0; column < original.width; column++) {
                int pixel = original.getPixel(column, row);
                RGB color = original.palette.getRGB(pixel);
                if (blue(color)) {
                    color = target;
                    changed = true;
                }
                result.setPixel(column, row, result.palette.getPixel(color));
                int alpha = original.alphaData != null ? original.getAlpha(column, row) : original.alpha >= 0 ? original.alpha : 255;
                if (pixel == original.transparentPixel || mask != null && mask.getPixel(column, row) == 0) {
                    alpha = 0;
                }
                result.setAlpha(column, row, alpha);
            }
        }
        return changed ? result : null;
    }

    static boolean blue(@NotNull RGB color) {
        return color.blue >= 80 && color.blue >= color.red + 24 && color.blue >= color.green + 8
            && color.red - color.green <= (color.blue - color.green) / 3;
    }

    @NotNull
    static String normalize(@NotNull String value) {
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (normalized.equals("DEFAULT") || normalized.equals("RESET")) {
            return "default";
        }
        if (!normalized.matches("#[0-9A-F]{6}")) {
            throw new IllegalArgumentException("Icon tint requires #RRGGBB or default");
        }
        return normalized;
    }

    @NotNull
    private static RGB parse(@NotNull String value) {
        int rgb = Integer.parseInt(value.substring(1), 16);
        return new RGB(rgb >>> 16 & 255, rgb >>> 8 & 255, rgb & 255);
    }

    @Nullable
    private static Image image(@NotNull Widget widget, @NotNull Kind kind) {
        if (widget instanceof ToolItem item) {
            return switch (kind) {
                case NORMAL -> item.getImage();
                case HOT -> item.getHotImage();
                case DISABLED -> item.getDisabledImage();
            };
        }
        if (widget instanceof Item item) {
            return item.getImage();
        }
        if (widget instanceof Button button) {
            return button.getImage();
        }
        if (widget instanceof Label label) {
            return label.getImage();
        }
        throw new IllegalArgumentException("Unsupported native icon holder");
    }

    private static void setImage(@NotNull Widget widget, @NotNull Kind kind, @Nullable Image image) {
        if (widget instanceof ToolItem item) {
            switch (kind) {
                case NORMAL -> item.setImage(image);
                case HOT -> item.setHotImage(image);
                case DISABLED -> item.setDisabledImage(image);
                default -> throw new IllegalArgumentException("Unknown icon kind");
            }
        } else if (widget instanceof Item item) {
            item.setImage(image);
        } else if (widget instanceof Button button) {
            button.setImage(image);
        } else if (widget instanceof Label label) {
            label.setImage(image);
        } else {
            throw new IllegalArgumentException("Unsupported native icon holder");
        }
    }

    private static void releaseWidget(@NotNull Widget widget, boolean restore) {
        List<Slot> slots = SLOTS.remove(widget);
        if (slots == null) {
            return;
        }
        if (!widget.isDisposed()) {
            widget.removeDisposeListener(DISPOSE);
        }
        for (Slot slot : slots) {
            if (restore && !widget.isDisposed() && image(widget, slot.kind()) == slot.variant().image) {
                setImage(widget, slot.kind(), slot.original().isDisposed() ? null : slot.original());
            }
            release(slot.variant());
            slotCount--;
        }
    }

    private static void release(@NotNull Variant variant) {
        if (--variant.references == 0) {
            VARIANTS.remove(variant.original);
            retainedBytes -= variant.bytes;
            if (!variant.image.isDisposed()) {
                variant.image.dispose();
            }
        }
    }

    public static void reset() {
        if (installedDisplay == null) {
            return;
        }
        if (!installedDisplay.isDisposed()) {
            requireDisplay(installedDisplay);
            for (Widget widget : new ArrayList<>(SLOTS.keySet())) {
                releaseWidget(widget, true);
            }
        }
        for (Variant variant : VARIANTS.values()) {
            if (!variant.image.isDisposed()) {
                variant.image.dispose();
            }
        }
        VARIANTS.clear();
        UNCHANGED.clear();
        SLOTS.clear();
        installedDisplay = null;
        tint = null;
        retainedBytes = 0;
        slotCount = 0;
        truncated = false;
    }

    public static void stop() {
        Display display = installedDisplay;
        if (display == null || display.isDisposed()) {
            return;
        }
        if (Display.getCurrent() == display) {
            reset();
        } else {
            display.asyncExec(WorkbenchIconTheme::reset);
        }
    }

    private static void requireDisplay(@NotNull Display display) {
        if (display.isDisposed() || Display.getCurrent() != display) {
            throw new IllegalStateException("Icon styling requires the SWT display thread");
        }
    }

    private enum Kind {
        NORMAL, HOT, DISABLED
    }

    private record Slot(@NotNull Kind kind, @NotNull Image original, @NotNull Variant variant) {
    }

    private static final class Variant {
        private final Image original;
        private final Image image;
        private final int bytes;
        private int references;

        private Variant(@NotNull Image original, @NotNull Image image, int bytes) {
            this.original = original;
            this.image = image;
            this.bytes = bytes;
        }
    }
}

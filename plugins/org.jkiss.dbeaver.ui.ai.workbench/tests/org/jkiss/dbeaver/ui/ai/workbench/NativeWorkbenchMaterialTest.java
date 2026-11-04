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
import org.eclipse.swt.widgets.Control;
import org.jkiss.code.NotNull;

import java.util.List;

/** Headless policy and installed Cocoa ABI checks; native window rendering remains an integration check. */
public final class NativeWorkbenchMaterialTest {
    private NativeWorkbenchMaterialTest() {
    }

    public static void main(@NotNull String[] args) throws Exception {
        check(NativeWorkbenchMaterial.supportedPlatform("cocoa"), "Cocoa capability");
        check(!NativeWorkbenchMaterial.supportedPlatform("gtk"), "GTK must report explicit fallback");
        check(!NativeWorkbenchMaterial.supportedPlatform("win32"), "Windows must report explicit fallback");
        check(!NativeWorkbenchMaterial.supportedPlatform(null), "Unknown platform must not load native code");
        check(NativeWorkbenchMaterial.materialAllowed("cocoa", false), "Material allowed when transparency is enabled");
        check(!NativeWorkbenchMaterial.materialAllowed("cocoa", true), "Respect Reduce Transparency");
        check(!NativeWorkbenchMaterial.materialAllowed("cocoa", null), "Unknown accessibility policy fails closed");
        check(!NativeWorkbenchMaterial.materialAllowed("gtk", false), "No alpha imitation on other platforms");
        check(NativeWorkbenchMaterial.selectorAllowed("setMaterial:"), "Public material selector");
        check(NativeWorkbenchMaterial.selectorAllowed("accessibilityDisplayShouldReduceTransparency"), "Public preference selector");
        check(!NativeWorkbenchMaterial.selectorAllowed("performSelector:"), "No arbitrary selector execution");
        check(!NativeWorkbenchMaterial.selectorAllowed("setAlphaValue:"), "No window-alpha imitation");
        check(!NativeWorkbenchMaterial.selectorAllowed("setContentView:"), "No content reparenting");
        check(!NativeWorkbenchMaterial.selectorAllowed("setTextColor:"), "No semantic foreground modification");
        check(!NativeWorkbenchMaterial.selectorAllowed("setStringValue:"), "No document or field value changes");
        check(NativeWorkbenchMaterial.selectorAllowed("setStyle:"), "Public clear glass style");
        check(NativeWorkbenchMaterial.selectorAllowed("setDrawsBackground:"), "Public scroll and clip background setter");
        check(NativeWorkbenchMaterial.selectorAllowed("setUnderPageBackgroundColor:"), "Public WebKit under-page color");
        for (String name : new String[] {"NSScrollView", "NSClipView", "NSTextView", "NSTableView", "NSOutlineView"}) {
            check(NativeWorkbenchMaterial.surfaceClassAllowed(name), "Finite native background surface class");
        }
        check(NativeWorkbenchMaterial.surfaceClassAllowed("WebView"), "Installed legacy WebView background target");
        verifyBrowserTraversal();
        check(!NativeWorkbenchMaterial.surfaceClassAllowed("NSView"), "Generic native views are not background targets");
        check(!NativeWorkbenchMaterial.surfaceClassAllowed(null), "Unknown native surface class rejected");
        check(!NativeWorkbenchMaterial.prepareControl(null), "No background operation without a native control");
        check(NativeWorkbenchMaterial.applyRegion(null).get("applied").equals(false), "No region without a native tab folder");
        check(!NativeWorkbenchMaterial.selectorAllowed(null), "Null selector rejection");
        check(!NativeWorkbenchMaterial.isApplied(null), "No material without a tracked live shell");
        NativeWorkbenchMaterial.reset();
        NativeWorkbenchMaterial.stop();
        if (NativeWorkbenchMaterial.supportedPlatform(SWT.getPlatform())) {
            verifyCocoaSignatures();
        }
        System.out.println("Native material policy and available Cocoa ABI checks passed; rendering not exercised");
    }

    private static void verifyBrowserTraversal() throws ReflectiveOperationException {
        check(NativeWorkbenchMaterial.boundedBrowserViews(0, view -> List.of()).isEmpty(), "Null native root");
        List<Long> views = NativeWorkbenchMaterial.boundedBrowserViews(1, view -> switch ((int) view) {
            case 1 -> List.of(2L, 3L);
            case 2 -> List.of(1L, 4L);
            default -> List.of();
        });
        check(views.equals(List.of(1L, 2L, 3L, 4L)), "Browser descendants discovered once in breadth-first order");
        List<Long> chain = NativeWorkbenchMaterial.boundedBrowserViews(1, view -> List.of(view + 1));
        check(chain.equals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L)), "Browser descendant depth bounded");
        List<Long> broad = NativeWorkbenchMaterial.boundedBrowserViews(1,
            view -> java.util.stream.LongStream.range(2, 1000).boxed().toList());
        check(broad.size() == 64 && broad.get(63).longValue() == 64, "Browser traversal and queue bounded");
        try {
            views.add(99L);
            throw new AssertionError("Browser view identities must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Callers cannot change the bounded traversal result.
        }
        check(NativeWorkbenchMaterial.selectorAllowed("subviews"), "Public descendant enumeration");
        check(NativeWorkbenchMaterial.selectorAllowed("objectAtIndex:"), "Bounded public array access");
        check(!NativeWorkbenchMaterial.selectorAllowed("_setDrawsBackground:"), "Private WebKit API rejected");
    }

    private static void verifyCocoaSignatures() throws ReflectiveOperationException {
        ClassLoader loader = Control.class.getClassLoader();
        // Metadata reflection does not initialize OS or call AppKit off the native display thread.
        Class<?> os = Class.forName("org.eclipse.swt.internal.cocoa.OS", false, loader);
        final Class<?> view = Class.forName("org.eclipse.swt.internal.cocoa.NSView", false, loader);
        final Class<?> rectangle = Class.forName("org.eclipse.swt.internal.cocoa.NSRect", false, loader);
        final Class<?> color = Class.forName("org.eclipse.swt.internal.cocoa.NSColor", false, loader);
        final Class<?> window = Class.forName("org.eclipse.swt.internal.cocoa.NSWindow", false, loader);
        os.getMethod("objc_getClass", String.class);
        os.getMethod("sel_registerName", String.class);
        os.getMethod("objc_msgSend", long.class, long.class);
        os.getMethod("objc_msgSend", long.class, long.class, long.class);
        os.getMethod("objc_msgSend", long.class, long.class, boolean.class);
        os.getMethod("objc_msgSend", long.class, long.class, double.class);
        os.getMethod("objc_msgSend_bool", long.class, long.class);
        os.getMethod("objc_msgSend_bool", long.class, long.class, long.class);
        Control.class.getField("view");
        view.getField("id");
        view.getConstructor(long.class);
        view.getMethod("bounds");
        view.getMethod("subviews");
        view.getMethod("setFrame", rectangle);
        rectangle.getConstructor();
        for (String field : new String[] {"x", "y", "width", "height"}) {
            check(rectangle.getField(field).getType().equals(double.class), "Native rectangle scalar ABI");
        }
        view.getMethod("initWithFrame", rectangle);
        view.getMethod("addSubview", view, long.class, view);
        view.getMethod("setAutoresizingMask", long.class);
        view.getMethod("window");
        view.getMethod("removeFromSuperview");
        window.getMethod("setBackgroundColor", color);
        window.getMethod("setOpaque", boolean.class);
        color.getMethod("clearColor");
        color.getConstructor(long.class);
    }

    private static void check(boolean condition, @NotNull String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

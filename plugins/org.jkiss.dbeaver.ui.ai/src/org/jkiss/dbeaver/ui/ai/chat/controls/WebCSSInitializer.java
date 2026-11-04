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
package org.jkiss.dbeaver.ui.ai.chat.controls;

import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.ColorRegistry;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.internal.IWorkbenchThemeConstants;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.ui.BaseThemeSettings;
import org.jkiss.dbeaver.ui.UIStyles;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.ai.chat.internal.AIChatThemeSettings;
import org.jkiss.dbeaver.ui.ai.internal.AIUIActivator;
import org.jkiss.dbeaver.ui.browser.LocalResourceHttpServer;
import org.jkiss.dbeaver.utils.RuntimeUtils;
import org.osgi.framework.Bundle;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class WebCSSInitializer implements AutoCloseable {
    private static final String WEB_ROOT = "web";
    private static final String WEB_CSS_PATH = "styles.css";
    private static final String WEB_HTML_PATH = "index.html";
    private static final String EXTRA_HEAD_PLACEHOLDER = "<!--{{EXTRA_HEAD}}-->";
    private static final Set<String> THEME_COLOR_KEYS = Set.of(
        "BACKGROUND_COLOR", "BACKGROUND_COLOR_INACTIVE", "TEXT_COLOR", "PROMPT_BACKGROUND",
        "PROMPT_BORDER", "WARNING_BACKGROUND", "ERROR_BACKGROUND", "HYPERLINK_COLOR", "BORDER_COLOR");

    private final LocalResourceHttpServer.Handle server;
    private final Map<String, String> resourceUrls = new HashMap<>();
    private volatile Map<String, String> cssValues;
    private volatile Map<String, String> themeColors = Map.of();
    private volatile Map<String, Object> designTokens = Map.of();
    private long themeRevision;

    public WebCSSInitializer() throws IOException {
        cssValues = fillValues();
        server = LocalResourceHttpServer.acquire();
        try {
            for (Bundle bundle : getResourceBundles()) {
                server.addBundleResources(bundle, WEB_ROOT, this::registerWebResource);
            }
        } catch (RuntimeException e) {
            server.close();
            throw e;
        }
    }

    @NotNull
    protected List<Bundle> getResourceBundles() {
        return List.of(AIUIActivator.getInstance().getBundle());
    }

    @NotNull
    protected String getExtraHeadContent() {
        return "";
    }

    private void registerWebResource(@NotNull String resource, @NotNull URL url) {
        switch (resource) {
            case WEB_CSS_PATH -> server.addTextResource(
                resource,
                LocalResourceHttpServer.Resource.of(url::openStream)
                    .map(this::updateThemeValues)
                    .map(content -> content + designStyles(designTokens))
            );
            case WEB_HTML_PATH -> server.addTextResource(
                resource,
                LocalResourceHttpServer.Resource.of(url::openStream)
                    .map(this::updateThemeValues)
                    .map(content -> content.replace(EXTRA_HEAD_PLACEHOLDER, getExtraHeadContent()))
            );
            default -> server.addResource(resource, url::openStream);
        }
    }

    @NotNull
    public String getWebHtmlPath() {
        return server.getUrl(WEB_HTML_PATH);
    }

    @NotNull
    public String getWebPath() {
        return server.getBaseUrl();
    }

    public void refreshTheme() {
        cssValues = fillValues();
        themeRevision++;
    }

    /** Returns immutable explicit color overrides; an empty map follows the native theme. */
    @NotNull
    public Map<String, String> getThemeColors() {
        return themeColors;
    }

    /** Applies fixed color tokens only, preserving fonts, content and the native color-scheme choice. */
    public void setThemeColors(@NotNull Map<String, String> colors) {
        if (!UIUtils.isUIThread()) {
            throw new IllegalStateException("Chat theme colors require the SWT display thread");
        }
        themeColors = validateThemeColors(colors);
        refreshTheme();
    }

    /** Validates a palette without constructing a browser, stylesheet server or native control. */
    @NotNull
    public static Map<String, String> validateThemeColors(@NotNull Map<String, String> colors) {
        if (colors == null || colors.size() > THEME_COLOR_KEYS.size()) {
            throw new IllegalArgumentException("Invalid chat theme colors");
        }
        Map<String, String> validated = new HashMap<>();
        for (var entry : colors.entrySet()) {
            String value = entry.getValue();
            if (entry.getKey() == null || !THEME_COLOR_KEYS.contains(entry.getKey())
                || value == null || !value.matches("#[0-9a-fA-F]{6}")) {
                throw new IllegalArgumentException("Chat theme accepts supported #RRGGBB colors only");
            }
            validated.put(entry.getKey(), value.toUpperCase(Locale.ROOT));
        }
        return Map.copyOf(validated);
    }

    /** Returns explicit material/layout tokens; empty follows the original chat design. */
    @NotNull
    public Map<String, Object> getDesignTokens() {
        return designTokens;
    }

    /** Applies typed design tokens to the existing stylesheet without accepting CSS, URLs or script. */
    public void setDesignTokens(@NotNull Map<String, Object> tokens) {
        if (!UIUtils.isUIThread()) {
            throw new IllegalStateException("Chat design tokens require the SWT display thread");
        }
        if (tokens == null) {
            throw new IllegalArgumentException("Invalid design tokens");
        }
        designTokens = tokens.isEmpty() ? Map.of() : validateDesignTokens(tokens);
        refreshTheme();
    }

    /** Describes the complete set of design tokens supported by this renderer. */
    @NotNull
    public static Map<String, Object> getDesignSchema() {
        return Map.of(
            "material", Map.of("type", "string", "values", List.of("flat", "glass"), "default", "flat"),
            "density", Map.of("type", "string", "values", List.of("compact", "comfortable"), "default", "comfortable"),
            "fontScale", Map.of("type", "number", "min", 0.85, "max", 1.3, "default", 1.0),
            "radius", Map.of("type", "integer", "min", 0, "max", 20, "default", 8),
            "opacity", Map.of("type", "number", "min", 0.02, "max", 0.2, "default", 0.02),
            "blur", Map.of("type", "integer", "min", 0, "max", 8, "default", 0),
            "iconTint", Map.of("type", "string", "format", "default or #RRGGBB", "default", "default",
                "browser", "monochrome SQL action icons only"));
    }

    /** Validates without native controls. Defaults are optically clear and have no blur or animation. */
    @NotNull
    public static Map<String, Object> validateDesignTokens(@NotNull Map<String, Object> tokens) {
        if (tokens == null || tokens.size() > 7 || !getDesignSchema().keySet().containsAll(tokens.keySet())) {
            throw new IllegalArgumentException("Unsupported design token");
        }
        return Map.of("material", choice(tokens, "material", "flat", List.of("flat", "glass")),
            "density", choice(tokens, "density", "comfortable", List.of("compact", "comfortable")),
            "fontScale", numeric(tokens, "fontScale", 1.0, 0.85, 1.3),
            "radius", integer(tokens, "radius", 8, 0, 20),
            "opacity", numeric(tokens, "opacity", 0.02, 0.02, 0.2),
            "blur", integer(tokens, "blur", 0, 0, 8), "iconTint", iconTint(tokens.getOrDefault("iconTint", "default")));
    }

    @NotNull
    private static String iconTint(@Nullable Object value) {
        if (value instanceof String text && (text.equals("default") || text.matches("#[0-9a-fA-F]{6}"))) {
            return text.equals("default") ? text : text.toUpperCase(Locale.ROOT);
        }
        throw new IllegalArgumentException("Icon tint must be default or #RRGGBB");
    }

    @NotNull
    private static String choice(@NotNull Map<String, Object> tokens, @NotNull String key,
        @NotNull String fallback, @NotNull List<String> choices) {
        Object value = tokens.getOrDefault(key, fallback);
        if (!(value instanceof String text) || text.length() > 16 || !choices.contains(text.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Invalid design choice");
        }
        return text.toLowerCase(Locale.ROOT);
    }

    private static double numeric(@NotNull Map<String, Object> tokens, @NotNull String key,
        double fallback, double min, double max) {
        Object value = tokens.getOrDefault(key, fallback);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
            || number.doubleValue() < min || number.doubleValue() > max) {
            throw new IllegalArgumentException("Design number outside supported range");
        }
        return number.doubleValue();
    }

    private static int integer(@NotNull Map<String, Object> tokens, @NotNull String key,
        int fallback, int min, int max) {
        double value = numeric(tokens, key, fallback, min, max);
        if (Math.rint(value) != value) {
            throw new IllegalArgumentException("Design token must be an integer");
        }
        return (int) value;
    }

    /** Generates fixed material rules from validated primitives; this is not a CSS execution endpoint. */
    @NotNull
    public static String designStyles(@NotNull Map<String, Object> tokens) {
        if (tokens.isEmpty()) {
            return "";
        }
        Map<String, Object> values = validateDesignTokens(tokens);
        int radius = (int) values.get("radius");
        int gap = values.get("density").equals("compact") ? 3 : 7;
        String rules = "\n#chat{gap:" + gap + "px;}\n.message{padding:" + gap + "px;}"
            + ".message,.confirmation-card,.attachment,.sql-form{border-radius:" + radius + "px;}"
            + ".confirmation-card{padding:" + (gap + 3) + "px;gap:" + gap + "px;}"
            + iconStyles((String) values.get("iconTint"));
        if (!values.get("material").equals("glass")) {
            return rules;
        }
        String surfaces = "#chat-meta,.message.right,.confirmation-card,.attachment";
        String opacity = values.get("opacity").toString();
        int blur = (int) values.get("blur");
        String filter = blur == 0 ? "none" : "blur(" + blur + "px)";
        return rules + "\nhtml,body{background:transparent;}#chat-root{background:linear-gradient(135deg,"
            + "color-mix(in srgb,var(--hyperlink-color) 6%,transparent),transparent 45%,"
            + "color-mix(in srgb,var(--hyperlink-color) 3%,transparent));}"
            + surfaces + "{background:color-mix(in srgb,var(--background-color) "
            + (((Number) values.get("opacity")).doubleValue() * 100) + "%,transparent);"
            + "border:1px solid rgba(255,255,255,.38);border-bottom-color:rgba(0,0,0,.10);"
            + "border-radius:" + radius + "px;"
            + "box-shadow:inset 0 1px 0 rgba(255,255,255,.58),inset 0 -1px 0 rgba(0,0,0,.05),"
            + "0 3px 10px rgba(0,0,0,.08);"
            + "backdrop-filter:" + filter + ";-webkit-backdrop-filter:" + filter + ";"
            + "--workbench-surface-opacity:" + opacity + ";}"
            + "@media(prefers-reduced-transparency:reduce){" + surfaces
            + "{background:var(--background-color);backdrop-filter:none;-webkit-backdrop-filter:none;box-shadow:none;}}";
    }

    @NotNull
    private static String iconStyles(@NotNull String tint) {
        if (tint.equals("default")) {
            return ".sql-button-img{filter:none;}";
        }
        if (!tint.substring(1, 3).equals(tint.substring(3, 5)) || !tint.substring(1, 3).equals(tint.substring(5, 7))) {
            return "";
        }
        double level = Integer.parseInt(tint.substring(1, 3), 16) / 255.0;
        return ".sql-button-img{filter:grayscale(1) brightness(0) invert(" + level + ");}";
    }

    @NotNull
    public String getThemeStylesheetPath() {
        return server.getUrl(WEB_CSS_PATH) + "?theme=" + themeRevision;
    }

    @NotNull
    String getResourceUrl(@NotNull DBPImage image) throws IOException {
        String location = image.getLocation();
        String resourceUrl = resourceUrls.get(location);
        if (resourceUrl != null) {
            return resourceUrl;
        }
        Path file = RuntimeUtils.getPlatformFile(location);
        String fileName = file.getFileName().toString();
        int extensionIndex = fileName.lastIndexOf('.');
        String extension = extensionIndex >= 0 ? fileName.substring(extensionIndex) : "";
        String resourcePath = "external/" + UUID.randomUUID() + extension;
        server.addResource(resourcePath, () -> Files.newInputStream(file));
        resourceUrl = server.getUrl(resourcePath);
        resourceUrls.put(location, resourceUrl);
        return resourceUrl;
    }

    @Override
    public void close() {
        server.close();
    }

    @NotNull
    private String updateThemeValues(@NotNull String content) {
        for (var entry : cssValues.entrySet()) {
            content = content.replace(entry.getKey(), entry.getValue());
        }
        return content;
    }

    @NotNull
    private Map<String, String> fillValues() {
        final ColorRegistry registry = UIUtils.getColorRegistry();
        String promptBackground = colorToHex(AIChatThemeSettings.instance.promptBackgroundColor);
        String promptBorder = colorToHex(AIChatThemeSettings.instance.promptBorderColor);
        String waningBackground = colorToHex(BaseThemeSettings.instance.colorWarning);
        String errorBackground = colorToHex(BaseThemeSettings.instance.colorError);
        String chatBackground = colorToHex(UIStyles.getDefaultTextBackground());
        String chatInactiveBackground = colorToHex(UIStyles.getDefaultWidgetBackground());
        String textColor = colorToHex(registry.get(IWorkbenchThemeConstants.ACTIVE_TAB_TEXT_COLOR));
        String themeSchema = UIStyles.isDarkTheme() ? "dark" : "light";

        String hyperLinkColor = colorToHex(registry.get(JFacePreferences.HYPERLINK_COLOR));
        String borderColor = colorToHex(Display.getDefault().getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));

        FontData[] mainFont = BaseThemeSettings.instance.baseFont.getFontData();
        double fontScale = designTokens.isEmpty() ? 1.0 : ((Number) designTokens.get("fontScale")).doubleValue();
        String fontSize = getFontSize(mainFont, fontScale);
        String fontFamily = getFontFamily(mainFont, "Arial");

        FontData[] monoFont = BaseThemeSettings.instance.monospaceFont.getFontData();
        String monoFontSize = getFontSize(monoFont, fontScale);
        String monoFontFamily = getFontFamily(monoFont, "monospace");

        Map<String, String> values = new HashMap<>(Map.ofEntries(
            Map.entry("{{BACKGROUND_COLOR}}", chatBackground),
            Map.entry("{{BACKGROUND_COLOR_INACTIVE}}", chatInactiveBackground),
            Map.entry("{{TEXT_COLOR}}", textColor),
            Map.entry("{{PROMPT_BACKGROUND}}", promptBackground),
            Map.entry("{{PROMPT_BORDER}}", promptBorder),
            Map.entry("{{WARNING_BACKGROUND}}", waningBackground),
            Map.entry("{{ERROR_BACKGROUND}}", errorBackground),
            Map.entry("{{FONT_SIZE}}", fontSize),
            Map.entry("{{FONT_FAMILY}}", fontFamily),
            Map.entry("{{MONOSPACE_FONT_SIZE}}", monoFontSize),
            Map.entry("{{MONOSPACE_FONT_FAMILY}}", monoFontFamily),
            Map.entry("{{HYPERLINK_COLOR}}", hyperLinkColor),
            Map.entry("{{BORDER_COLOR}}", borderColor),
            Map.entry("{{COLOR_SCHEME}}", themeSchema)
        ));
        themeColors.forEach((key, value) -> values.put("{{" + key + "}}", value));
        return Map.copyOf(values);
    }

    @NotNull
    private static String getFontSize(@NotNull FontData[] mainFont, double scale) {
        String unit = RuntimeUtils.isMacOS() ? "px" : "pt";
        int height = mainFont.length > 0 ? mainFont[0].getHeight() : 13;
        return scale == 1.0 ? height + unit : String.format(Locale.ROOT, "%.2f%s", height * scale, unit);
    }

    @NotNull
    private static String getFontFamily(@NotNull FontData[] mainFont, @NotNull String defaultFamily) {
        String fontFamily = defaultFamily;
        if (mainFont.length > 0) {
            fontFamily = mainFont[0].getName();
            if (fontFamily.equals(".AppleSystemUIFont")) {
                fontFamily = "system-ui";
            }
        }
        return fontFamily;
    }

    @NotNull
    static String colorToHex(@Nullable Color color) {
        if (color == null) {
            return "#000000";
        }
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }
}

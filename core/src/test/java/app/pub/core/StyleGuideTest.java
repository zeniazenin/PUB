package app.pub.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import java.util.Map;
import java.util.function.Consumer;
import static org.junit.Assert.*;

public class StyleGuideTest {
    private static void rejected(Consumer<JsonObject> change) {
        JsonObject o = StyleGuide.DEFAULT.toJsonObject();
        change.accept(o);
        assertThrows(IllegalArgumentException.class, () -> StyleGuide.parse(o.toString()));
    }

    @Test public void roundTripsAndNormalisesColours() {
        assertEquals(StyleGuide.DEFAULT, StyleGuide.parse(StyleGuide.DEFAULT.toJson()));
        StyleGuide guide = StyleGuide.of("light", "#1a73e8", "#5f6368", null, "sharp", "serif");
        assertEquals("#1A73E8", guide.primary);
        assertEquals("#5F6368", guide.secondary);
        assertNull(guide.tertiary);
        StyleGuide back = StyleGuide.parse(guide.toJson());
        assertEquals(guide, back);
        assertFalse(back.toJsonObject().has("tertiary"));
        assertEquals("mono", guide.withFont("mono").font);
        assertNull(guide.withSecondary(null).secondary);
    }

    @Test public void rejectsAnythingOutsideTheSchema() {
        rejected(o -> o.addProperty("notes", "Use a playful tone"));
        rejected(o -> o.addProperty("version", 2));
        rejected(o -> o.addProperty("version", "1"));
        rejected(o -> o.remove("version"));
        rejected(o -> o.remove("primary"));
        rejected(o -> o.addProperty("primary", "#12345"));
        rejected(o -> o.addProperty("primary", "red"));
        rejected(o -> o.addProperty("secondary", "#12345G"));
        rejected(o -> o.addProperty("mode", "dim"));
        rejected(o -> o.addProperty("corners", "square"));
        rejected(o -> o.addProperty("font", "comic"));
        rejected(o -> o.addProperty("font", 3));
        assertThrows(IllegalArgumentException.class, () -> StyleGuide.parse("[]"));
        assertThrows(IllegalArgumentException.class, () -> StyleGuide.parse("{"));
        assertThrows(IllegalArgumentException.class, () -> StyleGuide.parse(null));
        assertThrows(IllegalArgumentException.class, () -> StyleGuide.parse("{\"version\":1,\"mode\":\"" + "x".repeat(1000) + "\"}"));
        assertThrows(IllegalArgumentException.class, () -> StyleGuide.DEFAULT.withCorners("pill"));
    }

    @Test public void optionalColoursMayBeNull() {
        JsonObject o = StyleGuide.DEFAULT.toJsonObject();
        o.add("secondary", com.google.gson.JsonNull.INSTANCE);
        assertNull(StyleGuide.parse(o.toString()).secondary);
    }

    @Test public void legacyAccentsBecomeStyleGuides() {
        assertEquals(StyleGuide.DEFAULT, StyleGuide.fromLegacyAccent("moss"));
        assertEquals(StyleGuide.DEFAULT, StyleGuide.fromLegacyAccent(null));
        assertEquals(StyleGuide.DEFAULT, StyleGuide.fromLegacyAccent("unknown"));
        StyleGuide blue = StyleGuide.fromLegacyAccent("blue");
        assertEquals("#9EC8FF", blue.primary);
        assertEquals("dark", blue.mode);
        assertEquals(StyleGuide.APRICOT, blue.tertiary);
        assertEquals("#D0BCFF", StyleGuide.fromLegacyAccent("violet").primary);
    }

    @Test public void modeDecidesDarkness() {
        assertTrue(StyleGuide.DEFAULT.dark(false));
        assertFalse(StyleGuide.DEFAULT.withMode("light").dark(true));
        assertTrue(StyleGuide.DEFAULT.withMode("system").dark(true));
        assertFalse(StyleGuide.DEFAULT.withMode("system").dark(false));
    }

    @Test public void legacyLooksKeepTheirExactColours() {
        for (String accent : new String[]{"moss", "blue", "violet"}) {
            StyleGuide guide = StyleGuide.fromLegacyAccent(accent);
            Map<String, Integer> dark = StyleColors.scheme(guide, true);
            assertEquals(accent, guide.primary, StyleColors.hex(dark.get("primary")));
            assertEquals(accent, StyleGuide.APRICOT, StyleColors.hex(dark.get("tertiary")));
        }
    }

    @Test public void everySchemeHasEveryRoleWithReadableText() {
        String[] seeds = {"#BDD990", "#9EC8FF", "#D0BCFF", "#1A73E8", "#B3261E", "#FFEB3B", "#00FF00", "#000000", "#FFFFFF",
            "#808080", "#3E2723", "#FF00FF", "#00BCD4", "#F48FB1", "#0D47A1", "#7FD6C2"};
        for (String seed : seeds) for (boolean dark : new boolean[]{true, false}) {
            StyleGuide guide = StyleGuide.DEFAULT.withPrimary(seed).withTertiary(null).withSecondary(dark ? null : "#FF7043");
            Map<String, Integer> s = StyleColors.scheme(guide, dark);
            String label = seed + (dark ? " dark" : " light");
            assertEquals(label, StyleColors.ROLES, java.util.List.copyOf(s.keySet()));
            for (Map.Entry<String, Integer> role : s.entrySet()) {
                assertNotNull(label + " " + role.getKey(), role.getValue());
                assertEquals(label + " " + role.getKey() + " opaque", 0xFF, role.getValue() >>> 24);
            }
            for (String[] pair : new String[][]{{"onPrimary", "primary"}, {"onSecondary", "secondary"}, {"onTertiary", "tertiary"},
                {"onPrimaryContainer", "primaryContainer"}, {"onSecondaryContainer", "secondaryContainer"},
                {"onTertiaryContainer", "tertiaryContainer"}, {"onError", "error"}, {"onErrorContainer", "errorContainer"},
                {"onSurface", "surface"}, {"onBackground", "background"}, {"onSurfaceVariant", "surfaceContainerHigh"},
                {"onSurface", "surfaceContainerHighest"}, {"inverseOnSurface", "inverseSurface"}}) {
                double contrast = StyleColors.contrast(s.get(pair[0]), s.get(pair[1]));
                assertTrue(label + " " + pair[0] + " on " + pair[1] + " = " + contrast, contrast >= StyleColors.TEXT_CONTRAST);
            }
            assertTrue(label + " primary stands out", StyleColors.contrast(s.get("primary"), s.get("background")) >= 3);
            double background = StyleColors.tone(s.get("background"));
            assertTrue(label + " background tone " + background, dark ? background < 10 : background > 95);
        }
    }

    @Test public void pickedColoursAreUsedWhenTheyFitTheMode() {
        StyleGuide brand = StyleGuide.of("light", "#1A73E8", null, "#E8710A", "rounded", "sans");
        Map<String, Integer> light = StyleColors.scheme(brand, false);
        assertEquals("#1A73E8", StyleColors.hex(light.get("primary")));
        // Too light for text on a light background: the palette's tone 40 of the same hue is used instead.
        Map<String, Integer> pale = StyleColors.scheme(brand.withPrimary("#9EC8FF"), false);
        assertEquals(40, StyleColors.tone(pale.get("primary")), 1.5);
        // In dark mode the dark brand blue is too dark, so the light tone of its hue is used.
        Map<String, Integer> dark = StyleColors.scheme(brand, true);
        assertEquals(80, StyleColors.tone(dark.get("primary")), 1.5);
        double hue = StyleColors.lch(0xFF1A73E8)[2], darkHue = StyleColors.lch(dark.get("primary"))[2];
        assertEquals(hue, darkHue, 8);
    }

    @Test public void conversionsRoundTrip() {
        for (int argb : new int[]{0xFF1A73E8, 0xFFBDD990, 0xFF000000, 0xFFFFFFFF, 0xFF808080, 0xFFFF00FF}) {
            double[] lch = StyleColors.lch(argb);
            assertEquals(StyleColors.hex(argb), StyleColors.hex(StyleColors.fromLch(lch[0], lch[1], lch[2])));
        }
        assertEquals(21, StyleColors.contrast(0xFF000000, 0xFFFFFFFF), 1e-9);
        assertEquals("#BDD990", StyleColors.hex(StyleColors.argb("#bdd990")));
    }

    @Test public void graphicThemeColoursAreSchemeRoles() {
        for (String name : GraphicAsset.THEME_COLORS) {
            if (!name.equals("transparent")) assertTrue(name, StyleColors.ROLES.contains(name));
        }
        GraphicAsset.validate(JsonParser.parseString("{\"type\":\"vector\",\"width\":10,\"height\":10,\"shapes\":[{\"id\":\"a\",\"type\":\"rect\",\"x\":0,\"y\":0,\"width\":5,\"height\":5,\"fill\":\"surfaceContainerHighest\",\"stroke\":\"onSecondaryContainer\"}]}").getAsJsonObject());
        assertThrows(IllegalArgumentException.class, () -> GraphicAsset.validate(JsonParser.parseString("{\"type\":\"vector\",\"width\":10,\"height\":10,\"shapes\":[{\"id\":\"a\",\"type\":\"rect\",\"x\":0,\"y\":0,\"width\":5,\"height\":5,\"fill\":\"accent\"}]}").getAsJsonObject()));
    }
}

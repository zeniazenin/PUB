package app.pub.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The user's style guide: one overarching look that PUB applies itself, when it draws, to its own screens and to every
 * generated app (an app can be set not to follow it and then uses {@link #DEFAULT}). Every field is a closed choice or a
 * colour, so a guide never carries text, and the host derives the complete light and dark colour schemes from it
 * ({@link StyleColors}). Stored and context forms are the same strict JSON object; unknown fields reject the guide.
 *
 * <pre>{"version":1,"mode":"dark","primary":"#BDD990","tertiary":"#FFC084","corners":"rounded","font":"sans"}</pre>
 *
 * {@code secondary} and {@code tertiary} are optional: without them the host derives them from {@code primary}.
 */
public final class StyleGuide {
    public static final int VERSION = 1;
    public static final List<String> MODES = List.of("dark", "light", "system");
    public static final List<String> CORNERS = List.of("sharp", "rounded", "round");
    public static final List<String> FONTS = List.of("sans", "serif", "mono");
    /** The tertiary colour every legacy accent had, so the default and migrated styles keep their look. */
    public static final String APRICOT = "#FFC084";
    private static final Pattern HEX = Pattern.compile("#[0-9A-F]{6}");
    private static final Set<String> FIELDS = Set.of("version", "mode", "primary", "secondary", "tertiary", "corners", "font");

    /** PUB's own style: the look of PUB before style guides, and of apps that do not follow the user's guide. */
    public static final StyleGuide DEFAULT = new StyleGuide("dark", "#BDD990", null, APRICOT, "rounded", "sans");

    /** "dark", "light" or "system" (follow the phone's dark theme setting). */
    public final String mode;
    /** The main colour, "#RRGGBB" in upper case. */
    public final String primary;
    /** Optional second colour, or null to derive it from {@link #primary}. */
    public final String secondary;
    /** Optional third colour, or null to derive it from {@link #primary}. */
    public final String tertiary;
    /** "sharp", "rounded" or "round". */
    public final String corners;
    /** "sans", "serif" or "mono". */
    public final String font;

    private StyleGuide(String mode, String primary, String secondary, String tertiary, String corners, String font) {
        this.mode = choice(mode, MODES, "mode");
        this.primary = color(primary, "primary");
        this.secondary = secondary == null ? null : color(secondary, "secondary");
        this.tertiary = tertiary == null ? null : color(tertiary, "tertiary");
        this.corners = choice(corners, CORNERS, "corners");
        this.font = choice(font, FONTS, "font");
    }

    /** A guide from its fields; colours are "#RRGGBB" (any case). Throws IllegalArgumentException on any invalid value. */
    public static StyleGuide of(String mode, String primary, String secondary, String tertiary, String corners, String font) {
        return new StyleGuide(mode, primary, secondary, tertiary, corners, font);
    }

    public StyleGuide withMode(String value) { return new StyleGuide(value, primary, secondary, tertiary, corners, font); }
    public StyleGuide withPrimary(String value) { return new StyleGuide(mode, value, secondary, tertiary, corners, font); }
    /** Null derives the colour from {@link #primary}. */
    public StyleGuide withSecondary(String value) { return new StyleGuide(mode, primary, value, tertiary, corners, font); }
    /** Null derives the colour from {@link #primary}. */
    public StyleGuide withTertiary(String value) { return new StyleGuide(mode, primary, secondary, value, corners, font); }
    public StyleGuide withCorners(String value) { return new StyleGuide(mode, primary, secondary, tertiary, value, font); }
    public StyleGuide withFont(String value) { return new StyleGuide(mode, primary, secondary, tertiary, corners, value); }

    /** True when {@code value} is a "#RRGGBB" colour (any case). Never throws. */
    public static boolean validColor(String value) {
        return value != null && HEX.matcher(value.toUpperCase(Locale.ROOT)).matches();
    }

    /** The style an install had before style guides: its accent (moss, blue or violet) on PUB's dark theme. */
    public static StyleGuide fromLegacyAccent(String accent) {
        String primary = switch (accent == null ? "" : accent) {
            case "blue" -> "#9EC8FF";
            case "violet" -> "#D0BCFF";
            default -> DEFAULT.primary;
        };
        return DEFAULT.withPrimary(primary);
    }

    /** Whether apps drawn in this guide are dark, given whether the phone's dark theme is on. */
    public boolean dark(boolean systemDark) {
        return switch (mode) { case "light" -> false; case "dark" -> true; default -> systemDark; };
    }

    /** Parses the stored or context form. Throws IllegalArgumentException for anything but a valid version-1 guide. */
    public static StyleGuide parse(String json) {
        if (json == null || json.length() > 1_000) throw bad("Style guide is missing or too large");
        JsonElement parsed;
        try { parsed = JsonParser.parseString(json); } catch (JsonParseException | IllegalStateException e) { throw bad("Style guide is not JSON"); }
        if (!parsed.isJsonObject()) throw bad("Style guide must be an object");
        return fromJson(parsed.getAsJsonObject());
    }

    public static StyleGuide fromJson(JsonObject o) {
        if (o == null || !FIELDS.containsAll(o.keySet())) throw bad("Unknown style guide field");
        JsonElement version = o.get("version");
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber() || version.getAsDouble() != VERSION) {
            throw bad("Unsupported style guide version");
        }
        return new StyleGuide(text(o, "mode", true), text(o, "primary", true), text(o, "secondary", false),
            text(o, "tertiary", false), text(o, "corners", true), text(o, "font", true));
    }

    public JsonObject toJsonObject() {
        JsonObject o = new JsonObject();
        o.addProperty("version", VERSION);
        o.addProperty("mode", mode);
        o.addProperty("primary", primary);
        if (secondary != null) o.addProperty("secondary", secondary);
        if (tertiary != null) o.addProperty("tertiary", tertiary);
        o.addProperty("corners", corners);
        o.addProperty("font", font);
        return o;
    }

    public String toJson() { return toJsonObject().toString(); }

    @Override public boolean equals(Object other) {
        if (!(other instanceof StyleGuide)) return false;
        StyleGuide g = (StyleGuide) other;
        return mode.equals(g.mode) && primary.equals(g.primary) && Objects.equals(secondary, g.secondary)
            && Objects.equals(tertiary, g.tertiary) && corners.equals(g.corners) && font.equals(g.font);
    }
    @Override public int hashCode() { return Objects.hash(mode, primary, secondary, tertiary, corners, font); }
    @Override public String toString() { return toJson(); }

    private static String text(JsonObject o, String key, boolean required) {
        JsonElement value = o.get(key);
        if (value == null || value.isJsonNull()) {
            if (required) throw bad("Missing style guide field");
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().length() > 16) throw bad("Invalid style guide field");
        return value.getAsString();
    }
    private static String choice(String value, List<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) throw bad("Invalid style guide " + field);
        return value;
    }
    private static String color(String value, String field) {
        if (!validColor(value)) throw bad("Invalid style guide " + field + " colour");
        return value.toUpperCase(Locale.ROOT);
    }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}

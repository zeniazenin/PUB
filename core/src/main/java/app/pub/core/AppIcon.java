package app.pub.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;

/**
 * Launcher icons: one outline pictogram per app, so every icon on PUB's home screen shares one style. A pictogram is
 * a vector {@link GraphicAsset} on a {@link #GRID}-unit square without text. The host draws it in a single theme colour
 * with one even outline weight, whatever colours and widths the shapes carry. Fills: "primaryContainer" is a soft tonal
 * fill (a duotone silhouette), "surface" cuts the shape out of the tile, any other visible fill is solid, and a missing or
 * "transparent" fill is none. Icons that are not pictograms (from older builds) are not drawn this way.
 *
 * <p>A design is {@link #refine refined} when it arrives: coordinates on half units, no stroke widths, colours reduced to
 * the icon palette, and degenerate or duplicate shapes removed. {@link #tooBusy} tells the designer when to simplify.
 *
 * <p>Drawing fits every pictogram to the same live area ({@link #fit}): its measured outline, strokes included, is
 * scaled and centred into {@link #LIVE_MIN}..{@link #LIVE_MAX}, so icons a model drew too large, too small or off centre
 * all sit at one optical size. The stored icon is never rewritten.
 */
public final class AppIcon {
    public static final int GRID = 48;
    /**
     * Nominal outline width on the grid, which the designer is told. The host draws outlines at an optical weight for
     * the size the icon is shown at (thinner relative to the icon as it grows), not at this exact width.
     */
    public static final float STROKE = 2f;
    /** PUB draws pictograms as pixel art on a grid this many cells across: 2 units per cell, one outline per cell. */
    public static final int PIXELS = 24;
    /** The live area on the grid that every pictogram is fitted into when drawn. */
    public static final double LIVE_MIN = 9, LIVE_MAX = 39;
    /** The most a small design is enlarged when fitted, so a single dot never fills the tile. */
    public static final double MAX_GROWTH = 1.6;
    public static final int MAX_SHAPES = 16;
    /** PUB's minimal style: at most this many shapes... */
    public static final int SIMPLE_SHAPES = 6;
    /** ...and at most this much drawing (see {@link #busyness}). */
    public static final int SIMPLE_BUSYNESS = 30;
    /**
     * Reference designs in PUB's icon style, shown to the icon designer as the level of reduction to aim for: one
     * object, two to four shapes, a soft duotone silhouette and one light outline weight. Keyed by what each shows.
     */
    public static final java.util.Map<String, String> STYLE_EXAMPLES = java.util.Map.of(
        "a timer", "{\"shapes\":["
            + "{\"id\":\"face\",\"type\":\"ellipse\",\"cx\":24,\"cy\":26,\"rx\":12,\"ry\":12,\"fill\":\"primaryContainer\",\"stroke\":\"primary\"},"
            + "{\"id\":\"stem\",\"type\":\"line\",\"x1\":24,\"y1\":10,\"x2\":24,\"y2\":14,\"fill\":\"transparent\",\"stroke\":\"primary\"},"
            + "{\"id\":\"crown\",\"type\":\"line\",\"x1\":21,\"y1\":10,\"x2\":27,\"y2\":10,\"fill\":\"transparent\",\"stroke\":\"primary\"},"
            + "{\"id\":\"hand\",\"type\":\"line\",\"x1\":24,\"y1\":26,\"x2\":28.5,\"y2\":21.5,\"fill\":\"transparent\",\"stroke\":\"primary\"}]}",
        "a journal", "{\"shapes\":["
            + "{\"id\":\"cover\",\"type\":\"rect\",\"x\":14,\"y\":9,\"width\":20,\"height\":30,\"rx\":3,\"fill\":\"primaryContainer\",\"stroke\":\"primary\"},"
            + "{\"id\":\"spine\",\"type\":\"line\",\"x1\":19,\"y1\":9,\"x2\":19,\"y2\":39,\"fill\":\"transparent\",\"stroke\":\"primary\"},"
            + "{\"id\":\"line1\",\"type\":\"line\",\"x1\":23.5,\"y1\":17,\"x2\":29.5,\"y2\":17,\"fill\":\"transparent\",\"stroke\":\"primary\"},"
            + "{\"id\":\"line2\",\"type\":\"line\",\"x1\":23.5,\"y1\":22,\"x2\":27.5,\"y2\":22,\"fill\":\"transparent\",\"stroke\":\"primary\"}]}",
        "plant care", "{\"shapes\":["
            + "{\"id\":\"leaf\",\"type\":\"path\",\"commands\":[[\"M\",24,38],[\"C\",13,31,13,17,24,9],[\"C\",35,17,35,31,24,38],[\"Z\"]],\"fill\":\"primaryContainer\",\"stroke\":\"primary\"},"
            + "{\"id\":\"vein\",\"type\":\"line\",\"x1\":24,\"y1\":17,\"x2\":24,\"y2\":38,\"fill\":\"transparent\",\"stroke\":\"primary\"}]}");

    /** Asset id given to a host-designed icon; a taken id gets a numeric suffix. */
    public static final String ASSET_ID = "app-icon";
    private static final int MAX_ASSETS = 8;
    private AppIcon() {}

    /** True when [asset] is a valid pictogram. Never throws. */
    public static boolean isPictogram(JsonObject asset) {
        try {
            GraphicAsset.validate(asset);
            if (!"vector".equals(asset.get("type").getAsString())) return false;
            if (asset.get("width").getAsDouble() != GRID || asset.get("height").getAsDouble() != GRID) return false;
            JsonArray shapes = asset.getAsJsonArray("shapes");
            if (shapes.size() > MAX_SHAPES) return false;
            for (JsonElement shape : shapes) if ("text".equals(shape.getAsJsonObject().get("type").getAsString())) return false;
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * The exact bounds {minX, minY, maxX, maxY} of [icon]'s geometry on the grid, without strokes: curves are measured at
     * their extremes, not at their control points. Null when the icon has no measurable shape.
     */
    public static double[] bounds(JsonObject icon) {
        double[] box = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (JsonElement element : icon.getAsJsonArray("shapes")) {
            JsonObject shape = element.getAsJsonObject();
            switch (shape.get("type").getAsString()) {
                case "rect" -> { double x = num(shape, "x"), y = num(shape, "y");
                    add(box, x, y); add(box, x + num(shape, "width"), y + num(shape, "height")); }
                case "ellipse" -> { double cx = num(shape, "cx"), cy = num(shape, "cy"), rx = Math.abs(num(shape, "rx")), ry = Math.abs(num(shape, "ry"));
                    add(box, cx - rx, cy - ry); add(box, cx + rx, cy + ry); }
                case "line" -> { add(box, num(shape, "x1"), num(shape, "y1")); add(box, num(shape, "x2"), num(shape, "y2")); }
                case "path" -> pathBounds(shape.getAsJsonArray("commands"), box);
                default -> { }
            }
        }
        return box[0] <= box[2] && box[1] <= box[3] ? box : null;
    }

    /**
     * How to draw [icon] so its outline, grown by [pad] units on every side for its strokes, is centred in the live area:
     * {scale, dx, dy}, applied as translate(dx, dy) then scale(scale) on the grid. Large designs shrink to fit; small ones
     * grow by at most {@link #MAX_GROWTH}. An icon without measurable geometry is left as it is.
     */
    public static double[] fit(JsonObject icon, double pad) {
        double[] box = bounds(icon);
        if (box == null) return new double[]{1, 0, 0};
        double width = box[2] - box[0] + 2 * pad, height = box[3] - box[1] + 2 * pad;
        double live = LIVE_MAX - LIVE_MIN;
        double scale = Math.min(MAX_GROWTH, live / Math.max(1e-6, Math.max(width, height)));
        double centreX = (box[0] + box[2]) / 2, centreY = (box[1] + box[3]) / 2, middle = GRID / 2.0;
        return new double[]{scale, middle - centreX * scale, middle - centreY * scale};
    }

    private static double num(JsonObject shape, String key) { return shape.get(key).getAsDouble(); }

    private static void add(double[] box, double x, double y) {
        box[0] = Math.min(box[0], x); box[1] = Math.min(box[1], y); box[2] = Math.max(box[2], x); box[3] = Math.max(box[3], y);
    }

    private static void pathBounds(JsonArray commands, double[] box) {
        double x = 0, y = 0, startX = 0, startY = 0;
        for (JsonElement element : commands) {
            JsonArray c = element.getAsJsonArray();
            switch (c.get(0).getAsString()) {
                case "M" -> { x = startX = c.get(1).getAsDouble(); y = startY = c.get(2).getAsDouble(); add(box, x, y); }
                case "L" -> { x = c.get(1).getAsDouble(); y = c.get(2).getAsDouble(); add(box, x, y); }
                case "Q" -> {
                    double x1 = c.get(1).getAsDouble(), y1 = c.get(2).getAsDouble(), x2 = c.get(3).getAsDouble(), y2 = c.get(4).getAsDouble();
                    for (double t : quadraticExtrema(x, x1, x2)) add(box, quadratic(x, x1, x2, t), quadratic(y, y1, y2, t));
                    for (double t : quadraticExtrema(y, y1, y2)) add(box, quadratic(x, x1, x2, t), quadratic(y, y1, y2, t));
                    x = x2; y = y2; add(box, x, y);
                }
                case "C" -> {
                    double x1 = c.get(1).getAsDouble(), y1 = c.get(2).getAsDouble(), x2 = c.get(3).getAsDouble(), y2 = c.get(4).getAsDouble();
                    double x3 = c.get(5).getAsDouble(), y3 = c.get(6).getAsDouble();
                    for (double t : cubicExtrema(x, x1, x2, x3)) add(box, cubic(x, x1, x2, x3, t), cubic(y, y1, y2, y3, t));
                    for (double t : cubicExtrema(y, y1, y2, y3)) add(box, cubic(x, x1, x2, x3, t), cubic(y, y1, y2, y3, t));
                    x = x3; y = y3; add(box, x, y);
                }
                case "Z" -> { x = startX; y = startY; }
                default -> { }
            }
        }
    }

    private static double quadratic(double p0, double p1, double p2, double t) { double u = 1 - t; return u * u * p0 + 2 * u * t * p1 + t * t * p2; }

    private static double[] quadraticExtrema(double p0, double p1, double p2) {
        double d = p0 - 2 * p1 + p2;
        if (Math.abs(d) < 1e-12) return new double[0];
        double t = (p0 - p1) / d;
        return t > 0 && t < 1 ? new double[]{t} : new double[0];
    }

    private static double cubic(double p0, double p1, double p2, double p3, double t) {
        double u = 1 - t; return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3;
    }

    /** Parameters in (0, 1) where the cubic's derivative is zero. */
    private static double[] cubicExtrema(double p0, double p1, double p2, double p3) {
        double a = -p0 + 3 * p1 - 3 * p2 + p3, b = 2 * (p0 - 2 * p1 + p2), c = p1 - p0;
        java.util.List<Double> roots = new java.util.ArrayList<>();
        if (Math.abs(a) < 1e-12) { if (Math.abs(b) > 1e-12) roots.add(-c / b); }
        else {
            double disc = b * b - 4 * a * c;
            if (disc >= 0) { double r = Math.sqrt(disc); roots.add((-b + r) / (2 * a)); roots.add((-b - r) / (2 * a)); }
        }
        return roots.stream().filter(t -> t > 0 && t < 1).mapToDouble(Double::doubleValue).toArray();
    }

    /** The bundle's launcher icon asset when it is a pictogram, else null. */
    public static JsonObject pictogram(ProjectBundle bundle) {
        if (bundle == null || bundle.icon == null) return null;
        JsonElement asset = bundle.assets.get(bundle.icon);
        return asset != null && asset.isJsonObject() && isPictogram(asset.getAsJsonObject()) ? asset.getAsJsonObject().deepCopy() : null;
    }

    /**
     * The pictogram from an icon designer's answer, exactly {"shapes":[...]} (a Markdown code fence around it is
     * tolerated), {@link #refine refined}. Throws IllegalArgumentException when it is not a valid pictogram.
     */
    public static JsonObject fromDesign(String answer) {
        if (answer == null || answer.length() > 60_000) throw new IllegalArgumentException("Icon design is missing or too large");
        String text = answer.trim();
        if (text.startsWith("```")) {
            text = text.substring(text.indexOf('\n') + 1);
            if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
        }
        UiPacket.checkJsonDepth(text);
        JsonElement parsed;
        try { parsed = JsonParser.parseString(text.trim()); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Icon design is not JSON"); }
        if (!parsed.isJsonObject() || !parsed.getAsJsonObject().keySet().equals(Set.of("shapes")) || !parsed.getAsJsonObject().get("shapes").isJsonArray()) {
            throw new IllegalArgumentException("Icon design must be {\"shapes\":[...]}");
        }
        JsonObject icon = new JsonObject();
        icon.addProperty("type", "vector");
        icon.addProperty("width", GRID);
        icon.addProperty("height", GRID);
        icon.add("shapes", parsed.getAsJsonObject().get("shapes").deepCopy());
        if (!isPictogram(icon)) throw new IllegalArgumentException("Icon design is not a valid pictogram");
        JsonObject refined = refine(icon);
        if (!isPictogram(refined)) throw new IllegalArgumentException("Icon design is not a valid pictogram");
        return refined;
    }

    /**
     * [icon] (a valid pictogram) cleaned to PUB's icon style: every coordinate and size on half units, no stroke widths
     * (PUB draws one weight), outlines "primary" or "transparent", fills reduced to the icon palette ("transparent",
     * "primary", "surface" and the soft "primaryContainer"), and shapes that draw nothing, are too small to see or repeat
     * another shape removed. Throws IllegalArgumentException when nothing drawable is left.
     */
    public static JsonObject refine(JsonObject icon) {
        JsonArray kept = new JsonArray();
        Set<String> seen = new java.util.HashSet<>();
        for (JsonElement element : icon.getAsJsonArray("shapes")) {
            JsonObject shape = element.getAsJsonObject().deepCopy();
            shape.remove("strokeWidth");
            String type = shape.get("type").getAsString();
            String stroke = strokeOf(shape.get("stroke")), fill = fillOf(shape.get("fill"));
            if (type.equals("line")) fill = "transparent"; // A line has no inside.
            shape.addProperty("stroke", stroke); shape.addProperty("fill", fill);
            for (String key : new String[]{"x", "y", "width", "height", "rx", "cx", "cy", "ry", "x1", "y1", "x2", "y2"}) {
                if (shape.has(key)) shape.addProperty(key, half(shape.get(key).getAsDouble()));
            }
            if (type.equals("path")) {
                JsonArray commands = new JsonArray();
                for (JsonElement command : shape.getAsJsonArray("commands")) {
                    JsonArray rounded = new JsonArray();
                    JsonArray original = command.getAsJsonArray();
                    rounded.add(original.get(0));
                    for (int i = 1; i < original.size(); i++) rounded.add(half(original.get(i).getAsDouble()));
                    commands.add(rounded);
                }
                shape.add("commands", commands);
            }
            if (stroke.equals("transparent") && fill.equals("transparent")) continue;
            if (!drawable(shape, type)) continue;
            JsonObject key = shape.deepCopy(); key.remove("id");
            if (!seen.add(key.toString())) continue;
            kept.add(shape);
        }
        if (kept.isEmpty()) throw new IllegalArgumentException("Icon design draws nothing");
        JsonObject refined = icon.deepCopy();
        refined.add("shapes", kept);
        return refined;
    }

    /**
     * How much drawing [icon] holds: a line counts 1, a rectangle or ellipse 2, a path its commands. A clean single-object
     * pictogram is usually 8-20.
     */
    public static int busyness(JsonObject icon) {
        int total = 0;
        for (JsonElement element : icon.getAsJsonArray("shapes")) {
            JsonObject shape = element.getAsJsonObject();
            total += switch (shape.get("type").getAsString()) {
                case "line" -> 1;
                case "path" -> shape.getAsJsonArray("commands").size();
                default -> 2;
            };
        }
        return total;
    }

    /** True when [icon] is busier than PUB's minimal style allows: more than {@link #SIMPLE_SHAPES} shapes or {@link #SIMPLE_BUSYNESS}. */
    public static boolean tooBusy(JsonObject icon) {
        return icon.getAsJsonArray("shapes").size() > SIMPLE_SHAPES || busyness(icon) > SIMPLE_BUSYNESS;
    }

    private static double half(double value) { return Math.round(value * 2) / 2.0; }

    private static boolean visibleColor(JsonElement value) {
        if (value == null || value.isJsonNull()) return false;
        String color = value.getAsString();
        if (color.equals("transparent")) return false;
        return !(color.length() == 9 && color.startsWith("#") && color.substring(1, 3).equals("00"));
    }

    /** A missing stroke is drawn, as outlines are the style's default. */
    private static String strokeOf(JsonElement value) {
        return value == null || value.isJsonNull() || visibleColor(value) ? "primary" : "transparent";
    }

    private static String fillOf(JsonElement value) {
        if (!visibleColor(value)) return "transparent";
        String color = value.getAsString();
        if (color.equals("surface") || color.equals("background")) return "surface";
        if (color.endsWith("Container") || color.equals("surfaceVariant") || color.startsWith("surfaceContainer")) return "primaryContainer";
        return "primary";
    }

    /** Whether a refined shape is large enough to see at icon size (half a unit or more in some direction). */
    private static boolean drawable(JsonObject shape, String type) {
        return switch (type) {
            case "rect" -> shape.get("width").getAsDouble() >= .5 && shape.get("height").getAsDouble() >= .5;
            case "ellipse" -> shape.get("rx").getAsDouble() >= .5 && shape.get("ry").getAsDouble() >= .5;
            case "line" -> Math.hypot(shape.get("x2").getAsDouble() - shape.get("x1").getAsDouble(),
                shape.get("y2").getAsDouble() - shape.get("y1").getAsDouble()) >= .5;
            case "path" -> {
                JsonObject single = new JsonObject(); JsonArray one = new JsonArray(); one.add(shape); single.add("shapes", one);
                double[] box = bounds(single);
                yield box != null && Math.max(box[2] - box[0], box[3] - box[1]) >= .5;
            }
            default -> false;
        };
    }

    /**
     * [bundle] with [icon] as its launcher icon under a free asset id. The previous icon asset is replaced when the app
     * itself never shows it, so builds do not pile up old icons. Returns null when the package has no room for another
     * asset; [icon] must be a pictogram.
     */
    public static ProjectBundle withIcon(ProjectBundle bundle, JsonObject icon) {
        if (!isPictogram(icon)) throw new IllegalArgumentException("Not a pictogram");
        JsonObject data = JsonParser.parseString(bundle.toJson()).getAsJsonObject();
        JsonObject assets = data.has("assets") ? data.getAsJsonObject("assets") : new JsonObject();
        if (bundle.icon != null && !usedInSource(bundle, bundle.icon)) assets.remove(bundle.icon);
        if (assets.size() >= MAX_ASSETS) return null;
        String id = ASSET_ID;
        for (int n = 2; assets.has(id); n++) id = ASSET_ID + "-" + n;
        assets.add(id, icon.deepCopy());
        data.add("assets", assets);
        data.addProperty("icon", id);
        return ProjectBundle.parse(data.toString());
    }

    /** [bundle] without a launcher icon; its assets stay, since the app may show them. */
    public static ProjectBundle withoutIcon(ProjectBundle bundle) {
        if (bundle.icon == null) return bundle;
        JsonObject data = JsonParser.parseString(bundle.toJson()).getAsJsonObject();
        data.remove("icon");
        return ProjectBundle.parse(data.toString());
    }

    /** Whether the app's source names the asset id as a string, i.e. might show it in a graphic node. */
    private static boolean usedInSource(ProjectBundle bundle, String id) {
        return bundle.source.contains("'" + id + "'") || bundle.source.contains("\"" + id + "\"") || bundle.source.contains("`" + id + "`");
    }
}

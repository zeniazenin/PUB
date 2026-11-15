package app.pub.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;

/**
 * Launcher icons: one outline pictogram per app, so every icon on PUB's home screen shares one style. A pictogram is
 * a vector {@link GraphicAsset} on a {@link #GRID}-unit square without text. The host draws it in a single theme colour
 * with every outline {@link #STROKE} units wide, whatever colours and widths the shapes carry: a fill of "surface" cuts
 * the shape out of the tile, any other visible fill is solid, and a missing or "transparent" fill is none. Icons that
 * are not pictograms (from older builds) are not drawn this way.
 */
public final class AppIcon {
    public static final int GRID = 48;
    /** Outline width on the grid; the host draws every stroke at this width. */
    public static final float STROKE = 3f;
    public static final int MAX_SHAPES = 16;
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

    /** The bundle's launcher icon asset when it is a pictogram, else null. */
    public static JsonObject pictogram(ProjectBundle bundle) {
        if (bundle == null || bundle.icon == null) return null;
        JsonElement asset = bundle.assets.get(bundle.icon);
        return asset != null && asset.isJsonObject() && isPictogram(asset.getAsJsonObject()) ? asset.getAsJsonObject().deepCopy() : null;
    }

    /**
     * The pictogram from an icon designer's answer, exactly {"shapes":[...]} (a Markdown code fence around it is
     * tolerated). Throws IllegalArgumentException when it is not a valid pictogram.
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
        return icon;
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

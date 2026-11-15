package app.pub.core;

import com.google.gson.*;
import java.util.Set;

/** Untrusted model output is data. The host assigns all filesystem identities. */
public final class ProjectBundle {
    public static final int MAX_PACKAGE_CHARS = 12_000_000;
    public static final int MAX_PACKAGE_BYTES = 16_000_000;
    public final String name, description, source;
    public final JsonObject assets;
    public final String icon;
    public final Set<String> capabilities;
    private ProjectBundle(String name, String description, String source, Set<String> caps, JsonObject assets, String icon) {
        this.name=name; this.description=description; this.source=source; this.capabilities=Set.copyOf(caps); this.assets=assets.deepCopy(); this.icon=icon;
    }
    public static ProjectBundle parse(String json) {
        if (json.length()>MAX_PACKAGE_CHARS) throw new IllegalArgumentException("Project exceeds size limit");
        UiPacket.checkJsonDepth(json);
        JsonObject o=JsonParser.parseString(json).getAsJsonObject();
        if (!o.keySet().containsAll(Set.of("name","description","source","capabilities")) || !Set.of("name","description","source","capabilities","assets","icon").containsAll(o.keySet())) throw new IllegalArgumentException("Unexpected project fields");
        String name=string(o,"name",80), description=string(o,"description",500), source=string(o,"source",ScriptEngine.MAX_SOURCE);
        if (name.isBlank() || source.isBlank()) throw new IllegalArgumentException("Empty project");
        JsonArray array=o.getAsJsonArray("capabilities");
        if (array.size()>HardwareContract.CAPABILITIES.size()) throw new IllegalArgumentException("Too many capabilities");
        Set<String> caps=new java.util.HashSet<>();
        for(JsonElement c:array) {
            if (!c.isJsonPrimitive() || !c.getAsJsonPrimitive().isString() || !HardwareContract.CAPABILITIES.contains(c.getAsString())) throw new IllegalArgumentException("Unsupported capability");
            caps.add(c.getAsString());
        }
        JsonObject assets=o.has("assets")?o.getAsJsonObject("assets"):new JsonObject();
        if (assets.size()>8) throw new IllegalArgumentException("Too many graphical assets");
        int images=0;
        for (var entry: assets.entrySet()) {
            if (!GraphicAsset.validId(entry.getKey())) throw new IllegalArgumentException("Invalid graphical asset ID");
            GraphicAsset.validate(entry.getValue().getAsJsonObject());
            if ("image".equals(entry.getValue().getAsJsonObject().get("type").getAsString()) && ++images>2) throw new IllegalArgumentException("At most two generated images per app");
        }
        String icon=o.has("icon")?string(o,"icon",40):null;
        if (icon!=null && !assets.has(icon)) throw new IllegalArgumentException("Icon asset is missing");
        return new ProjectBundle(name,description,source,caps,assets,icon);
    }
    private static String string(JsonObject o,String key,int max) {
        JsonElement e=o.get(key);
        if(e==null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().length()>max) throw new IllegalArgumentException("Invalid "+key);
        return e.getAsString();
    }
    /** Include asset definitions/prompts in edits, never binary image content. */
    public String toPromptJson() {
        JsonObject o=JsonParser.parseString(toJson()).getAsJsonObject();
        if(o.has("assets")) for(var entry:o.getAsJsonObject("assets").entrySet()) entry.getValue().getAsJsonObject().remove("data");
        return o.toString();
    }
    public void requireReadyAssets() {
        for(var entry:assets.entrySet()) {
            JsonObject asset=entry.getValue().getAsJsonObject();
            if("image".equals(asset.get("type").getAsString()) && !asset.has("data")) throw new IllegalStateException("Image generation did not complete for " + entry.getKey());
        }
    }
    public String toJson() {
        JsonObject o=new JsonObject(); o.addProperty("name",name);o.addProperty("description",description);o.addProperty("source",source);
        JsonArray a=new JsonArray(); capabilities.stream().sorted().forEach(a::add);o.add("capabilities",a); if(!assets.isEmpty())o.add("assets",assets.deepCopy()); if(icon!=null)o.addProperty("icon",icon);return o.toString();
    }
}

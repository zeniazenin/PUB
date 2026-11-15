package app.pub.core;

import com.google.gson.*;
import java.util.*;

/** Shared allowlist: generated scripts cannot invent native APIs or hardware identities. */
public final class HardwareContract {
    public static final Map<String, Set<String>> OPERATIONS = Map.ofEntries(
        Map.entry("camera", Set.of("capture","list")),
        Map.entry("location", Set.of("current","start","stop")),
        Map.entry("sensors", Set.of("list","start","stop")),
        Map.entry("bluetooth", Set.of("choose","connect","read","write","subscribe","disconnect","classicConnect","classicWrite","classicRead")),
        Map.entry("haptics", Set.of("vibrate","stop")),
        Map.entry("torch", Set.of("set")),
        Map.entry("audio", Set.of("tone","speak","stop","devices","route","routeReset")),
        Map.entry("battery", Set.of("get")),
        Map.entry("infrared", Set.of("frequencies","transmit")),
        Map.entry("nfc", Set.of("read")),
        Map.entry("usb", Set.of("choose","claim","read","write","disconnect"))
    );
    public static final Set<String> CAPABILITIES;
    static {
        Set<String> all = new HashSet<>(OPERATIONS.keySet());
        all.add("microphone.pitch"); all.add("storage.project");
        CAPABILITIES = Set.copyOf(all);
    }
    private HardwareContract() {}
    public static void validate(JsonObject effect) {
        if (!effect.keySet().equals(Set.of("type","id","capability","operation","args"))) throw new IllegalArgumentException("Unexpected hardware fields");
        if (!"hardware.request".equals(text(effect,"type",32))) throw new IllegalArgumentException("Invalid hardware effect type");
        String id = text(effect,"id",64), capability=text(effect,"capability",32), operation=text(effect,"operation",32);
        if (!id.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid request id");
        if (!OPERATIONS.getOrDefault(capability,Set.of()).contains(operation)) throw new IllegalArgumentException("Unsupported hardware operation");
        if (!effect.get("args").isJsonObject() || effect.get("args").toString().length()>20000) throw new IllegalArgumentException("Invalid hardware arguments");
    }
    private static String text(JsonObject o,String key,int limit) {
        JsonElement v=o.get(key);
        if(v==null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString() || v.getAsString().length()>limit) throw new IllegalArgumentException("Invalid "+key);
        return v.getAsString();
    }
    public static boolean validAsset(String asset) { return asset.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg"); }
}

package app.pub.core;

import com.google.gson.*;
import java.util.*;
import java.util.regex.Pattern;

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
        Map.entry("usb", Set.of("choose","claim","read","write","disconnect")),
        // Documents and folders the user picks in the system file picker; arguments are checked by PhoneFilesContract.parse.
        Map.entry(PhoneFilesContract.CAPABILITY, PhoneFilesContract.OPERATIONS)
    );
    public static final Set<String> CAPABILITIES;
    static {
        Set<String> all = new HashSet<>(OPERATIONS.keySet());
        all.add("microphone.pitch"); all.add("storage.project"); all.add(FilesContract.CAPABILITY);
        CAPABILITIES = Set.copyOf(all);
    }
    private HardwareContract() {}
    public static void validate(JsonObject effect) {
        if (!effect.keySet().equals(Set.of("type","id","capability","operation","args"))) throw new IllegalArgumentException("Unexpected hardware fields");
        if (!"hardware.request".equals(text(effect,"type",32))) throw new IllegalArgumentException("Invalid hardware effect type");
        String id = text(effect,"id",64), capability=text(effect,"capability",32), operation=text(effect,"operation",32);
        if (!REQUEST_ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid request id");
        if (!OPERATIONS.getOrDefault(capability,Set.of()).contains(operation)) throw new IllegalArgumentException("Unsupported hardware operation");
        // File writes carry up to 12,000 bytes of text, which JSON escaping can grow past the 20,000 used for devices.
        int limit=PhoneFilesContract.CAPABILITY.equals(capability) ? ScriptEngine.MAX_MESSAGE : 20000;
        if (!effect.get("args").isJsonObject() || effect.get("args").toString().length()>limit) throw new IllegalArgumentException("Invalid hardware arguments");
    }
    private static String text(JsonObject o,String key,int limit) {
        JsonElement v=o.get(key);
        if(v==null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString() || v.getAsString().length()>limit) throw new IllegalArgumentException("Invalid "+key);
        return v.getAsString();
    }
    private static final Pattern REQUEST_ID = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
    private static final Pattern PHOTO_ASSET = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg");
    public static boolean validAsset(String asset) { return PHOTO_ASSET.matcher(asset).matches(); }
}

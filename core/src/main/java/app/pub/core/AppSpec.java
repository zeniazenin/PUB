package app.pub.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * What PUB keeps about one saved app so that later edits build on it instead of starting over: the request the app
 * was first built from, and the research brief and behavior checks of its latest saved build. It never contains
 * the app's package, saved data, photos, device context or credentials. The stored form is strict: exactly the
 * five fields below, bounded values, and the nested brief and suite revalidated by their own parsers. Invalid data
 * is rejected as a whole and errors never echo it.
 *
 * <pre>{"version":1,"request":"…","brief":{…}|null,"tests":[…]|null,"updatedAtMillis":1731600000000}</pre>
 */
public final class AppSpec {
    public static final int VERSION = 1;
    public static final int MAX_REQUEST = 8000;
    /** Every field at its maximum, with JSON escaping (at most six characters per request character), stays below this. */
    public static final int MAX_JSON_CHARS = 6 * MAX_REQUEST + ResearchBrief.MAX_STORED_JSON_CHARS + AppTestSuite.MAX_JSON_CHARS + 1_000;

    /** The request the app was first built from (for an app saved before specs existed, its first saved edit's). */
    public final String request;
    /** The latest saved build's research brief; null when that build had none (research skipped or failed). */
    public final ResearchBrief brief;
    /** The latest saved build's behavior checks; null when none were requested, empty when none were usable. */
    public final AppTestSuite tests;
    public final long updatedAtMillis;

    private AppSpec(String request, ResearchBrief brief, AppTestSuite tests, long updatedAtMillis) {
        this.request = request; this.brief = brief; this.tests = tests; this.updatedAtMillis = updatedAtMillis;
    }

    /** request must have 1 to 8000 characters and not be blank; updatedAtMillis must be positive. */
    public static AppSpec of(String request, ResearchBrief brief, AppTestSuite tests, long updatedAtMillis) {
        if (request == null || request.isBlank() || request.length() > MAX_REQUEST || updatedAtMillis <= 0) {
            throw new IllegalArgumentException("Invalid app spec.");
        }
        return new AppSpec(request, brief, tests, updatedAtMillis);
    }

    /** The spec after another saved build of the same app: the original request stays, brief and checks are replaced. */
    public AppSpec withBuild(ResearchBrief brief, AppTestSuite tests, long updatedAtMillis) {
        return of(request, brief, tests, updatedAtMillis);
    }

    /** The number of stored behavior checks (0 without a suite). */
    public int testCount() { return tests == null ? 0 : tests.tests.size(); }

    /** Stored form, in a fixed field order. Round-trips through {@link #parse}. */
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("version", VERSION);
        o.addProperty("request", request);
        o.add("brief", brief == null ? JsonNull.INSTANCE : JsonParser.parseString(brief.toJson()));
        o.add("tests", tests == null ? JsonNull.INSTANCE : JsonParser.parseString(tests.toJson()));
        o.addProperty("updatedAtMillis", updatedAtMillis);
        return o.toString();
    }

    private static final Set<String> FIELDS = Set.of("version", "request", "brief", "tests", "updatedAtMillis");

    /** Parses the stored form written by {@link #toJson()}; anything else is rejected. */
    public static AppSpec parse(String json) {
        if (json == null || json.length() > MAX_JSON_CHARS) throw invalid();
        // Stream the fixed schema so duplicate fields cannot overwrite earlier values.
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            UiPacket.checkJsonDepth(json);
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw invalid();
            reader.beginObject();
            Set<String> fields = new HashSet<>();
            String request = null;
            ResearchBrief brief = null;
            AppTestSuite tests = null;
            long updated = 0;
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!fields.add(field)) throw invalid();
                switch (field) {
                    case "version":
                        if (!String.valueOf(VERSION).equals(number(reader))) throw invalid();
                        break;
                    case "request":
                        if (reader.peek() != JsonToken.STRING) throw invalid();
                        request = reader.nextString();
                        break;
                    case "brief":
                        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); break; }
                        if (reader.peek() != JsonToken.BEGIN_OBJECT) throw invalid();
                        brief = ResearchBrief.parse(tree(reader).toString());
                        break;
                    case "tests":
                        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); break; }
                        if (reader.peek() != JsonToken.BEGIN_ARRAY) throw invalid();
                        tests = AppTestSuite.parse(tree(reader).toString());
                        break;
                    case "updatedAtMillis":
                        String raw = number(reader);
                        if (!raw.matches("[1-9][0-9]{0,18}")) throw invalid();
                        updated = Long.parseLong(raw);
                        break;
                    default: throw invalid();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || !fields.equals(FIELDS)) throw invalid();
            return of(request, brief, tests, updated);
        } catch (IOException | RuntimeException error) {
            // Do not include stored content or exception causes in UI/logs.
            throw invalid();
        }
    }

    private static String number(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw invalid();
        return reader.nextString();
    }

    /**
     * Reads one nested value as a tree for its own strict parser, rejecting duplicate fields (which a tree would
     * otherwise silently merge). Depth is already bounded by checkJsonDepth.
     */
    private static JsonElement tree(JsonReader reader) throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT: {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw invalid();
                    object.add(key, tree(reader));
                }
                reader.endObject();
                return object;
            }
            case BEGIN_ARRAY: {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(tree(reader));
                reader.endArray();
                return array;
            }
            case STRING: return new JsonPrimitive(reader.nextString());
            case NUMBER: return new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
            case NULL: reader.nextNull(); return JsonNull.INSTANCE;
            default: throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Saved app spec is invalid.");
    }
}

package app.pub.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Streamed research response. Like GenerationOutput, only a completed stream yields text. Sources are the
 * url_citation annotations of the answer: pages the model actually cited, with their titles. Pages a search
 * merely visited are not sources (they include unrelated results and have no titles). Only safe https links
 * are kept, de-duplicated and bounded. Never echoes payloads.
 */
public final class ResearchOutput {
    public static final int MAX_EVENT_CHARS = 1_000_000;
    public static final int MAX_TEXT_CHARS = ResearchBrief.MAX_JSON_CHARS;
    private static final int MAX_OUTPUT_ITEMS = 256;
    private static final int MAX_CONTENT_PARTS = 64;
    private static final int MAX_ANNOTATIONS = 1024;

    // A tool-using model may write a short message before searching; text is kept per output item.
    private final Map<String, StringBuilder> streamed = new LinkedHashMap<>();
    private final List<String> completedMessages = new ArrayList<>();
    private int textChars;
    private int completedChars;
    private final Map<String, ResearchBrief.Source> cited = new LinkedHashMap<>();
    private boolean completed;
    private boolean searched;

    /** Returns true once the response has completed; later payloads are ignored. */
    public boolean accept(String payload) {
        if (completed) return true;
        if (payload == null || payload.length() > MAX_EVENT_CHARS) throw new IllegalStateException("Research event exceeded limit");
        JsonObject event;
        try {
            UiPacket.checkJsonDepth(payload);
            event = JsonParser.parseString(payload).getAsJsonObject();
        } catch (RuntimeException error) {
            throw malformed();
        }
        String type = string(event, "type");
        switch (type == null ? "" : type) {
            case "response.output_text.delta":
                String delta = string(event, "delta");
                if (delta == null) throw malformed();
                textChars += delta.length();
                if (textChars > MAX_TEXT_CHARS) throw new IllegalStateException("Research response is too large");
                String key = itemKey(event);
                if (!streamed.containsKey(key) && streamed.size() == MAX_OUTPUT_ITEMS) throw malformed();
                streamed.computeIfAbsent(key, ignored -> new StringBuilder()).append(delta);
                break;
            case "response.output_text.annotation.added":
                annotation(object(event.get("annotation")));
                break;
            case "response.output_item.done":
                item(object(event.get("item")), false);
                break;
            case "response.completed":
                JsonObject response = object(event.get("response"));
                if (response != null) {
                    String status = string(response, "status");
                    if (status != null && !"completed".equals(status)) throw failed();
                    JsonArray output = array(response.get("output"));
                    if (output != null) {
                        if (output.size() > MAX_OUTPUT_ITEMS) throw malformed();
                        for (JsonElement item : output) item(object(item), true);
                    }
                }
                completed = true;
                break;
            case "response.failed": case "response.incomplete": case "error": case "response.refusal.delta":
                throw failed();
            default:
                // Search progress, reasoning and other events carry nothing the brief needs.
                break;
        }
        return completed;
    }

    /**
     * The final answer text: the last output message that looks like JSON (else the last non-blank one).
     * Streamed deltas win; message text from the completed response is only a fallback.
     */
    public String finish() {
        if (!completed) throw interrupted();
        List<String> messages = new ArrayList<>();
        streamed.values().forEach(value -> messages.add(value.toString()));
        String result = pick(messages);
        if (result == null) result = pick(completedMessages);
        if (result == null) throw interrupted();
        return result;
    }

    /** The cited sources in citation order, unique by URL, at most {@link ResearchBrief#MAX_SOURCES}. */
    public List<ResearchBrief.Source> sources() { return List.copyOf(cited.values()); }

    /** Whether the response actually ran a web search or cited a web page. */
    public boolean webSearchUsed() { return searched || !cited.isEmpty(); }

    private static String pick(List<String> messages) {
        String fallback = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            String value = messages.get(i).strip();
            if (value.isEmpty()) continue;
            if (value.startsWith("{") || value.startsWith("```")) return messages.get(i);
            if (fallback == null) fallback = messages.get(i);
        }
        return fallback;
    }

    private static String itemKey(JsonObject event) {
        String id = string(event, "item_id");
        if (id != null) return "id:" + id;
        JsonElement index = event.get("output_index");
        return index != null && index.isJsonPrimitive() && index.getAsJsonPrimitive().isNumber() ? "index:" + index.getAsString() : "";
    }

    private void item(JsonObject item, boolean fromCompleted) {
        if (item == null) return;
        String type = string(item, "type");
        if ("web_search_call".equals(type)) {
            searched = true;
        } else if ("message".equals(type)) {
            JsonArray content = array(item.get("content"));
            if (content == null) return;
            StringBuilder message = new StringBuilder();
            int parts = 0;
            for (JsonElement element : content) {
                if (++parts > MAX_CONTENT_PARTS) break;
                JsonObject part = object(element);
                if (part == null) continue;
                if (fromCompleted && "output_text".equals(string(part, "type"))) {
                    String value = string(part, "text");
                    if (value != null) message.append(value);
                }
                JsonArray annotations = array(part.get("annotations"));
                if (annotations == null) continue;
                int count = 0;
                for (JsonElement annotation : annotations) {
                    if (++count > MAX_ANNOTATIONS) break;
                    annotation(object(annotation));
                }
            }
            if (message.length() > 0 && completedChars + message.length() <= MAX_TEXT_CHARS) {
                completedChars += message.length();
                completedMessages.add(message.toString());
            }
        }
    }

    private void annotation(JsonObject annotation) {
        if (annotation == null || !"url_citation".equals(string(annotation, "type"))) return;
        JsonObject nested = object(annotation.get("url_citation"));
        JsonObject value = string(annotation, "url") == null && nested != null ? nested : annotation;
        add(cited, string(value, "title"), string(value, "url"));
    }

    private static void add(Map<String, ResearchBrief.Source> target, String title, String url) {
        if (target.size() >= ResearchBrief.MAX_SOURCES) return;
        ResearchBrief.Source source = ResearchBrief.Source.of(title, url);
        if (source != null) target.putIfAbsent(source.key(), source);
    }

    private static JsonObject object(JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonElement value) {
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    private static IllegalStateException malformed() {
        return new IllegalStateException("Malformed research response. Please retry.");
    }

    private static IllegalStateException failed() {
        return new IllegalStateException("Research could not complete. Try again or choose another model.");
    }

    private static IllegalStateException interrupted() {
        return new IllegalStateException("Research was interrupted before completion. Please retry.");
    }
}

package app.pub.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ResearchOutputTest {
    private static final String BRIEF = "{\"summary\":\"A tip calculator\",\"category\":\"Finance\",\"features\":[{\"title\":\"Bill input\",\"priority\":\"must\",\"feasibleInPub\":true,\"notes\":\"\"}]}";

    private static String delta(String itemId, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "response.output_text.delta");
        if (itemId != null) o.addProperty("item_id", itemId);
        o.addProperty("output_index", 0); o.addProperty("content_index", 0);
        o.addProperty("delta", text);
        return o.toString();
    }
    private static JsonObject citation(String url, String title) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "url_citation"); o.addProperty("start_index", 10); o.addProperty("end_index", 40);
        o.addProperty("url", url); if (title != null) o.addProperty("title", title);
        return o;
    }
    private static JsonObject message(String id, String text, JsonObject... annotations) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "output_text"); part.addProperty("text", text);
        JsonArray list = new JsonArray(); for (JsonObject a : annotations) list.add(a);
        part.add("annotations", list);
        JsonArray content = new JsonArray(); content.add(part);
        JsonObject item = new JsonObject();
        item.addProperty("id", id); item.addProperty("type", "message"); item.addProperty("status", "completed");
        item.addProperty("role", "assistant"); item.add("content", content);
        return item;
    }
    private static JsonObject search(String... urls) {
        JsonArray sources = new JsonArray();
        for (String url : urls) { JsonObject s = new JsonObject(); s.addProperty("type", "url"); s.addProperty("url", url); sources.add(s); }
        JsonObject action = new JsonObject();
        action.addProperty("type", "search"); action.addProperty("query", "tip calculator app features"); action.add("sources", sources);
        JsonObject item = new JsonObject();
        item.addProperty("id", "ws_1"); item.addProperty("type", "web_search_call"); item.addProperty("status", "completed");
        item.add("action", action);
        return item;
    }
    private static String completed(JsonObject... items) {
        JsonArray output = new JsonArray(); for (JsonObject item : items) output.add(item);
        JsonObject response = new JsonObject();
        response.addProperty("id", "resp_1"); response.addProperty("object", "response"); response.addProperty("status", "completed");
        response.addProperty("model", "fixture"); response.add("output", output);
        JsonObject event = new JsonObject();
        event.addProperty("type", "response.completed"); event.addProperty("sequence_number", 99); event.add("response", response);
        return event.toString();
    }

    @Test public void realisticWebSearchStreamYieldsFinalJsonAndCitedSourcesFirst() {
        ResearchOutput out = new ResearchOutput();
        assertFalse(out.accept("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\",\"status\":\"in_progress\"}}"));
        // A preamble message before the search must not be glued to the JSON answer.
        assertFalse(out.accept(delta("msg_0", "I'll look up comparable apps first.")));
        assertFalse(out.accept("{\"type\":\"response.web_search_call.in_progress\",\"item_id\":\"ws_1\",\"output_index\":1}"));
        assertFalse(out.accept("{\"type\":\"response.web_search_call.searching\",\"item_id\":\"ws_1\",\"output_index\":1}"));
        assertFalse(out.accept("{\"type\":\"response.web_search_call.completed\",\"item_id\":\"ws_1\",\"output_index\":1}"));
        int half = BRIEF.length() / 2;
        assertFalse(out.accept(delta("msg_1", BRIEF.substring(0, half))));
        assertFalse(out.accept(delta("msg_1", BRIEF.substring(half))));
        String completed = completed(
                message("msg_0", "I'll look up comparable apps first."),
                search("https://consulted.example.org/review", "https://www.nist.gov/guide?utm_source=openai", "http://insecure.example.com/"),
                message("msg_1", BRIEF,
                        citation("https://www.nist.gov/guide?utm_source=openai", "NIST guide"),
                        citation("https://www.NIST.gov/guide", "NIST guide again"),
                        citation("javascript:alert(1)", "Bad"),
                        citation("http://insecure.example.com/", "Plain http"),
                        citation("https://apps.example.com/tip-calc", null)));
        assertTrue(out.accept(completed));
        assertTrue(out.accept("{\"type\":\"error\"}")); // Ignored once complete.
        assertEquals(BRIEF, out.finish());
        List<ResearchBrief.Source> sources = out.sources();
        // Only cited pages are sources; the page the search merely visited is not, and OpenAI's tag is removed.
        assertEquals(2, sources.size());
        assertEquals("NIST guide", sources.get(0).title);
        assertEquals("https://www.nist.gov/guide", sources.get(0).url);
        assertEquals("apps.example.com", sources.get(1).title);
        assertTrue(sources.stream().noneMatch(s -> s.url.contains("consulted.example.org")));
        assertTrue(out.webSearchUsed());
        ResearchBrief brief = ResearchBrief.parseModelOutput(out.finish()).withSources(sources, out.webSearchUsed());
        assertEquals(brief.toJson(), ResearchBrief.parse(brief.toJson()).toJson());
    }
    @Test public void visitedPagesAreNeverSourcesButStillCountAsWebResearch() {
        ResearchOutput out = new ResearchOutput();
        out.accept(delta("msg_1", BRIEF));
        out.accept("{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":" + search("https://a.example.com/1", "https://b.example.com/2") + "}");
        assertTrue(out.accept(completed(message("msg_1", BRIEF))));
        assertTrue(out.sources().isEmpty());
        assertTrue(out.webSearchUsed());
    }
    @Test public void streamedAnnotationEventsAndNestedCitationsAreCollected() {
        ResearchOutput out = new ResearchOutput();
        out.accept(delta(null, BRIEF));
        out.accept("{\"type\":\"response.output_text.annotation.added\",\"annotation\":" + citation("https://streamed.example.com/", "Streamed") + "}");
        out.accept("{\"type\":\"response.output_text.annotation.added\",\"annotation\":{\"type\":\"url_citation\",\"url_citation\":{\"url\":\"https://nested.example.com/\",\"title\":\"Nested\"}}}");
        out.accept("{\"type\":\"response.output_text.annotation.added\",\"annotation\":{\"type\":\"file_citation\",\"file_id\":\"f\"}}");
        out.accept(completed());
        assertEquals(List.of("Streamed", "Nested"), out.sources().stream().map(s -> s.title).toList());
    }
    @Test public void sourcesAreTruncatedToTwelve() {
        JsonObject[] citations = new JsonObject[20];
        for (int i = 0; i < citations.length; i++) citations[i] = citation("https://example.com/" + i, "Source " + i);
        String[] consulted = new String[30];
        for (int i = 0; i < consulted.length; i++) consulted[i] = "https://other.example.com/" + i;
        ResearchOutput out = new ResearchOutput();
        out.accept(delta("msg_1", BRIEF));
        out.accept(completed(search(consulted), message("msg_1", BRIEF, citations)));
        List<ResearchBrief.Source> sources = out.sources();
        assertEquals(ResearchBrief.MAX_SOURCES, sources.size());
        assertEquals("Source 0", sources.get(0).title); assertEquals("Source 11", sources.get(11).title);
        assertTrue(sources.stream().noneMatch(s -> s.url.startsWith("https://other.example.com/")));
    }
    @Test public void completedMessageTextIsUsedWhenNoDeltasStreamed() {
        ResearchOutput out = new ResearchOutput();
        assertTrue(out.accept(completed(message("msg_0", "Searching now."), message("msg_1", "```json\n" + BRIEF + "\n```"))));
        assertEquals("```json\n" + BRIEF + "\n```", out.finish());
        assertFalse(out.webSearchUsed()); assertTrue(out.sources().isEmpty());
    }
    @Test public void noSearchMeansNoSourcesAndNoWebFlag() {
        ResearchOutput out = new ResearchOutput();
        out.accept(delta("msg_1", BRIEF));
        assertTrue(out.accept("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}"));
        assertEquals(BRIEF, out.finish());
        assertFalse(out.webSearchUsed()); assertTrue(out.sources().isEmpty());
    }
    @Test public void onlyCompletedNonBlankOutputCanBeUsed() {
        ResearchOutput partial = new ResearchOutput();
        partial.accept(delta("msg_1", BRIEF));
        assertThrows(IllegalStateException.class, partial::finish);
        ResearchOutput blank = new ResearchOutput();
        blank.accept(delta("msg_1", "   "));
        blank.accept("{\"type\":\"response.completed\"}");
        assertThrows(IllegalStateException.class, blank::finish);
        ResearchOutput unfinished = new ResearchOutput();
        assertThrows(IllegalStateException.class, () -> unfinished.accept("{\"type\":\"response.completed\",\"response\":{\"status\":\"incomplete\"}}"));
    }
    @Test public void failureRefusalAndMalformedEventsStopTheStream() {
        for (String type : new String[]{"response.failed", "response.incomplete", "response.refusal.delta", "error"}) {
            ResearchOutput out = new ResearchOutput();
            assertThrows(IllegalStateException.class, () -> out.accept("{\"type\":\"" + type + "\"}"));
        }
        for (String payload : new String[]{"{private-marker", "[]", "{\"type\":\"response.output_text.delta\",\"delta\":5}",
                "{\"type\":\"response.output_text.delta\"}", "[".repeat(30)}) {
            ResearchOutput out = new ResearchOutput();
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> out.accept(payload));
            assertFalse(error.getMessage().contains("private-marker"));
        }
        ResearchOutput tooMany = new ResearchOutput();
        JsonArray output = new JsonArray(); for (int i = 0; i < 257; i++) output.add(new JsonObject());
        assertThrows(IllegalStateException.class, () -> tooMany.accept("{\"type\":\"response.completed\",\"response\":{\"output\":" + output + "}}"));
    }
    @Test public void boundsTextAndEventSize() {
        ResearchOutput out = new ResearchOutput();
        out.accept(delta("msg_1", "x".repeat(ResearchOutput.MAX_TEXT_CHARS)));
        assertThrows(IllegalStateException.class, () -> out.accept(delta("msg_2", "x")));
        ResearchOutput big = new ResearchOutput();
        assertThrows(IllegalStateException.class, () -> big.accept(" ".repeat(ResearchOutput.MAX_EVENT_CHARS + 1)));
        ResearchOutput items = new ResearchOutput();
        for (int i = 0; i < 256; i++) items.accept(delta("msg_" + i, "x"));
        assertThrows(IllegalStateException.class, () -> items.accept(delta("msg_overflow", "x")));
    }
}

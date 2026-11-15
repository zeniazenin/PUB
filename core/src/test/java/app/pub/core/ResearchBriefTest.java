package app.pub.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;
import static org.junit.Assert.*;

public class ResearchBriefTest {
    private static JsonObject feature(String title, String priority, boolean feasible, String notes) {
        JsonObject o = new JsonObject();
        o.addProperty("title", title); o.addProperty("priority", priority);
        o.addProperty("feasibleInPub", feasible); o.addProperty("notes", notes);
        return o;
    }
    private static JsonArray strings(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        return array;
    }
    private static JsonArray repeat(int count, JsonElement value) {
        JsonArray array = new JsonArray();
        for (int i = 0; i < count; i++) array.add(value.deepCopy());
        return array;
    }
    /** A realistic tip-calculator brief in the model-output schema. */
    private static JsonObject valid() {
        JsonObject o = new JsonObject();
        o.addProperty("summary", "A tip calculator for diners splitting a restaurant bill; its main job is a correct per-person total.");
        o.addProperty("category", "Finance calculator");
        JsonObject comparable = new JsonObject();
        comparable.addProperty("name", "Tip Calc Pro"); comparable.addProperty("takeaway", "Preset tip chips make the common case one tap.");
        JsonArray comparables = new JsonArray(); comparables.add(comparable);
        o.add("comparables", comparables);
        JsonArray features = new JsonArray();
        features.add(feature("Bill amount input", "must", true, ""));
        features.add(feature("Tip presets 10/15/18/20%", "must", true, "Use choices"));
        features.add(feature("Remember last tip", "should", true, "storage.project"));
        features.add(feature("Receipt scanning", "could", false, "No OCR in PUB"));
        o.add("features", features);
        o.add("logic", strings("tip = bill * percent / 100", "Round currency to 2 decimals at display time only"));
        o.add("ux", strings("Primary result in display style near the top"));
        o.add("pitfalls", strings("Totals that do not add up after rounding"));
        o.add("acceptanceChecks", strings("Bill 100, tip 15% -> tip 15.00, total 115.00", "Bill 100, 3 people, 15% -> 38.33 each"));
        o.add("assumptions", strings("Currency is shown without a symbol"));
        return o;
    }
    private static String with(Consumer<JsonObject> change) {
        JsonObject o = valid(); change.accept(o); return o.toString();
    }
    private static void rejected(String json) {
        assertThrows(IllegalArgumentException.class, () -> ResearchBrief.parseModelOutput(json));
    }

    @Test public void parsesACompleteBrief() {
        ResearchBrief brief = ResearchBrief.parseModelOutput(valid().toString());
        assertTrue(brief.summary.startsWith("A tip calculator"));
        assertEquals("Finance calculator", brief.category);
        assertEquals(1, brief.comparables.size()); assertEquals("Tip Calc Pro", brief.comparables.get(0).name);
        assertEquals(4, brief.features.size());
        ResearchBrief.Feature scan = brief.features.get(3);
        assertEquals("could", scan.priority); assertFalse(scan.feasibleInPub); assertEquals("No OCR in PUB", scan.notes);
        assertEquals("", brief.features.get(0).notes);
        assertEquals(2, brief.logic.size()); assertEquals(1, brief.ux.size()); assertEquals(1, brief.pitfalls.size());
        assertEquals(2, brief.acceptanceChecks.size()); assertEquals(1, brief.assumptions.size());
        assertTrue(brief.sources.isEmpty()); assertFalse(brief.web);
    }
    @Test public void optionalListsMayBeOmittedButCoreFieldsAreRequired() {
        JsonObject minimal = new JsonObject();
        minimal.addProperty("summary", "A counter"); minimal.addProperty("category", "Utility");
        JsonArray features = new JsonArray(); features.add(feature("Count up", "must", true, ""));
        minimal.add("features", features);
        ResearchBrief brief = ResearchBrief.parseModelOutput(minimal.toString());
        assertTrue(brief.comparables.isEmpty()); assertTrue(brief.logic.isEmpty()); assertTrue(brief.assumptions.isEmpty());
        for (String field : new String[]{"summary", "category", "features"}) rejected(with(o -> o.remove(field)));
        rejected(with(o -> o.add("features", new JsonArray())));
    }
    @Test public void enforcesEveryTextBound() {
        Object[][] bounds = {{"summary", 600}, {"category", 60}};
        for (Object[] bound : bounds) {
            String field = (String) bound[0]; int limit = (Integer) bound[1];
            assertNotNull(ResearchBrief.parseModelOutput(with(o -> o.addProperty(field, "s".repeat(limit)))));
            rejected(with(o -> o.addProperty(field, "s".repeat(limit + 1))));
        }
        Object[][] lists = {{"logic", 12, 400}, {"ux", 12, 300}, {"pitfalls", 8, 300}, {"acceptanceChecks", 10, 300}, {"assumptions", 6, 300}};
        for (Object[] list : lists) {
            String field = (String) list[0]; int count = (Integer) list[1], limit = (Integer) list[2];
            ResearchBrief full = ResearchBrief.parseModelOutput(with(o -> o.add(field, repeat(count, new com.google.gson.JsonPrimitive("x".repeat(limit))))));
            assertNotNull(full);
            rejected(with(o -> o.add(field, repeat(count + 1, new com.google.gson.JsonPrimitive("x")))));
            rejected(with(o -> o.add(field, strings("x".repeat(limit + 1)))));
        }
        JsonObject comparable = new JsonObject();
        comparable.addProperty("name", "n".repeat(80)); comparable.addProperty("takeaway", "t".repeat(300));
        assertEquals(8, ResearchBrief.parseModelOutput(with(o -> o.add("comparables", repeat(8, comparable)))).comparables.size());
        rejected(with(o -> o.add("comparables", repeat(9, comparable))));
        JsonObject longName = comparable.deepCopy(); longName.addProperty("name", "n".repeat(81));
        rejected(with(o -> o.add("comparables", repeat(1, longName))));
        JsonObject longTakeaway = comparable.deepCopy(); longTakeaway.addProperty("takeaway", "t".repeat(301));
        rejected(with(o -> o.add("comparables", repeat(1, longTakeaway))));
        JsonObject maximal = feature("f".repeat(80), "should", true, "n".repeat(300));
        assertEquals(16, ResearchBrief.parseModelOutput(with(o -> o.add("features", repeat(16, maximal)))).features.size());
        rejected(with(o -> o.add("features", repeat(17, maximal))));
        rejected(with(o -> o.add("features", repeat(1, feature("f".repeat(81), "must", true, "")))));
        rejected(with(o -> o.add("features", repeat(1, feature("Fine", "must", true, "n".repeat(301))))));
        rejected(" ".repeat(ResearchBrief.MAX_JSON_CHARS + 1));
    }
    @Test public void blankTextIsRejectedWhereRequired() {
        rejected(with(o -> o.addProperty("summary", "   ")));
        rejected(with(o -> o.addProperty("category", "")));
        rejected(with(o -> o.add("logic", strings("Valid", " "))));
        rejected(with(o -> o.add("features", repeat(1, feature(" ", "must", true, "")))));
        JsonObject comparable = new JsonObject(); comparable.addProperty("name", "App"); comparable.addProperty("takeaway", "");
        rejected(with(o -> o.add("comparables", repeat(1, comparable))));
        // Infeasible features must explain why, so the builder can be honest about the limit.
        rejected(with(o -> o.add("features", repeat(1, feature("Push reminders", "must", false, " ")))));
        JsonObject noNotes = feature("Reminders", "must", false, "x"); noNotes.remove("notes");
        rejected(with(o -> o.add("features", repeat(1, noNotes))));
        JsonObject feasibleNoNotes = feature("Count", "must", true, "x"); feasibleNoNotes.remove("notes");
        assertEquals("", ResearchBrief.parseModelOutput(with(o -> o.add("features", repeat(1, feasibleNoNotes)))).features.get(0).notes);
    }
    @Test public void priorityIsAnExactEnumAndFeasibilityABoolean() {
        for (String priority : new String[]{"must", "should", "could"})
            assertEquals(priority, ResearchBrief.parseModelOutput(with(o -> o.add("features", repeat(1, feature("A", priority, true, ""))))).features.get(0).priority);
        for (String priority : new String[]{"MUST", "Must", " must", "high", "wont", ""})
            rejected(with(o -> o.add("features", repeat(1, feature("A", priority, true, "")))));
        JsonObject numeric = feature("A", "must", true, ""); numeric.addProperty("priority", 1);
        rejected(with(o -> o.add("features", repeat(1, numeric))));
        JsonObject stringBoolean = feature("A", "must", true, ""); stringBoolean.addProperty("feasibleInPub", "true");
        rejected(with(o -> o.add("features", repeat(1, stringBoolean))));
        JsonObject missing = feature("A", "must", true, ""); missing.remove("feasibleInPub");
        rejected(with(o -> o.add("features", repeat(1, missing))));
    }
    @Test public void rejectsUnknownDuplicateAndHostOnlyFields() {
        rejected(with(o -> o.addProperty("name", "Tipster")));
        rejected(with(o -> o.add("sources", new JsonArray())));
        rejected(with(o -> o.addProperty("web", true)));
        JsonObject extraFeature = feature("A", "must", true, ""); extraFeature.addProperty("code", "alert(1)");
        rejected(with(o -> o.add("features", repeat(1, extraFeature))));
        JsonObject extraComparable = new JsonObject();
        extraComparable.addProperty("name", "A"); extraComparable.addProperty("takeaway", "B"); extraComparable.addProperty("url", "https://example.com");
        rejected(with(o -> o.add("comparables", repeat(1, extraComparable))));
        String json = valid().toString();
        rejected(json.replaceFirst("\"summary\":", "\"summary\":\"First\",\"summary\":"));
        rejected(json.replaceFirst("\"title\":\"Bill amount input\"", "\"title\":\"Bill amount input\",\"title\":\"Other\""));
        rejected(json.replaceFirst("\"priority\":\"must\"", "\"priority\":\"must\",\"priority\":\"could\""));
    }
    @Test public void rejectsWrongTypesMalformedAndLenientJson() {
        String valid = valid().toString();
        for (String json : new String[]{null, "", " ", "[]", "null", "\"brief\"", "Here is the brief: " + valid, valid + " extra",
                valid + valid, "/* note */" + valid, valid.replace("\"summary\":", "summary:"), valid.substring(0, valid.length() - 1) + ",}",
                with(o -> o.addProperty("summary", 5)), with(o -> o.add("logic", new JsonObject())),
                with(o -> o.add("logic", repeat(1, new JsonArray()))), with(o -> o.add("features", feature("A", "must", true, "")))}) {
            rejected(json);
        }
        rejected("{".repeat(30) + "}".repeat(30));
    }
    @Test public void toleratesOneSurroundingJsonFence() {
        String json = valid().toString();
        for (String text : new String[]{"```json\n" + json + "\n```", "```\n" + json + "\n```", "  ```JSON\n" + json + "```  \n", "\n" + json + "\n"})
            assertEquals(4, ResearchBrief.parseModelOutput(text).features.size());
        for (String text : new String[]{"```python\n" + json + "\n```", "```json\n" + json, "```json " + json + "```", "Brief:\n```json\n" + json + "\n```", "```"})
            rejected(text);
    }
    @Test public void inlineCitationsAndControlCharactersAreCleaned() {
        ResearchBrief brief = ResearchBrief.parseModelOutput(with(o -> {
            o.addProperty("summary", "Tip around 15–20% in the US ([example.com](https://example.com/tipping?utm_source=openai)).");
            o.add("logic", strings("Use the [IRS](https://www.irs.gov/x) definition", "tab\there\u0007bell‮end"));
        }));
        assertEquals("Tip around 15–20% in the US.", brief.summary);
        assertEquals("Use the IRS definition", brief.logic.get(0));
        assertEquals("tab here bell end", brief.logic.get(1));
        assertFalse(brief.toJson().contains("https://example.com"));
    }
    @Test public void storedFormRoundTripsWithSources() {
        List<ResearchBrief.Source> sources = List.of(
                ResearchBrief.Source.of("Tipping guide", "https://example.com/tipping?utm_source=openai"),
                ResearchBrief.Source.of(null, "https://www.nist.gov/pml/weights-and-measures"));
        ResearchBrief brief = ResearchBrief.parseModelOutput(valid().toString()).withSources(sources, true);
        assertTrue(brief.web); assertEquals(2, brief.sources.size());
        assertEquals("www.nist.gov", brief.sources.get(1).title);
        String stored = brief.toJson();
        ResearchBrief restored = ResearchBrief.parse(stored);
        assertEquals(stored, restored.toJson());
        assertTrue(restored.web); assertEquals("https://example.com/tipping?utm_source=openai", restored.sources.get(0).url);
        assertEquals(java.util.Set.of("summary", "category", "comparables", "features", "logic", "ux", "pitfalls",
                "acceptanceChecks", "assumptions", "sources", "web"), JsonParser.parseString(stored).getAsJsonObject().keySet());
        // A model-knowledge brief stores an explicit empty source list.
        ResearchBrief offline = ResearchBrief.parseModelOutput(valid().toString()).withSources(List.of(), false);
        assertEquals(offline.toJson(), ResearchBrief.parse(offline.toJson()).toJson());
        assertThrows(IllegalArgumentException.class, () -> ResearchBrief.parseModelOutput(stored));
    }
    @Test public void storedFormIsStrict() {
        JsonObject stored = JsonParser.parseString(ResearchBrief.parseModelOutput(valid().toString())
                .withSources(List.of(ResearchBrief.Source.of("Guide", "https://example.com/a")), true).toJson()).getAsJsonObject();
        assertNotNull(ResearchBrief.parse(stored.toString()));
        for (String field : new String[]{"web", "sources", "logic", "comparables"}) {
            JsonObject missing = stored.deepCopy(); missing.remove(field);
            assertThrows(IllegalArgumentException.class, () -> ResearchBrief.parse(missing.toString()));
        }
        JsonObject extra = stored.deepCopy(); extra.addProperty("accessToken", "x");
        JsonObject webString = stored.deepCopy(); webString.addProperty("web", "true");
        List<String> invalid = new ArrayList<>(List.of(extra.toString(), webString.toString(), "{}", ""));
        for (String url : new String[]{"http://example.com/a", "javascript:alert(1)", "https://user:pw@example.com/", "https://exa mple.com/",
                "https://example.com/‮gnp", "https://localhost/", "https:///path", "file:///sdcard/x", "https://example.com/" + "a".repeat(600)}) {
            JsonObject bad = stored.deepCopy();
            JsonObject source = new JsonObject(); source.addProperty("title", "T"); source.addProperty("url", url);
            JsonArray list = new JsonArray(); list.add(source); bad.add("sources", list);
            invalid.add(bad.toString());
        }
        JsonObject blankTitle = stored.deepCopy(); blankTitle.getAsJsonArray("sources").get(0).getAsJsonObject().addProperty("title", " ");
        JsonObject longTitle = stored.deepCopy(); longTitle.getAsJsonArray("sources").get(0).getAsJsonObject().addProperty("title", "t".repeat(201));
        JsonObject duplicate = stored.deepCopy(); duplicate.getAsJsonArray("sources").add(duplicate.getAsJsonArray("sources").get(0).deepCopy());
        JsonObject tooMany = stored.deepCopy(); JsonArray many = new JsonArray();
        for (int i = 0; i < 13; i++) { JsonObject s = new JsonObject(); s.addProperty("title", "S" + i); s.addProperty("url", "https://example.com/" + i); many.add(s); }
        tooMany.add("sources", many);
        invalid.addAll(List.of(blankTitle.toString(), longTitle.toString(), duplicate.toString(), tooMany.toString()));
        for (String json : invalid) assertThrows(IllegalArgumentException.class, () -> ResearchBrief.parse(json));
        assertThrows(IllegalArgumentException.class, () -> ResearchBrief.parse(" ".repeat(ResearchBrief.MAX_STORED_JSON_CHARS + 1)));
    }
    @Test public void withSourcesDeduplicatesAndKeepsAtMostTwelve() {
        List<ResearchBrief.Source> sources = new ArrayList<>();
        sources.add(ResearchBrief.Source.of("First", "https://Example.com/page?utm_source=openai"));
        sources.add(ResearchBrief.Source.of("Same page", "https://example.com/page"));
        sources.add(ResearchBrief.Source.of("Same page, fragment", "https://example.com/page#top"));
        sources.add(null);
        for (int i = 0; i < 20; i++) sources.add(ResearchBrief.Source.of("Page " + i, "https://example.org/" + i));
        ResearchBrief brief = ResearchBrief.parseModelOutput(valid().toString()).withSources(sources, true);
        assertEquals(12, brief.sources.size());
        assertEquals("First", brief.sources.get(0).title);
        assertEquals("Page 0", brief.sources.get(1).title);
        assertEquals("Page 10", brief.sources.get(11).title);
    }
    @Test public void sourceConstructionOnlyAcceptsSafeHttpsLinks() {
        assertNull(ResearchBrief.Source.of("x", "http://example.com"));
        assertNull(ResearchBrief.Source.of("x", "HTTPS://example.com"));
        assertNull(ResearchBrief.Source.of("x", null));
        assertNull(ResearchBrief.Source.of("x", "https://example.com/a b"));
        assertNull(ResearchBrief.Source.of("x", "https://example.com:bad/"));
        assertNull(ResearchBrief.Source.of("x", "https://例子.example.com/")); // Hosts must be ASCII (punycode).
        assertNotNull(ResearchBrief.Source.of("x", "https://xn--fsqu00a.example.com/"));
        ResearchBrief.Source source = ResearchBrief.Source.of("  A\n\ttitle​  " + "t".repeat(300), "https://example.com/é");
        assertNotNull(source);
        assertTrue(source.title.startsWith("A title "));
        assertEquals(ResearchBrief.Source.MAX_TITLE, source.title.length());
        assertEquals("example.com", ResearchBrief.Source.of("   ", "https://example.com/").title);
    }
    @Test public void promptJsonIsCompactAndKeepsSourceLinks() {
        JsonObject minimal = new JsonObject();
        minimal.addProperty("summary", "A counter"); minimal.addProperty("category", "Utility");
        minimal.add("features", repeat(1, feature("Count up", "must", true, "")));
        ResearchBrief brief = ResearchBrief.parseModelOutput(minimal.toString())
                .withSources(List.of(ResearchBrief.Source.of("Guide", "https://example.com/guide")), true);
        JsonObject prompt = JsonParser.parseString(brief.toPromptJson()).getAsJsonObject();
        assertEquals(java.util.Set.of("summary", "category", "features", "web", "sources"), prompt.keySet());
        JsonObject source = prompt.getAsJsonArray("sources").get(0).getAsJsonObject();
        assertEquals(java.util.Set.of("title", "url"), source.keySet());
        assertEquals("https://example.com/guide", source.get("url").getAsString());
        JsonObject full = JsonParser.parseString(ResearchBrief.parseModelOutput(valid().toString()).toPromptJson()).getAsJsonObject();
        assertTrue(full.has("acceptanceChecks")); assertTrue(full.has("logic")); assertFalse(full.has("sources"));
        assertFalse(full.get("web").getAsBoolean());
    }
    @Test public void modelJsonIsTheModelSchemaAndRoundTripsWithoutHostFields() {
        ResearchBrief brief = ResearchBrief.parseModelOutput(valid().toString())
                .withSources(List.of(ResearchBrief.Source.of("Guide", "https://example.com/guide")), true);
        String model = brief.toModelJson();
        JsonObject o = JsonParser.parseString(model).getAsJsonObject();
        assertEquals(java.util.Set.of("summary", "category", "comparables", "features", "logic", "ux", "pitfalls",
                "acceptanceChecks", "assumptions"), o.keySet());
        assertFalse(model.contains("https://example.com/guide"));
        ResearchBrief again = ResearchBrief.parseModelOutput(model);
        assertEquals(brief.withSources(List.of(), false).toJson(), again.toJson());
        // Empty lists stay present, so the model sees every field it may fill.
        JsonObject minimal = new JsonObject();
        minimal.addProperty("summary", "A counter"); minimal.addProperty("category", "Utility");
        minimal.add("features", repeat(1, feature("Count up", "must", true, "")));
        JsonObject empty = JsonParser.parseString(ResearchBrief.parseModelOutput(minimal.toString()).toModelJson()).getAsJsonObject();
        assertEquals(0, empty.getAsJsonArray("logic").size()); assertEquals(0, empty.getAsJsonArray("comparables").size());
    }
    @Test public void listsAreImmutableAndErrorsDoNotEchoOutput() {
        ResearchBrief brief = ResearchBrief.parseModelOutput(valid().toString());
        assertThrows(UnsupportedOperationException.class, () -> brief.features.clear());
        assertThrows(UnsupportedOperationException.class, () -> brief.logic.add("x"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ResearchBrief.parseModelOutput("{private-marker"));
        assertFalse(error.getMessage().contains("private-marker"));
        assertNull(error.getCause());
    }
}

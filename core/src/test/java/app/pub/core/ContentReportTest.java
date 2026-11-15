package app.pub.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import static org.junit.Assert.*;

/** Content reports carry fixed fields at fixed limits, and the reported content only when the user includes it. */
public class ContentReportTest {
    private static final Path ENDPOINT = Path.of("tools/report-endpoint/Code.gs");

    private static ProjectBundle app(String source) {
        JsonObject o = new JsonObject();
        o.addProperty("name", "Egg timer");
        o.addProperty("description", "Counts down soft, medium and hard eggs.");
        o.addProperty("source", source);
        o.add("capabilities", new JsonArray());
        return ProjectBundle.parse(o.toString());
    }

    private static JsonArray strings(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(new JsonPrimitive(value));
        return array;
    }

    private static ResearchBrief brief(String summary, int sources) {
        JsonObject o = new JsonObject();
        o.addProperty("summary", summary);
        o.addProperty("category", "Kitchen timer");
        o.add("comparables", new JsonArray());
        JsonObject feature = new JsonObject();
        feature.addProperty("title", "Countdown"); feature.addProperty("priority", "must");
        feature.addProperty("feasibleInPub", true); feature.addProperty("notes", "");
        JsonArray features = new JsonArray(); features.add(feature);
        o.add("features", features);
        o.add("logic", strings("Soft 6 min"));
        o.add("ux", strings("Large time display"));
        o.add("pitfalls", strings("Timer stops when the screen turns off"));
        o.add("acceptanceChecks", strings("Soft shows 6:00"));
        o.add("assumptions", strings("Eggs start in boiling water"));
        List<ResearchBrief.Source> links = new ArrayList<>();
        for (int i = 0; i < sources; i++) links.add(ResearchBrief.Source.of("Source " + i, "https://example.com/" + i));
        return ResearchBrief.parseModelOutput(o.toString()).withSources(links, true);
    }

    @Test public void reportWithoutContentHasOnlyTheFixedFields() {
        JsonObject report = ContentReport.app(ContentReport.Subject.APP, ContentReport.Reason.OFFENSIVE, "  Rude text on the result screen \n", "0.7.0 (11)", null);
        assertEquals(Set.of("kind", "version", "subject", "reason", "details", "pubVersion"), report.keySet());
        assertEquals("pub-content-report", report.get("kind").getAsString());
        assertEquals(1, report.get("version").getAsInt());
        assertEquals("app", report.get("subject").getAsString());
        assertEquals("offensive", report.get("reason").getAsString());
        assertEquals("Rude text on the result screen", report.get("details").getAsString());
        assertEquals("0.7.0 (11)", report.get("pubVersion").getAsString());
    }

    @Test public void includedAppAttachesNameDescriptionAndCode() {
        JsonObject report = ContentReport.app(ContentReport.Subject.BUILD, ContentReport.Reason.HARMFUL, "", "1", app("function dispatch(e){return '{}'}"));
        assertEquals("build", report.get("subject").getAsString());
        JsonObject content = report.getAsJsonObject("content");
        assertEquals(Set.of("name", "description", "code", "codeTruncated"), content.keySet());
        assertEquals("Egg timer", content.get("name").getAsString());
        assertEquals("Counts down soft, medium and hard eggs.", content.get("description").getAsString());
        assertEquals("function dispatch(e){return '{}'}", content.get("code").getAsString());
        assertFalse(content.get("codeTruncated").getAsBoolean());
    }

    @Test public void longCodeIsTruncatedWithoutSplittingACharacter() {
        String source = "x".repeat(ContentReport.MAX_CODE - 1) + "🥚" + "tail";
        JsonObject content = ContentReport.app(ContentReport.Subject.APP, ContentReport.Reason.OTHER, "", "1", app(source)).getAsJsonObject("content");
        String code = content.get("code").getAsString();
        assertEquals(ContentReport.MAX_CODE - 1, code.length());
        assertFalse(Character.isHighSurrogate(code.charAt(code.length() - 1)));
        assertTrue(content.get("codeTruncated").getAsBoolean());
    }

    @Test public void detailsAreCleanedAndLimited() {
        String details = "line one\nline\u0000 two\u0007" + "d".repeat(ContentReport.MAX_DETAILS);
        String sent = ContentReport.app(ContentReport.Subject.APP, ContentReport.Reason.OTHER, details, null, null).get("details").getAsString();
        assertEquals(ContentReport.MAX_DETAILS, sent.length());
        assertTrue(sent.startsWith("line one\nline two"));
        assertEquals("", ContentReport.app(ContentReport.Subject.APP, ContentReport.Reason.OTHER, null, null, null).get("pubVersion").getAsString());
    }

    @Test public void researchReportAttachesSummaryAndAtMostTwelveSources() {
        JsonObject report = ContentReport.research(ContentReport.Reason.MISLEADING, "Wrong cooking times", "1", brief("Boil eggs for 6, 8 or 10 minutes.", 12));
        assertEquals("research", report.get("subject").getAsString());
        JsonObject content = report.getAsJsonObject("content");
        assertEquals(Set.of("research", "sources"), content.keySet());
        assertEquals("Boil eggs for 6, 8 or 10 minutes.", content.get("research").getAsString());
        JsonArray sources = content.getAsJsonArray("sources");
        assertEquals(12, sources.size());
        assertEquals("https://example.com/0", sources.get(0).getAsJsonObject().get("url").getAsString());
        assertFalse(ContentReport.research(ContentReport.Reason.OTHER, "", "1", null).has("content"));
    }

    @Test public void appReportsRejectTheResearchSubject() {
        assertThrows(IllegalArgumentException.class, () -> ContentReport.app(ContentReport.Subject.RESEARCH, ContentReport.Reason.OTHER, "", "1", null));
        assertThrows(IllegalArgumentException.class, () -> ContentReport.app(ContentReport.Subject.APP, null, "", "1", null));
    }

    /** The Apps Script endpoint rejects reports whose subject or reason it does not list. */
    @Test public void endpointAcceptsEverySubjectAndReason() throws Exception {
        String script = Files.readString(ENDPOINT);
        assertEquals(Arrays.stream(ContentReport.Subject.values()).map(s -> s.id).toList(), listed(script, "SUBJECTS"));
        assertEquals(Arrays.stream(ContentReport.Reason.values()).map(r -> r.id).toList(), listed(script, "REASONS"));
        assertTrue(script.contains("o.kind !== '" + ContentReport.KIND + "'"));
        assertTrue(script.contains("o.version !== " + ContentReport.VERSION));
        assertTrue(script.contains("text(o.details, " + ContentReport.MAX_DETAILS + ")"));
        assertTrue(script.contains("text(c.code, " + ContentReport.MAX_CODE + ")"));
        assertTrue(script.contains("text(c.research, " + ContentReport.MAX_RESEARCH + ")"));
    }

    private static List<String> listed(String script, String constant) {
        Matcher list = Pattern.compile("const " + constant + " = \\[([^\\]]*)\\];").matcher(script);
        assertTrue(constant + " not found", list.find());
        List<String> ids = new ArrayList<>();
        Matcher id = Pattern.compile("'([a-z]+)'").matcher(list.group(1));
        while (id.find()) ids.add(id.group(1));
        return ids;
    }
}

package app.pub.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * A user's report about content PUB produced: a build's draft app, its research, or a saved app. Google Play's
 * AI-Generated Content policy requires apps that generate content with AI to let users report offensive output to the
 * developer without leaving the app; the host sends this JSON to the developer's report endpoint
 * (tools/report-endpoint).
 *
 * A report holds only fixed fields at fixed limits. The reported content is attached only when the user chooses to
 * include it, and a report never carries phone context, account details, saved app data, files or photos.
 */
public final class ContentReport {
    public static final String KIND = "pub-content-report";
    public static final int VERSION = 1;
    public static final int MAX_DETAILS = 2_000;
    public static final int MAX_CODE = 40_000;
    public static final int MAX_RESEARCH = 4_000;
    public static final int MAX_VERSION = 40;

    /** What the report is about. The ids are part of the endpoint contract. */
    public enum Subject {
        BUILD("build"), RESEARCH("research"), APP("app");
        public final String id;
        Subject(String id) { this.id = id; }
    }

    /** Why the user is reporting, in the order the form lists them. The ids are part of the endpoint contract. */
    public enum Reason {
        OFFENSIVE("offensive", "Offensive or hateful"),
        SEXUAL("sexual", "Sexual content"),
        HARMFUL("harmful", "Dangerous or harmful"),
        MISLEADING("misleading", "Inaccurate or misleading"),
        OTHER("other", "Something else");
        public final String id, label;
        Reason(String id, String label) { this.id = id; this.label = label; }
    }

    private ContentReport() {}

    /**
     * A report about an app: a build's draft (BUILD) or a saved app (APP). Pass the app only when
     * the user chose to include it; its name, description and code (up to MAX_CODE characters) are then attached.
     */
    public static JsonObject app(Subject subject, Reason reason, String details, String pubVersion, ProjectBundle included) {
        if (subject == Subject.RESEARCH) throw new IllegalArgumentException("Use research() for research reports");
        JsonObject report = base(subject, reason, details, pubVersion);
        if (included != null) {
            JsonObject content = new JsonObject();
            content.addProperty("name", included.name);
            content.addProperty("description", included.description);
            content.addProperty("code", clip(included.source, MAX_CODE));
            content.addProperty("codeTruncated", included.source.length() > MAX_CODE);
            report.add("content", content);
        }
        return report;
    }

    /** A report about a build's research. Pass the brief only when the user chose to include its summary and sources. */
    public static JsonObject research(Reason reason, String details, String pubVersion, ResearchBrief included) {
        JsonObject report = base(Subject.RESEARCH, reason, details, pubVersion);
        if (included != null) {
            JsonObject content = new JsonObject();
            content.addProperty("research", clip(included.summary, MAX_RESEARCH));
            JsonArray sources = new JsonArray();
            for (ResearchBrief.Source source : included.sources) {
                if (sources.size() >= ResearchBrief.MAX_SOURCES) break;
                JsonObject link = new JsonObject();
                link.addProperty("title", source.title);
                link.addProperty("url", source.url);
                sources.add(link);
            }
            content.add("sources", sources);
            report.add("content", content);
        }
        return report;
    }

    private static JsonObject base(Subject subject, Reason reason, String details, String pubVersion) {
        if (subject == null || reason == null) throw new IllegalArgumentException("Missing subject or reason");
        JsonObject report = new JsonObject();
        report.addProperty("kind", KIND);
        report.addProperty("version", VERSION);
        report.addProperty("subject", subject.id);
        report.addProperty("reason", reason.id);
        report.addProperty("details", clip(clean(details).trim(), MAX_DETAILS));
        report.addProperty("pubVersion", clip(clean(pubVersion).trim(), MAX_VERSION));
        return report;
    }

    /** Removes control characters other than line breaks and tabs. */
    private static String clean(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\t' || !Character.isISOControl(c)) out.append(c);
        }
        return out.toString();
    }

    /** The first max characters, never ending in half of a surrogate pair. */
    static String clip(String text, int max) {
        if (text.length() <= max) return text;
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }
}

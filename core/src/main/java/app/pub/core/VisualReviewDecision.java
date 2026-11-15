package app.pub.core;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A bounded visual assessment, not proof of functional correctness. Invalid data never means pass. */
public final class VisualReviewDecision {
    public static final int MAX_JSON_CHARS = 32_768;
    public final String status;
    public final String summary;
    public final List<String> issues;

    private VisualReviewDecision(String status, String summary, List<String> issues) {
        this.status = status;
        this.summary = summary;
        this.issues = List.copyOf(issues);
    }

    public String getStatus() { return status; }
    public String getSummary() { return summary; }
    public List<String> getIssues() { return issues; }
    public boolean isPass() { return "pass".equals(status); }
    public boolean needsFix() { return "fix".equals(status); }
    public boolean isUnverifiable() { return "unverifiable".equals(status); }

    public static VisualReviewDecision parse(String json) {
        if (json == null || json.isBlank() || json.length() > MAX_JSON_CHARS) throw invalid();
        // Stream the tiny fixed schema so duplicate fields cannot overwrite a prior fix/refusal.
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw invalid();
            reader.beginObject();
            Set<String> fields = new HashSet<>();
            String status = null, summary = null;
            List<String> issues = new ArrayList<>();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!fields.add(field)) throw invalid();
                switch (field) {
                    case "status": status = text(reader, 12); break;
                    case "summary": summary = text(reader, 1000); break;
                    case "issues":
                        if (reader.peek() != JsonToken.BEGIN_ARRAY) throw invalid();
                        reader.beginArray();
                        while (reader.hasNext()) {
                            if (issues.size() == 5) throw invalid();
                            issues.add(text(reader, 600));
                        }
                        reader.endArray(); break;
                    default: throw invalid();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || !fields.equals(Set.of("status", "summary", "issues"))) throw invalid();
            if (!Set.of("pass", "fix", "unverifiable").contains(status)) throw invalid();
            if ("pass".equals(status) && !issues.isEmpty()) throw invalid();
            if ("fix".equals(status) && issues.isEmpty()) throw invalid();
            return new VisualReviewDecision(status, summary, issues);
        } catch (IOException | RuntimeException error) {
            // Do not include raw output or exception causes in UI/logs.
            throw invalid();
        }
    }

    private static String text(JsonReader reader, int limit) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw invalid();
        String value = reader.nextString();
        if (value.isBlank() || value.length() > limit) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Visual review returned an invalid decision. Automatic visual repair must stop.");
    }
}

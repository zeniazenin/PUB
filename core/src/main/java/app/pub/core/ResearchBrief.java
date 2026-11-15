package app.pub.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bounded pre-build research for one mini-app. Model output is untrusted data parsed against a fixed schema;
 * sources and the web flag are host-only and come from the response's own citations, never from model text.
 * Invalid data is rejected as a whole and errors never echo the output.
 */
public final class ResearchBrief {
    /** Limit for model output (also the streamed text limit in ResearchOutput). */
    public static final int MAX_JSON_CHARS = 100_000;
    /** Stored form: every field at its maximum, with JSON escaping, stays far below this. */
    public static final int MAX_STORED_JSON_CHARS = 200_000;
    public static final int MAX_SOURCES = 12;
    public static final Set<String> PRIORITIES = Set.of("must", "should", "could");

    public final String summary;
    public final String category;
    public final List<ComparableApp> comparables;
    public final List<Feature> features;
    public final List<String> logic;
    public final List<String> ux;
    public final List<String> pitfalls;
    public final List<String> acceptanceChecks;
    public final List<String> assumptions;
    public final List<Source> sources;
    /** True only when live web search was actually used for this brief. */
    public final boolean web;

    public static final class ComparableApp {
        public final String name;
        public final String takeaway;
        private ComparableApp(String name, String takeaway) { this.name = name; this.takeaway = takeaway; }
    }

    public static final class Feature {
        public final String title;
        /** Exactly "must", "should" or "could". */
        public final String priority;
        public final boolean feasibleInPub;
        /** Possibly empty; always explains why when feasibleInPub is false. */
        public final String notes;
        private Feature(String title, String priority, boolean feasibleInPub, String notes) {
            this.title = title; this.priority = priority; this.feasibleInPub = feasibleInPub; this.notes = notes;
        }
    }

    /** A plain https link with a display title. Construct with {@link #of}; invalid links are never created. */
    public static final class Source {
        public static final int MAX_TITLE = 200;
        public static final int MAX_URL = 600;
        public final String title;
        public final String url;
        private Source(String title, String url) { this.title = title; this.url = url; }

        /**
         * Tolerant constructor for links read from a response: returns null unless url is a safe https URL.
         * OpenAI's utm_source=openai tag is removed from the link. The title is cleaned and shortened; a blank
         * title falls back to the link's host.
         */
        public static Source of(String title, String url) {
            if (!isSafeUrl(url)) return null;
            url = withoutTracking(url);
            String clean = title == null ? "" : clean(title).replace('\n', ' ').replaceAll(" {2,}", " ").strip();
            if (clean.isEmpty()) clean = URI.create(url).getHost();
            if (clean.length() > MAX_TITLE) {
                int end = Character.isHighSurrogate(clean.charAt(MAX_TITLE - 1)) ? MAX_TITLE - 1 : MAX_TITLE;
                clean = clean.substring(0, end).strip();
            }
            return new Source(clean, url);
        }

        /** Only https, a dotted host, no credentials, whitespace, control or formatting characters. */
        public static boolean isSafeUrl(String url) {
            if (url == null || url.length() > MAX_URL || !url.startsWith("https://")) return false;
            for (int i = 0; i < url.length(); i++) {
                char c = url.charAt(i);
                if (c <= ' ' || c == 0x7f || Character.isWhitespace(c) || Character.isISOControl(c)
                        || Character.getType(c) == Character.FORMAT) return false;
            }
            try {
                URI uri = new URI(url);
                String host = uri.getHost();
                return "https".equals(uri.getScheme()) && uri.getRawUserInfo() == null && host != null
                        && host.indexOf('.') > 0 && !host.endsWith(".");
            } catch (URISyntaxException error) {
                return false;
            }
        }

        /**
         * Removes the utm_source=openai parameter that web search appends to result links, keeping every other
         * query parameter (such as an app store id) and the fragment. Applied to a URL that already passed
         * {@link #isSafeUrl}; the result is a substring-only edit of it, so it stays safe.
         */
        static String withoutTracking(String url) {
            int hash = url.indexOf('#');
            int queryStart = url.indexOf('?');
            // A '?' inside the fragment is not a query.
            if (queryStart < 0 || (hash >= 0 && hash < queryStart)) return url;
            int fragmentStart = hash;
            String query = fragmentStart < 0 ? url.substring(queryStart + 1) : url.substring(queryStart + 1, fragmentStart);
            StringBuilder kept = new StringBuilder();
            for (String part : query.split("&")) {
                if (part.isEmpty() || part.equalsIgnoreCase("utm_source=openai")) continue;
                kept.append(kept.length() == 0 ? "?" : "&").append(part);
            }
            return url.substring(0, queryStart) + kept + (fragmentStart < 0 ? "" : url.substring(fragmentStart));
        }

        /** Identity for de-duplication: case-insensitive host, no fragment and no utm_source=openai tag. */
        String key() {
            URI uri = URI.create(url);
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            StringBuilder query = new StringBuilder();
            if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
                if (part.isEmpty() || part.equalsIgnoreCase("utm_source=openai")) continue;
                query.append(query.length() == 0 ? "?" : "&").append(part);
            }
            int port = uri.getPort();
            return uri.getHost().toLowerCase(Locale.ROOT) + (port == -1 || port == 443 ? "" : ":" + port) + path + query;
        }
    }

    private ResearchBrief(String summary, String category, List<ComparableApp> comparables, List<Feature> features,
                          List<String> logic, List<String> ux, List<String> pitfalls, List<String> acceptanceChecks,
                          List<String> assumptions, List<Source> sources, boolean web) {
        this.summary = summary; this.category = category;
        this.comparables = List.copyOf(comparables); this.features = List.copyOf(features);
        this.logic = List.copyOf(logic); this.ux = List.copyOf(ux); this.pitfalls = List.copyOf(pitfalls);
        this.acceptanceChecks = List.copyOf(acceptanceChecks); this.assumptions = List.copyOf(assumptions);
        this.sources = List.copyOf(sources); this.web = web;
    }

    /**
     * Parses the research model's JSON object (optionally inside one ```json fence). Host-only fields
     * (sources, web) are rejected; inline Markdown citation links are reduced to their text.
     */
    public static ResearchBrief parseModelOutput(String text) {
        if (text == null || text.length() > MAX_JSON_CHARS) throw invalid();
        return read(stripFence(text), false);
    }

    /** Parses the stored form written by {@link #toJson()}. */
    public static ResearchBrief parse(String json) {
        if (json == null || json.length() > MAX_STORED_JSON_CHARS) throw invalid();
        return read(json, true);
    }

    /** The same brief with host-collected sources (de-duplicated, at most 12) and the web-search flag. */
    public ResearchBrief withSources(List<Source> sources, boolean web) {
        Map<String, Source> unique = new LinkedHashMap<>();
        if (sources != null) for (Source source : sources) {
            if (source != null && unique.size() < MAX_SOURCES) unique.putIfAbsent(source.key(), source);
        }
        return new ResearchBrief(summary, category, comparables, features, logic, ux, pitfalls, acceptanceChecks,
                assumptions, new ArrayList<>(unique.values()), web);
    }

    /** Stored form: every field, in a fixed order. Round-trips through {@link #parse}. */
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("summary", summary);
        o.addProperty("category", category);
        o.add("comparables", comparablesJson());
        o.add("features", featuresJson());
        o.add("logic", strings(logic));
        o.add("ux", strings(ux));
        o.add("pitfalls", strings(pitfalls));
        o.add("acceptanceChecks", strings(acceptanceChecks));
        o.add("assumptions", strings(assumptions));
        o.add("sources", sourcesJson());
        o.addProperty("web", web);
        return o.toString();
    }

    /** Compact data for the generation prompt: empty lists are omitted; sources are titles and URLs only. */
    public String toPromptJson() {
        JsonObject o = new JsonObject();
        o.addProperty("summary", summary);
        o.addProperty("category", category);
        if (!comparables.isEmpty()) o.add("comparables", comparablesJson());
        o.add("features", featuresJson());
        if (!logic.isEmpty()) o.add("logic", strings(logic));
        if (!ux.isEmpty()) o.add("ux", strings(ux));
        if (!pitfalls.isEmpty()) o.add("pitfalls", strings(pitfalls));
        if (!acceptanceChecks.isEmpty()) o.add("acceptanceChecks", strings(acceptanceChecks));
        if (!assumptions.isEmpty()) o.add("assumptions", strings(assumptions));
        o.addProperty("web", web);
        if (!sources.isEmpty()) o.add("sources", sourcesJson());
        return o.toString();
    }

    /**
     * The brief in the research model's own output schema (every list present, no host-only sources or web flag),
     * so a later change request can ask for the complete updated brief in the same shape. Round-trips through
     * {@link #parseModelOutput}.
     */
    public String toModelJson() {
        JsonObject o = new JsonObject();
        o.addProperty("summary", summary);
        o.addProperty("category", category);
        o.add("comparables", comparablesJson());
        o.add("features", featuresJson());
        o.add("logic", strings(logic));
        o.add("ux", strings(ux));
        o.add("pitfalls", strings(pitfalls));
        o.add("acceptanceChecks", strings(acceptanceChecks));
        o.add("assumptions", strings(assumptions));
        return o.toString();
    }

    private JsonArray comparablesJson() {
        JsonArray array = new JsonArray();
        for (ComparableApp app : comparables) {
            JsonObject item = new JsonObject();
            item.addProperty("name", app.name); item.addProperty("takeaway", app.takeaway);
            array.add(item);
        }
        return array;
    }

    private JsonArray featuresJson() {
        JsonArray array = new JsonArray();
        for (Feature feature : features) {
            JsonObject item = new JsonObject();
            item.addProperty("title", feature.title); item.addProperty("priority", feature.priority);
            item.addProperty("feasibleInPub", feature.feasibleInPub); item.addProperty("notes", feature.notes);
            array.add(item);
        }
        return array;
    }

    private JsonArray sourcesJson() {
        JsonArray array = new JsonArray();
        for (Source source : sources) {
            JsonObject item = new JsonObject();
            item.addProperty("title", source.title); item.addProperty("url", source.url);
            array.add(item);
        }
        return array;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static final Set<String> MODEL_REQUIRED = Set.of("summary", "category", "features");
    private static final Set<String> STORED_FIELDS = Set.of("summary", "category", "comparables", "features", "logic",
            "ux", "pitfalls", "acceptanceChecks", "assumptions", "sources", "web");

    private interface Item<T> { T read(JsonReader reader) throws IOException; }

    private static ResearchBrief read(String json, boolean stored) {
        // Stream the fixed schema so duplicate fields cannot overwrite earlier values.
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            UiPacket.checkJsonDepth(json);
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw invalid();
            reader.beginObject();
            Set<String> fields = new HashSet<>();
            String summary = null, category = null;
            List<ComparableApp> comparables = List.of();
            List<Feature> features = List.of();
            List<String> logic = List.of(), ux = List.of(), pitfalls = List.of(), checks = List.of(), assumptions = List.of();
            List<Source> sources = List.of();
            boolean web = false;
            boolean model = !stored;
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!fields.add(field)) throw invalid();
                switch (field) {
                    case "summary": summary = text(reader, 600, model); break;
                    case "category": category = text(reader, 60, model); break;
                    case "comparables": comparables = array(reader, 8, r -> comparable(r, model)); break;
                    case "features": features = array(reader, 16, r -> feature(r, model)); break;
                    case "logic": logic = array(reader, 12, r -> text(r, 400, model)); break;
                    case "ux": ux = array(reader, 12, r -> text(r, 300, model)); break;
                    case "pitfalls": pitfalls = array(reader, 8, r -> text(r, 300, model)); break;
                    case "acceptanceChecks": checks = array(reader, 10, r -> text(r, 300, model)); break;
                    case "assumptions": assumptions = array(reader, 6, r -> text(r, 300, model)); break;
                    case "sources":
                        if (!stored) throw invalid();
                        sources = array(reader, MAX_SOURCES, ResearchBrief::source); break;
                    case "web":
                        if (!stored || reader.peek() != JsonToken.BOOLEAN) throw invalid();
                        web = reader.nextBoolean(); break;
                    default: throw invalid();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
            if (stored ? !fields.equals(STORED_FIELDS) : !fields.containsAll(MODEL_REQUIRED)) throw invalid();
            if (features.isEmpty()) throw invalid();
            Set<String> keys = new HashSet<>();
            for (Source source : sources) if (!keys.add(source.key())) throw invalid();
            return new ResearchBrief(summary, category, comparables, features, logic, ux, pitfalls, checks, assumptions, sources, web);
        } catch (IOException | RuntimeException error) {
            // Do not include raw output or exception causes in UI/logs.
            throw invalid();
        }
    }

    private static <T> List<T> array(JsonReader reader, int max, Item<T> item) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) throw invalid();
        List<T> values = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            if (values.size() == max) throw invalid();
            values.add(item.read(reader));
        }
        reader.endArray();
        return values;
    }

    private static ComparableApp comparable(JsonReader reader, boolean model) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) throw invalid();
        reader.beginObject();
        String name = null, takeaway = null;
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "name": if (name != null) throw invalid(); name = text(reader, 80, model); break;
                case "takeaway": if (takeaway != null) throw invalid(); takeaway = text(reader, 300, model); break;
                default: throw invalid();
            }
        }
        reader.endObject();
        if (name == null || takeaway == null) throw invalid();
        return new ComparableApp(name, takeaway);
    }

    private static Feature feature(JsonReader reader, boolean model) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) throw invalid();
        reader.beginObject();
        String title = null, priority = null, notes = null;
        Boolean feasible = null;
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "title": if (title != null) throw invalid(); title = text(reader, 80, model); break;
                case "priority":
                    if (priority != null || reader.peek() != JsonToken.STRING) throw invalid();
                    priority = reader.nextString();
                    if (!PRIORITIES.contains(priority)) throw invalid();
                    break;
                case "feasibleInPub":
                    if (feasible != null || reader.peek() != JsonToken.BOOLEAN) throw invalid();
                    feasible = reader.nextBoolean(); break;
                case "notes": if (notes != null) throw invalid(); notes = optionalText(reader, 300, model); break;
                default: throw invalid();
            }
        }
        reader.endObject();
        if (title == null || priority == null || feasible == null) throw invalid();
        if (notes == null) notes = "";
        // An infeasible feature must say why, so the builder can explain the limitation honestly.
        if (!feasible && notes.isEmpty()) throw invalid();
        return new Feature(title, priority, feasible, notes);
    }

    private static Source source(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) throw invalid();
        reader.beginObject();
        String title = null, url = null;
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "title": if (title != null) throw invalid(); title = text(reader, Source.MAX_TITLE, false); break;
                case "url":
                    if (url != null || reader.peek() != JsonToken.STRING) throw invalid();
                    url = reader.nextString();
                    if (!Source.isSafeUrl(url)) throw invalid();
                    break;
                default: throw invalid();
            }
        }
        reader.endObject();
        if (title == null || url == null || title.indexOf('\n') >= 0) throw invalid();
        // Briefs stored before tracking tags were stripped are cleaned when read.
        return new Source(title, Source.withoutTracking(url));
    }

    private static String text(JsonReader reader, int limit, boolean model) throws IOException {
        String value = optionalText(reader, limit, model);
        if (value.isEmpty()) throw invalid();
        return value;
    }

    private static String optionalText(JsonReader reader, int limit, boolean model) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw invalid();
        String raw = reader.nextString();
        if (raw.length() > MAX_JSON_CHARS) throw invalid();
        String value = clean(model ? stripCitations(raw) : raw).strip();
        if (value.length() > limit) throw invalid();
        return value;
    }

    /** Control, line/paragraph-separator and formatting characters (except newlines) become spaces. */
    static String clean(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int type = Character.getType(c);
            boolean unsafe = (Character.isISOControl(c) && c != '\n') || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || type == Character.FORMAT;
            out.append(unsafe ? ' ' : c);
        }
        return out.toString();
    }

    // Web-search answers may add Markdown citations inside strings; sources are recorded separately.
    private static final Pattern PAREN_CITATION = Pattern.compile("[ \\t]*\\(\\[[^\\]\\n]{1,300}\\]\\(https?://[^\\s)]{1,600}\\)\\)");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]\\n]{1,300})\\]\\(https?://[^\\s)]{1,600}\\)");

    static String stripCitations(String value) {
        if (value.indexOf("](http") < 0) return value;
        return MARKDOWN_LINK.matcher(PAREN_CITATION.matcher(value).replaceAll("")).replaceAll("$1");
    }

    private static String stripFence(String text) {
        String value = text.strip();
        if (!value.startsWith("```")) return value;
        int firstLine = value.indexOf('\n');
        if (firstLine < 0 || !value.endsWith("```") || value.length() < firstLine + 4) throw invalid();
        String language = value.substring(3, firstLine).strip();
        if (!language.isEmpty() && !language.equalsIgnoreCase("json")) throw invalid();
        return value.substring(firstLine + 1, value.length() - 3).strip();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Research returned an invalid brief.");
    }
}

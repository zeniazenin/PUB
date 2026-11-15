package app.pub.core;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Selects only IDs actually returned by the connected account's model catalog. */
public final class ModelSelection {
    private static final Pattern ASTRA = Pattern.compile("(?:^|[^a-z0-9])astra(?:[^a-z0-9]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION = Pattern.compile("gpt[- _]?(\\d+(?:\\.\\d+)*)", Pattern.CASE_INSENSITIVE);

    public static final class Model {
        public final String id;
        public final String name;
        public Model(String id, String name) { this.id = id; this.name = name; }
    }

    private ModelSelection() { }

    /** An available explicit choice wins; otherwise prefer newest Astra, then server order. */
    public static String choose(List<Model> models, String explicitChoice) {
        if (models.isEmpty()) return "";
        if (explicitChoice != null) {
            for (Model model : models) if (model.id.equals(explicitChoice)) return model.id;
        }
        Model best = null;
        for (Model model : models) {
            if (!isAstra(model)) continue;
            if (best == null || compareVersions(version(model), version(best)) > 0) best = model;
        }
        return best != null ? best.id : models.get(0).id;
    }

    private static boolean isAstra(Model model) {
        return ASTRA.matcher(model.id).find() || ASTRA.matcher(model.name).find();
    }

    private static List<BigInteger> version(Model model) {
        Matcher match = VERSION.matcher(model.id);
        if (!match.find()) {
            match = VERSION.matcher(model.name);
            if (!match.find()) return Collections.emptyList();
        }
        List<BigInteger> numbers = new ArrayList<>();
        for (String component : match.group(1).split("\\.")) numbers.add(new BigInteger(component));
        return numbers;
    }

    private static int compareVersions(List<BigInteger> a, List<BigInteger> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            BigInteger left = i < a.size() ? a.get(i) : BigInteger.ZERO;
            BigInteger right = i < b.size() ? b.get(i) : BigInteger.ZERO;
            int compared = left.compareTo(right);
            if (compared != 0) return compared;
        }
        return 0;
    }
}

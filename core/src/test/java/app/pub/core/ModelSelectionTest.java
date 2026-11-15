package app.pub.core;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ModelSelectionTest {
    private ModelSelection.Model model(String id) { return new ModelSelection.Model(id, id); }

    @Test public void astraWinsRegardlessOfServerOrdering() {
        assertEquals("gpt-6-astra", ModelSelection.choose(List.of(model("gpt-6.1-sol"), model("gpt-6-luna"), model("gpt-6-astra")), null));
    }

    @Test public void newestAstraUsesNumericVersionNotLexicalOrdering() {
        assertEquals("gpt-6.10-astra", ModelSelection.choose(List.of(model("gpt-6.9-astra"), model("gpt-6.10-astra"), model("gpt-6-astra")), null));
        assertEquals("gpt-10-astra", ModelSelection.choose(List.of(model("gpt-9-astra"), model("gpt-10-astra")), null));
    }

    @Test public void displayNameCanIdentifyAnAccountSpecificSlug() {
        assertEquals("account-astra", ModelSelection.choose(List.of(model("gpt-6.1-sol"), new ModelSelection.Model("account-astra", "GPT-6.2 Astra"), model("gpt-6-astra")), null));
    }

    @Test public void explicitAvailableChoiceSurvivesRefreshAndNewerAstra() {
        assertEquals("gpt-6.1-sol", ModelSelection.choose(List.of(model("gpt-7-astra"), model("gpt-6.1-sol")), "gpt-6.1-sol"));
    }

    @Test public void absentChoiceFallsBackToAvailableAstraWithoutInventingIds() {
        assertEquals("gpt-6-astra", ModelSelection.choose(List.of(model("gpt-6.1-sol"), model("gpt-6-astra")), "unavailable-model"));
    }

    @Test public void missingAstraPreservesCatalogOrderAndEmptyCatalogIsSafe() {
        assertEquals("gpt-6.1-sol", ModelSelection.choose(List.of(model("gpt-6.1-sol"), model("gpt-6-luna")), null));
        assertEquals("first", ModelSelection.choose(List.of(model("first"), model("disastral-model")), null));
        assertEquals("", ModelSelection.choose(List.of(), "old-choice"));
    }

    @Test public void equivalentVersionsPreserveServerOrder() {
        assertEquals("gpt-6-astra", ModelSelection.choose(List.of(model("gpt-6-astra"), model("gpt-6.0-astra")), null));
    }
}

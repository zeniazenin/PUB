package app.pub.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class VisualReviewDecisionTest {
    private static String decision(String status, String summary, String issues) {
        return "{\"status\":\"" + status + "\",\"summary\":\"" + summary + "\",\"issues\":[" + issues + "]}";
    }
    @Test public void parsesAllThreeExplicitOutcomes() {
        VisualReviewDecision pass = VisualReviewDecision.parse(decision("pass", "Required diagram and controls are visible.", ""));
        assertTrue(pass.isPass()); assertFalse(pass.needsFix()); assertFalse(pass.isUnverifiable());
        assertEquals("pass", pass.getStatus()); assertEquals(0, pass.getIssues().size());
        VisualReviewDecision fix = VisualReviewDecision.parse(decision("fix", "The label is clipped.", "\"Make the Start button label fully visible.\""));
        assertTrue(fix.needsFix()); assertEquals(1, fix.issues.size());
        VisualReviewDecision unknown = VisualReviewDecision.parse(decision("unverifiable", "Screenshot is blank.", ""));
        assertTrue(unknown.isUnverifiable()); assertFalse(unknown.isPass());
    }
    @Test public void enforcesStatusIssueConsistency() {
        for (String json : new String[]{decision("pass", "Fine", "\"Clipped label\""), decision("fix", "Broken", ""),
                decision("PASS", "Fine", ""), decision(" pass ", "Fine", ""), decision("approve", "Fine", ""), decision("", "Fine", "")}) {
            assertThrows(IllegalArgumentException.class, () -> VisualReviewDecision.parse(json));
        }
    }
    @Test public void rejectsUnknownMissingAndDuplicateFields() {
        for (String json : new String[]{"{}", "{\"status\":\"pass\",\"summary\":\"Fine\"}",
                "{\"status\":\"pass\",\"summary\":\"Fine\",\"issues\":[],\"extra\":true}",
                "{\"status\":\"fix\",\"status\":\"pass\",\"summary\":\"Fine\",\"issues\":[]}",
                "{\"status\":\"pass\",\"summary\":\"Fine\",\"issues\":[\"Clipped\"],\"issues\":[]}"}) {
            assertThrows(IllegalArgumentException.class, () -> VisualReviewDecision.parse(json));
        }
    }
    @Test public void rejectsMalformedNonJsonAndLenientJson() {
        String valid = decision("pass", "Fine", "");
        for (String json : new String[]{null, "", " ", "[]", "null", "pass", "```json\n" + valid + "\n```", valid + " extra",
                valid + valid, "/* ignored */" + valid, "{status:'pass',summary:'Fine',issues:[]}",
                "{\"status\":\"pass\",\"summary\":\"Fine\",\"issues\":[],}"}) {
            assertThrows(IllegalArgumentException.class, () -> VisualReviewDecision.parse(json));
        }
    }
    @Test public void rejectsWrongTypesAndBlankText() {
        for (String json : new String[]{"{\"status\":true,\"summary\":\"Fine\",\"issues\":[]}",
                "{\"status\":\"pass\",\"summary\":5,\"issues\":[]}",
                "{\"status\":\"pass\",\"summary\":\"Fine\",\"issues\":null}",
                "{\"status\":\"fix\",\"summary\":\"Broken\",\"issues\":[{}]}",
                decision("pass", "  ", ""), decision("fix", "Broken", "\"  \"")}) {
            assertThrows(IllegalArgumentException.class, () -> VisualReviewDecision.parse(json));
        }
    }
    @Test public void enforcesEverySizeBoundary() {
        assertEquals(1000, VisualReviewDecision.parse(decision("pass", "s".repeat(1000), "")).summary.length());
        String issue = "\"" + "x".repeat(600) + "\"";
        assertEquals(5, VisualReviewDecision.parse(decision("fix", "Fix five concrete issues", String.join(",", java.util.Collections.nCopies(5, issue)))).issues.size());
        assertThrows(IllegalArgumentException.class, () -> VisualReviewDecision.parse(decision("pass", "s".repeat(1001), "")));
        assertThrows(IllegalArgumentException.class, () -> VisualReviewDecision.parse(decision("fix", "Broken", "\"" + "x".repeat(601) + "\"")));
        assertThrows(IllegalArgumentException.class, () -> VisualReviewDecision.parse(decision("fix", "Broken", String.join(",", java.util.Collections.nCopies(6, issue)))));
        assertThrows(IllegalArgumentException.class, () -> VisualReviewDecision.parse(" ".repeat(VisualReviewDecision.MAX_JSON_CHARS + 1)));
    }
    @Test public void decisionIssuesCannotBeMutatedAfterValidation() {
        VisualReviewDecision fix = VisualReviewDecision.parse(decision("fix", "Broken", "\"Unclip label\""));
        assertThrows(UnsupportedOperationException.class, () -> fix.issues.clear());
    }
    @Test public void errorsDoNotEchoUntrustedOutput() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> VisualReviewDecision.parse("{private-marker"));
        assertFalse(error.getMessage().contains("private-marker"));
        assertNull(error.getCause());
    }
}

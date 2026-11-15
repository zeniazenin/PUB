package app.pub.core;
import org.junit.Test;
import static org.junit.Assert.*;

public class GenerationOutputTest {
    @Test public void onlyCompletedOutputCanBeUsed() {
        GenerationOutput out=new GenerationOutput();
        out.accept("{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}");
        assertThrows(IllegalStateException.class,out::finish);
        assertTrue(out.accept("{\"type\":\"response.completed\"}"));assertEquals("hello",out.finish());
    }
    @Test public void failureRefusalAndIncompleteDiscardPartialOutput() {
        for(String type:new String[]{"response.failed","response.incomplete","response.refusal.delta","error"}) {
            GenerationOutput out=new GenerationOutput();
            assertThrows(IllegalStateException.class,()->out.accept("{\"type\":\""+type+"\"}"));
        }
    }
    @Test public void boundsOutputAndRejectsEmptyCompletion() {
        GenerationOutput out=new GenerationOutput();
        assertThrows(IllegalStateException.class,()->out.accept("{\"type\":\"response.output_text.delta\",\"delta\":\""+"x".repeat(100001)+"\"}"));
        out.accept("{\"type\":\"response.completed\"}");assertThrows(IllegalStateException.class,out::finish);
    }
}

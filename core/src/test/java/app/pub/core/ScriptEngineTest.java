package app.pub.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScriptEngineTest {
    @Test public void hasNoJavaOrHostBindings() {
        ScriptEngine engine = new ScriptEngine();
        engine.load("function dispatch(s) { return [typeof java,typeof Packages,typeof JavaAdapter,typeof importClass,typeof getClass,typeof android,typeof fetch].join(','); }");
        assertEquals("undefined,undefined,undefined,undefined,undefined,undefined,undefined",engine.dispatch("{}"));
    }
    @Test public void pureJavascriptWorks() {
        ScriptEngine engine = new ScriptEngine();
        engine.load("function dispatch(s) { return JSON.stringify({result:JSON.parse(s).x*2}); }");
        assertEquals("{\"result\":42}",engine.dispatch("{\"x\":21}"));
    }
    @Test(timeout=2000, expected=ScriptEngine.BudgetExceeded.class) public void infiniteLoopIsInterrupted() {
        ScriptEngine engine = new ScriptEngine();
        engine.load("function dispatch(s) { while(true) {} }");
        engine.dispatch("{}");
    }
    @Test(timeout=2000, expected=ScriptEngine.BudgetExceeded.class) public void scriptCannotCatchBudgetError() {
        ScriptEngine engine = new ScriptEngine();
        engine.load("function dispatch(s) { while(true) { try { while(true) {} } catch(e) {} } }");
        engine.dispatch("{}");
    }
    @Test(timeout=2000, expected=ScriptEngine.BudgetExceeded.class) public void startupIsAlsoBounded() { new ScriptEngine().load("while(true){}"); }
    @Test(expected=IllegalArgumentException.class) public void oversizedOutputRejected() {
        ScriptEngine engine = new ScriptEngine(); engine.load("function dispatch(s) { return 'x'.repeat(40000); }"); engine.dispatch("{}");
    }
    @Test(expected=IllegalArgumentException.class) public void missingContractRejected() { new ScriptEngine().load("var x=1;"); }
}

package app.pub.core;

import org.mozilla.javascript.*;

/** Only strings cross this boundary. No Java objects or Java-backed callbacks are exposed. */
public final class ScriptEngine {
    public static final int MAX_SOURCE = 64 * 1024;
    public static final int MAX_MESSAGE = 32 * 1024;
    private final GuardedFactory factory = new GuardedFactory();
    private Scriptable scope;
    private Function dispatch;

    public void load(String source) {
        if (source.length() > MAX_SOURCE) throw new IllegalArgumentException("Script exceeds 64 KiB");
        factory.run(cx -> {
            scope = cx.initSafeStandardObjects(null, true);
            cx.evaluateString(scope, source, "main.js", 1, null);
            Object fn = ScriptableObject.getProperty(scope, "dispatch");
            if (!(fn instanceof Function)) throw new IllegalArgumentException("Project must define dispatch(json)");
            dispatch = (Function) fn;
            return null;
        });
    }

    public String dispatch(String event) {
        if (dispatch == null) throw new IllegalStateException("Runtime is not loaded");
        if (event.length() > MAX_MESSAGE) throw new IllegalArgumentException("Event too large");
        return factory.run(cx -> {
            Object value = dispatch.call(cx, scope, scope, new Object[]{event});
            if (!(value instanceof CharSequence)) throw new IllegalArgumentException("dispatch must return JSON text");
            String result = value.toString();
            if (result.length() > MAX_MESSAGE) throw new IllegalArgumentException("UI packet too large");
            return result;
        });
    }

    public static final class BudgetExceeded extends Error {
        public BudgetExceeded() { super("Script execution budget exceeded"); }
    }

    private static final class GuardedFactory extends ContextFactory {
        private long deadline;
        <T> T run(ContextAction<T> action) {
            deadline = System.nanoTime() + 150_000_000L;
            return call(action);
        }
        @Override protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setOptimizationLevel(-1); // Interpreter only: no generated JVM classes on Android.
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setClassShutter(name -> false);
            cx.setInstructionObserverThreshold(2000);
            cx.setMaximumInterpreterStackDepth(128);
            return cx;
        }
        @Override protected void observeInstructionCount(Context cx, int count) {
            if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) throw new BudgetExceeded();
        }
    }
}

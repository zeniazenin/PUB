package app.pub.runtime;
import app.pub.runtime.IRuntimeCallback;
oneway interface IRuntimeService {
    void load(String source, IRuntimeCallback callback);
    void dispatch(long sequence, String event);
    void shutdown();
}

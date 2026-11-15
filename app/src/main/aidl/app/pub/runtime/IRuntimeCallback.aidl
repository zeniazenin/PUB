package app.pub.runtime;
oneway interface IRuntimeCallback {
    void onReady(int isolatedUid);
    void onResult(long sequence, String packet);
    void onFailure(String message);
}

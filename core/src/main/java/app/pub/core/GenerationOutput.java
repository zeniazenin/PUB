package app.pub.core;

import com.google.gson.*;

/** A truncated/refused/failed stream must never become a previewable project. */
public final class GenerationOutput {
    private final StringBuilder text=new StringBuilder();
    private boolean completed;
    public boolean accept(String payload) {
        if(completed) return true;
        if(payload.length()>1_000_000) throw new IllegalStateException("Generation event exceeded limit");
        UiPacket.checkJsonDepth(payload);
        JsonObject event=JsonParser.parseString(payload).getAsJsonObject();
        String type=event.has("type")?event.get("type").getAsString():"";
        switch(type) {
            case "response.output_text.delta":
                String delta=event.get("delta").getAsString();
                if(text.length()+delta.length()>100_000) throw new IllegalStateException("Generated project is too large");
                text.append(delta);break;
            case "response.completed":completed=true;break;
            case "response.failed":case "response.incomplete":case "error":case "response.refusal.delta":
                throw new IllegalStateException("Generation could not complete. Try a smaller request or another model.");
        }
        return completed;
    }
    public String finish() {
        if(!completed || text.toString().isBlank()) throw new IllegalStateException("Generation interrupted before completion. Please retry.");
        return text.toString();
    }
}

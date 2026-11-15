package app.pub.core;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Accepts only a bounded, completed Responses image result. Never logs or echoes payloads. */
public final class ImageGenerationOutput {
    public static final int MAX_EVENT_BYTES = 8 * 1024 * 1024;
    public static final int MAX_STREAM_BYTES = 24 * 1024 * 1024;
    public static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    public static final int MAX_BASE64_CHARS = 4 * ((MAX_IMAGE_BYTES + 2) / 3);
    private boolean completed;
    private boolean failed;
    private String image;

    public boolean accept(String payload) {
        if (failed) throw failure("Image generation did not complete successfully.");
        if (completed) return true;
        try {
            if (payload == null || payload.length() > MAX_EVENT_BYTES || payload.getBytes(StandardCharsets.UTF_8).length > MAX_EVENT_BYTES)
                throw failure("Image generation event exceeded the size limit.");
            UiPacket.checkJsonDepth(payload);
            JsonObject event = object(JsonParser.parseString(payload));
            String type = string(event, "type");
            switch (type) {
                case "response.failed": case "response.incomplete": case "error":
                    throw failure("Image generation could not complete. The selected model or ChatGPT account may not support the image tool; try another available model or a simpler request.");
                case "response.refusal.delta": case "response.refusal.done":
                    throw failure("The image request was declined. Revise the description and try again.");
                case "response.output_item.done":
                    inspectItem(object(event.get("item"))); break;
                case "response.completed":
                    JsonObject response = object(event.get("response"));
                    if (response.has("status") && !"completed".equals(string(response, "status")))
                        throw failure("Image generation ended without a completed response.");
                    if (present(response, "error") || present(response, "incomplete_details"))
                        throw failure("Image generation ended with an error or incomplete output.");
                    if (response.has("output")) {
                        JsonArray items = response.getAsJsonArray("output");
                        if (items == null || items.size() > 128) throw failure("Invalid image generation output.");
                        for (JsonElement item : items) inspectItem(object(item));
                    }
                    completed = true; break;
                default:
                    // Progress, text and partial-image events never become a saved asset.
                    break;
            }
            return completed;
        } catch (IllegalStateException e) {
            if (failed) throw e;
            throw failure("Malformed image generation response. Please retry.");
        } catch (RuntimeException e) {
            throw failure("Malformed image generation response. Please retry.");
        }
    }

    private void inspectItem(JsonObject item) {
        String type = string(item, "type");
        if ("refusal".equals(type)) throw failure("The image request was declined. Revise the description and try again.");
        if ("message".equals(type) && item.has("content")) {
            JsonArray content = item.getAsJsonArray("content");
            if (content == null || content.size() > 128) throw failure("Invalid image generation message.");
            for (JsonElement part : content) {
                JsonObject value = object(part);
                if ("refusal".equals(string(value, "type")) || present(value, "refusal"))
                    throw failure("The image request was declined. Revise the description and try again.");
            }
        }
        if (!"image_generation_call".equals(type)) return;
        if (item.has("status") && !"completed".equals(string(item, "status")))
            throw failure("The image tool did not finish its result.");
        String candidate = string(item, "result");
        if (candidate.isEmpty() || candidate.length() > MAX_BASE64_CHARS)
            throw failure("Generated image is empty or exceeds the 4 MB limit.");
        if (candidate.length() % 4 != 0) throw failure("The image tool returned invalid image data.");
        int padding = candidate.endsWith("==") ? 2 : candidate.endsWith("=") ? 1 : 0;
        long decodedSize = (long) candidate.length() / 4 * 3 - padding;
        if (decodedSize <= 0 || decodedSize > MAX_IMAGE_BYTES) throw failure("Generated image exceeds the 4 MB limit.");
        for (int i = 0; i < candidate.length() - padding; i++) {
            char c = candidate.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/'))
                throw failure("The image tool returned invalid image data.");
        }
        try {
            if (Base64.getDecoder().decode(candidate).length != decodedSize) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw failure("The image tool returned invalid image data."); }
        if (image != null && !image.equals(candidate)) throw failure("The image tool returned multiple images; request one asset at a time.");
        image = candidate;
    }

    public String finish() {
        if (failed || !completed) throw failure("Image generation was interrupted before completion. Please retry.");
        if (image == null) throw failure("No generated image was returned. The selected model or ChatGPT account may not support image generation.");
        return image;
    }

    private IllegalStateException failure(String message) { failed = true; image = null; return new IllegalStateException(message); }
    private static boolean present(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull(); }
    private static JsonObject object(JsonElement value) {
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException();
        return value.getAsJsonObject();
    }
    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
        return value.getAsString();
    }

    /** Reads SSE frames, including multiline data and CRLF, counting actual wire bytes. Caller owns input. */
    public static String readSse(InputStream input, Runnable checkActive) throws IOException {
        ImageGenerationOutput output = new ImageGenerationOutput();
        SseFrames frames = new SseFrames(output);
        BufferedInputStream buffered = new BufferedInputStream(input, 8192);
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        long total = 0;
        checkActive.run();
        int value;
        while ((value = buffered.read()) != -1) {
            if (++total > MAX_STREAM_BYTES) throw output.failure("Image generation stream exceeded the size limit.");
            if ((total & 8191) == 0) checkActive.run();
            if (value == '\n') {
                if (frames.line(line.toByteArray())) { checkActive.run(); return output.finish(); }
                line.reset();
            } else {
                if (line.size() >= MAX_EVENT_BYTES + 8) throw output.failure("Image generation event exceeded the size limit.");
                line.write(value);
            }
        }
        checkActive.run();
        if (line.size() > 0) frames.line(line.toByteArray());
        frames.dispatch();
        return output.finish();
    }

    private static final class SseFrames {
        private final ImageGenerationOutput output;
        private final StringBuilder data = new StringBuilder();
        private long bytes;
        private SseFrames(ImageGenerationOutput output) { this.output = output; }
        private boolean line(byte[] wire) {
            int length = wire.length;
            if (length > 0 && wire[length - 1] == '\r') length--;
            if (length == 0) return dispatch();
            String text;
            try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(wire, 0, length)).toString(); }
            catch (CharacterCodingException e) { throw output.failure("Malformed image generation stream."); }
            if (!text.startsWith("data:")) return false;
            String part = text.substring(5);
            if (part.startsWith(" ")) part = part.substring(1);
            bytes += part.getBytes(StandardCharsets.UTF_8).length + (data.length() == 0 ? 0 : 1);
            if (bytes > MAX_EVENT_BYTES) throw output.failure("Image generation event exceeded the size limit.");
            if (data.length() > 0) data.append('\n');
            data.append(part);
            return false;
        }
        private boolean dispatch() {
            if (data.length() == 0) { bytes = 0; return false; }
            String payload = data.toString(); data.setLength(0); bytes = 0;
            if ("[DONE]".equals(payload)) return true;
            return output.accept(payload);
        }
    }
}

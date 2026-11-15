package app.pub.core;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ImageGenerationOutputTest {
    private static final String IMAGE = "AQID"; // Synthetic bytes only; PNG validation belongs to asset storage.
    private static String item(String data) {
        return "{\"type\":\"image_generation_call\",\"id\":\"ig_test\",\"status\":\"completed\",\"result\":\"" + data + "\"}";
    }
    private static String completed(String items) {
        return "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[" + items + "]}}";
    }
    private static String done(String data) { return "{\"type\":\"response.output_item.done\",\"item\":" + item(data) + "}"; }
    private static String frame(String payload) { return "data: " + payload + "\n\n"; }
    private static String stream(String value) throws Exception {
        return ImageGenerationOutput.readSse(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)), () -> {});
    }

    @Test public void acceptsFinalImageOnlyAfterCompletion() {
        ImageGenerationOutput output = new ImageGenerationOutput();
        assertTrue(output.accept(completed(item(IMAGE))));
        assertEquals(IMAGE, output.finish());
    }
    @Test public void acceptsOutputItemDoneAndRepeatedFinalImage() {
        ImageGenerationOutput output = new ImageGenerationOutput();
        assertFalse(output.accept(done(IMAGE)));
        assertTrue(output.accept(completed(item(IMAGE))));
        assertEquals(IMAGE, output.finish());
    }
    @Test public void acceptsDoneImageWhenFinalOutputDoesNotRepeatIt() throws Exception {
        assertEquals(IMAGE, stream(frame(done(IMAGE)) + frame(completed(""))));
    }
    @Test public void truncatedStreamCannotReturnAnAlreadyGeneratedImage() {
        ImageGenerationOutput output = new ImageGenerationOutput();
        output.accept(done(IMAGE));
        assertThrows(IllegalStateException.class, output::finish);
        assertThrows(IllegalStateException.class, () -> stream(frame(done(IMAGE)) + frame("[DONE]")));
    }
    @Test public void refusesFailureIncompleteAndRefusalEventsAfterPartialOutput() {
        for (String type : new String[]{"error", "response.failed", "response.incomplete", "response.refusal.delta", "response.refusal.done"}) {
            ImageGenerationOutput output = new ImageGenerationOutput();
            output.accept(done(IMAGE));
            assertThrows(IllegalStateException.class, () -> output.accept("{\"type\":\"" + type + "\"}"));
            assertThrows(IllegalStateException.class, output::finish);
            assertThrows(IllegalStateException.class, () -> output.accept(completed("")));
        }
    }
    @Test public void refusesRefusalInsideCompletedMessageEvenWithImage() {
        ImageGenerationOutput output = new ImageGenerationOutput();
        String refusal = "{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"private-marker\"}]}";
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> output.accept(completed(item(IMAGE) + "," + refusal)));
        assertFalse(error.getMessage().contains("private-marker"));
        assertThrows(IllegalStateException.class, output::finish);
    }
    @Test public void rejectsTextOnlyAndPartialImageOnlyResponses() {
        ImageGenerationOutput output = new ImageGenerationOutput();
        output.accept("{\"type\":\"response.image_generation_call.partial_image\",\"partial_image_b64\":\"AQID\"}");
        output.accept(completed("{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ASCII substitute\"}]}"));
        assertThrows(IllegalStateException.class, output::finish);
    }
    @Test public void rejectsMalformedJsonSchemaAndBase64WithoutEchoingPayload() {
        for (String payload : new String[]{"{private-marker", "[]", "{}", "{\"type\":7}",
                "{\"type\":\"response.completed\",\"response\":null}",
                "{\"type\":\"response.output_item.done\",\"item\":null}",
                completed(item("AQ?D")), completed(item("A===")), completed(item("AQI")), completed(item(""))}) {
            ImageGenerationOutput output = new ImageGenerationOutput();
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> output.accept(payload));
            assertFalse(error.getMessage().contains("private-marker"));
            assertThrows(IllegalStateException.class, output::finish);
        }
    }
    @Test public void rejectsCompletedEnvelopeWithFailedStatusOrIncompleteDetails() {
        for (String extra : new String[]{"\"status\":\"failed\",", "\"incomplete_details\":{},", "\"error\":{},"}) {
            ImageGenerationOutput output = new ImageGenerationOutput();
            assertThrows(IllegalStateException.class, () -> output.accept("{\"type\":\"response.completed\",\"response\":{" + extra + "\"output\":[" + item(IMAGE) + "]}}"));
        }
        assertThrows(IllegalStateException.class, () -> new ImageGenerationOutput().accept(completed(item(IMAGE).replace("completed", "in_progress"))));
    }
    @Test public void rejectsMultipleDistinctImages() {
        ImageGenerationOutput output = new ImageGenerationOutput();
        output.accept(done(IMAGE));
        assertThrows(IllegalStateException.class, () -> output.accept(completed(item("BAUG"))));
    }
    @Test public void enforcesEncodedDecodedAndEventLimits() {
        assertThrows(IllegalStateException.class, () -> new ImageGenerationOutput().accept(" ".repeat(ImageGenerationOutput.MAX_EVENT_BYTES + 1)));
        String encodedTooLarge = "A".repeat(ImageGenerationOutput.MAX_BASE64_CHARS + 4);
        assertThrows(IllegalStateException.class, () -> new ImageGenerationOutput().accept(completed(item(encodedTooLarge))));
        // The maximum encoded length can decode to two bytes beyond the decoded byte budget.
        String decodedTooLarge = "A".repeat(ImageGenerationOutput.MAX_BASE64_CHARS);
        assertThrows(IllegalStateException.class, () -> new ImageGenerationOutput().accept(completed(item(decodedTooLarge))));
    }
    @Test public void acceptsExactDecodedImageBudget() {
        String encoded = java.util.Base64.getEncoder().encodeToString(new byte[ImageGenerationOutput.MAX_IMAGE_BYTES]);
        ImageGenerationOutput output = new ImageGenerationOutput();
        assertTrue(output.accept(completed(item(encoded))));
        assertEquals(encoded, output.finish());
    }
    @Test public void readsSseCommentsCrlfMultilineDataAndFinalUnterminatedFrame() throws Exception {
        String value = ": heartbeat\r\nevent: response.output_item.done\r\n" + frame(done(IMAGE)).replace("\n", "\r\n") +
                "data: {\"type\":\"response.completed\",\n" +
                "data: \"response\":{\"status\":\"completed\",\"output\":[]}}";
        assertEquals(IMAGE, stream(value));
    }
    @Test public void rejectsMalformedUtf8AndOversizedSseLine() {
        assertThrows(IllegalStateException.class, () -> ImageGenerationOutput.readSse(new ByteArrayInputStream(new byte[]{(byte)0xc3, 0x28, 10}), () -> {}));
        assertThrows(IllegalStateException.class, () -> ImageGenerationOutput.readSse(new RepeatingInput("x", ImageGenerationOutput.MAX_EVENT_BYTES + 9L), () -> {}));
    }
    @Test public void boundsEntireWireStreamIncludingIgnoredEvents() {
        assertThrows(IllegalStateException.class, () -> ImageGenerationOutput.readSse(new RepeatingInput(":" + "x".repeat(4094) + "\n", ImageGenerationOutput.MAX_STREAM_BYTES + 1L), () -> {}));
    }
    @Test public void boundsAnEventSplitAcrossManySmallDataLines() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> ImageGenerationOutput.readSse(
                new RepeatingInput("data: " + " ".repeat(4089) + "\n", ImageGenerationOutput.MAX_EVENT_BYTES + 100_000L), () -> {}));
        assertTrue(error.getMessage().contains("event exceeded"));
    }
    @Test public void checksCancellationDuringLargeEvents() {
        AtomicInteger checks = new AtomicInteger();
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> ImageGenerationOutput.readSse(new RepeatingInput("x", 100_000), () -> {
            if (checks.incrementAndGet() == 3) throw new IllegalStateException("cancelled-test");
        }));
        assertEquals("cancelled-test", error.getMessage());
        assertEquals(3, checks.get());
    }

    private static final class RepeatingInput extends InputStream {
        private final byte[] pattern;
        private final long length;
        private long position;
        private RepeatingInput(String pattern, long length) { this.pattern = pattern.getBytes(StandardCharsets.UTF_8); this.length = length; }
        @Override public int read() { return position >= length ? -1 : pattern[(int)(position++ % pattern.length)] & 255; }
        @Override public int read(byte[] target, int offset, int count) {
            if (position >= length) return -1;
            int size = (int)Math.min(count, length - position);
            for (int i = 0; i < size; i++) target[offset + i] = pattern[(int)(position++ % pattern.length)];
            return size;
        }
    }
}

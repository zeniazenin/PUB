package app.pub.core;

import com.google.gson.JsonPrimitive;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

/** UTF-8 read chunking: whole characters only, strict decoding and the JSON-escaped size budget of a reply. */
public class FilesTextChunkTest {
    private FilesContract.TextChunk chunk(byte[] bytes, int count, boolean end) { return FilesContract.utf8Prefix(bytes, count, end, Integer.MAX_VALUE); }

    @Test public void chunkBoundariesNeverSplitACharacter() {
        String text = "aé€🎸z";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int[] boundaries = {0, 1, 3, 6, 10, 11};
        for (int cut = 0; cut <= bytes.length; cut++) {
            FilesContract.TextChunk chunk = chunk(bytes, cut, false);
            int expected = 0;
            for (int boundary : boundaries) if (boundary <= cut) expected = boundary;
            assertEquals("cut " + cut, expected, chunk.bytes);
            assertFalse("cut " + cut, chunk.invalid);
            assertArrayEquals(Arrays.copyOf(bytes, expected), chunk.text.getBytes(StandardCharsets.UTF_8));
            // Reading on from the reported byte count reassembles the text exactly.
            FilesContract.TextChunk rest = chunk(Arrays.copyOfRange(bytes, chunk.bytes, bytes.length), bytes.length - chunk.bytes, true);
            assertEquals(text, chunk.text + rest.text);
        }
    }

    @Test public void incompleteCharacterAtTheEndOfTheFileIsInvalid() {
        byte[] bytes = "ok€".getBytes(StandardCharsets.UTF_8);
        FilesContract.TextChunk cut = chunk(bytes, bytes.length - 1, true);
        assertEquals(2, cut.bytes); assertEquals("ok", cut.text); assertTrue(cut.invalid);
        FilesContract.TextChunk more = chunk(bytes, bytes.length - 1, false);
        assertEquals(2, more.bytes); assertFalse(more.invalid);
    }

    @Test public void strictDecodingRejectsMalformedSequences() {
        byte[][] malformed = {
            {(byte) 0x80}, {(byte) 0xBF}, {(byte) 0xC0, (byte) 0xAF}, {(byte) 0xC1, (byte) 0xBF}, {(byte) 0xE0, (byte) 0x80, (byte) 0xAF},
            {(byte) 0xED, (byte) 0xA0, (byte) 0x80}, {(byte) 0xED, (byte) 0xBF, (byte) 0xBF}, {(byte) 0xF0, (byte) 0x80, (byte) 0x80, (byte) 0xAF},
            {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, {(byte) 0xF5, (byte) 0x80, (byte) 0x80, (byte) 0x80}, {(byte) 0xFF},
            {(byte) 0xC3, 'a'}, {(byte) 0xE2, (byte) 0x82, 'a'}, {(byte) 0xE2, 'a'}
        };
        for (byte[] bad : malformed) {
            byte[] bytes = new byte[bad.length + 2];
            bytes[0] = 'o'; bytes[1] = 'k';
            System.arraycopy(bad, 0, bytes, 2, bad.length);
            FilesContract.TextChunk chunk = chunk(bytes, bytes.length, false);
            assertEquals(Arrays.toString(bad), 2, chunk.bytes);
            assertTrue(Arrays.toString(bad), chunk.invalid);
        }
        // The largest valid scalar values decode.
        byte[] edges = "퟿�􏿿".getBytes(StandardCharsets.UTF_8);
        FilesContract.TextChunk chunk = chunk(edges, edges.length, true);
        assertEquals(edges.length, chunk.bytes); assertEquals("퟿�􏿿", chunk.text); assertFalse(chunk.invalid);
    }

    @Test public void escapedSizeBudgetStopsBeforeTheLimit() {
        byte[] quotes = "\"\"\"\"".getBytes(StandardCharsets.UTF_8);
        FilesContract.TextChunk three = FilesContract.utf8Prefix(quotes, quotes.length, true, 7);
        assertEquals(3, three.bytes); assertFalse(three.invalid);
        byte[] controls = {1, 2, 3};
        assertEquals(2, FilesContract.utf8Prefix(controls, 3, true, 12).bytes);
        byte[] emoji = "🎸🎸".getBytes(StandardCharsets.UTF_8);
        assertEquals(4, FilesContract.utf8Prefix(emoji, emoji.length, true, 3).bytes);
    }

    @Test public void escapedLengthIsNeverBelowGsonOutput() {
        for (int point = 0; point < 0x3000; point++) {
            if (point >= 0xD800 && point <= 0xDFFF) continue;
            String text = new String(Character.toChars(point));
            int written = new JsonPrimitive(text).toString().length() - 2;
            assertTrue("U+" + Integer.toHexString(point), FilesContract.escapedLength(point) >= written);
        }
        assertEquals(2, new JsonPrimitive("🎸").toString().length() - 2);
        assertEquals(2, FilesContract.escapedLength(0x1F3B8));
    }
}

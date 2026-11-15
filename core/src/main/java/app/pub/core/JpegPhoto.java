package app.pub.core;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Streams a JPEG without decoding or recompressing pixels. Retains only display metadata. */
public final class JpegPhoto {
    private JpegPhoto() {}
    public static void copyWithoutPersonalMetadata(InputStream source, OutputStream destination, int orientation) throws IOException {
        Reader in = new Reader(source);
        BufferedOutputStream out = new BufferedOutputStream(destination, 65536);
        if (in.readUnsignedShort() != 0xffd8) throw new IOException("Not a JPEG");
        marker(out, 0xd8);
        // Minimal EXIF: a single orientation tag. No thumbnail, GPS, dates, device IDs or comments.
        byte[] exif = new byte[] {69,120,105,102,0,0,77,77,0,42,0,0,0,8,0,1,1,18,0,3,0,0,0,1,0,1,0,0,0,0,0,0};
        exif[25] = (byte) (orientation >= 1 && orientation <= 8 ? orientation : 1);
        segment(out, 0xe1, exif);
        boolean scan = false, sawScan = false;
        while (true) {
            // Entropy-coded data is nearly the whole file: copy it in bulk up to the next 0xFF.
            if (scan) in.copyUntilMarker(out);
            int prefix = in.readUnsignedByte();
            if (prefix != 0xff) throw new IOException("Invalid JPEG marker");
            int code;
            do { code = in.readUnsignedByte(); } while (code == 0xff);
            if (scan && (code == 0 || (code >= 0xd0 && code <= 0xd7))) {
                marker(out, code); continue; // Entropy stuffing and restart markers are image data.
            }
            scan = false;
            if (code == 0xd9) {
                if (!sawScan) throw new IOException("JPEG has no image scan");
                marker(out, code); out.flush(); return; // Discard trailing metadata/payloads.
            }
            if (code == 0 || code == 0xd8 || (code >= 0xd0 && code <= 0xd7)) throw new IOException("Invalid JPEG structure");
            if (code == 1) { marker(out, code); continue; }
            int length = in.readUnsignedShort();
            if (length < 2) throw new IOException("Invalid JPEG segment length");
            byte[] data = new byte[length - 2];
            in.readFully(data);
            boolean app = code >= 0xe0 && code <= 0xef;
            // JFIF thumbnails, XMP, EXIF, IPTC, comments, gain maps and unknown APP blocks are removed.
            // ICC profiles and the fixed Adobe color-transform marker preserve color interpretation.
            boolean displayMetadata = (code == 0xe2 && startsWith(data, "ICC_PROFILE\0")) ||
                (code == 0xee && data.length == 12 && startsWith(data, "Adobe"));
            if ((!app && code != 0xfe) || displayMetadata) segment(out, code, data);
            if (code == 0xda) { scan = true; sawScan = true; }
        }
    }
    private static boolean startsWith(byte[] data, String prefix) {
        byte[] bytes = prefix.getBytes(StandardCharsets.US_ASCII);
        if (data.length < bytes.length) return false;
        for (int i=0; i<bytes.length; i++) if (data[i] != bytes[i]) return false;
        return true;
    }
    private static void marker(OutputStream out, int code) throws IOException { out.write(0xff); out.write(code); }
    private static void segment(OutputStream out, int code, byte[] data) throws IOException {
        marker(out, code); int length=data.length+2; out.write(length >> 8); out.write(length); out.write(data);
    }

    /** Unsynchronized buffered reader; a truncated JPEG ends with EOFException like DataInputStream. */
    private static final class Reader {
        private final InputStream in;
        private final byte[] buffer = new byte[65536];
        private int position, limit;
        Reader(InputStream in) { this.in = in; }
        private boolean fill() throws IOException {
            int count;
            do { count = in.read(buffer, 0, buffer.length); } while (count == 0);
            if (count < 0) return false;
            position = 0; limit = count;
            return true;
        }
        int readUnsignedByte() throws IOException {
            if (position == limit && !fill()) throw new EOFException();
            return buffer[position++] & 0xff;
        }
        int readUnsignedShort() throws IOException { return (readUnsignedByte() << 8) | readUnsignedByte(); }
        void readFully(byte[] target) throws IOException {
            int copied = 0;
            while (copied < target.length) {
                if (position == limit && !fill()) throw new EOFException();
                int count = Math.min(limit - position, target.length - copied);
                System.arraycopy(buffer, position, target, copied, count);
                position += count; copied += count;
            }
        }
        /** Writes every byte before the next 0xFF, leaving that 0xFF unread. */
        void copyUntilMarker(OutputStream out) throws IOException {
            while (true) {
                if (position == limit && !fill()) throw new EOFException();
                int start = position;
                while (position < limit && buffer[position] != (byte) 0xff) position++;
                if (position > start) out.write(buffer, start, position - start);
                if (position < limit) return;
            }
        }
    }
}

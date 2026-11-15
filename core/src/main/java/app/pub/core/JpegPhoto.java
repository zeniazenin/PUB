package app.pub.core;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Streams a JPEG without decoding or recompressing pixels. Retains only display metadata. */
public final class JpegPhoto {
    private JpegPhoto() {}
    public static void copyWithoutPersonalMetadata(InputStream source, OutputStream destination, int orientation) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(source, 65536));
        BufferedOutputStream out = new BufferedOutputStream(destination, 65536);
        if (in.readUnsignedShort() != 0xffd8) throw new IOException("Not a JPEG");
        marker(out, 0xd8);
        // Minimal EXIF: a single orientation tag. No thumbnail, GPS, dates, device IDs or comments.
        byte[] exif = new byte[] {69,120,105,102,0,0,77,77,0,42,0,0,0,8,0,1,1,18,0,3,0,0,0,1,0,1,0,0,0,0,0,0};
        exif[25] = (byte) (orientation >= 1 && orientation <= 8 ? orientation : 1);
        segment(out, 0xe1, exif);
        boolean scan = false, sawScan = false;
        while (true) {
            int prefix = in.readUnsignedByte();
            if (scan && prefix != 0xff) { out.write(prefix); continue; }
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
}

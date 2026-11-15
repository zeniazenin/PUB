package app.pub.core;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class JpegPhotoTest {
    private byte[] clean(byte[] raw, int orientation) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        JpegPhoto.copyWithoutPersonalMetadata(new ByteArrayInputStream(raw), result, orientation);
        return result.toByteArray();
    }
    private void segment(ByteArrayOutputStream out, int marker, byte[] data) throws IOException {
        out.write(255); out.write(marker); out.write((data.length+2)>>8); out.write(data.length+2); out.write(data);
    }
    private byte[] jpeg(boolean privateData) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); out.write(255);out.write(216);
        if(privateData) {
            segment(out,225,"Exif-private-location".getBytes(StandardCharsets.US_ASCII));
            segment(out,237,"IPTC-private-comment".getBytes(StandardCharsets.US_ASCII));
        }
        segment(out,219,new byte[]{0,4,5,6}); // quantization bytes must remain unchanged
        segment(out,218,new byte[]{1,1,0,0,63,0});
        out.write(new byte[]{1,2,(byte)255,0,3,(byte)255,(byte)208,4});
        // Metadata between progressive scans must also be stripped.
        if(privateData) segment(out,254,"private-after-scan".getBytes(StandardCharsets.US_ASCII));
        segment(out,218,new byte[]{1,1,0,1,63,0}); out.write(new byte[]{7,8,9});
        out.write(255);out.write(217);
        if(privateData) out.write("private-trailing-payload".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }
    @Test public void removesMetadataAroundScansWithoutChangingCompressedImageBytes() throws Exception {
        assertArrayEquals(clean(jpeg(false),6),clean(jpeg(true),6));
        byte[] result=clean(jpeg(true),6);
        assertFalse(new String(result,StandardCharsets.ISO_8859_1).contains("private"));
        assertEquals(6,result[31]); // Minimal EXIF orientation entry, TIFF big endian.
        assertArrayEquals(result,clean(result,6));
    }
    @Test public void preservesColorProfilesButRemovesUnknownApplicationData() throws Exception {
        ByteArrayOutputStream raw=new ByteArrayOutputStream(); raw.write(255);raw.write(216);
        segment(raw,226,"ICC_PROFILE\0color-profile".getBytes(StandardCharsets.US_ASCII));
        segment(raw,226,"unknown-private".getBytes(StandardCharsets.US_ASCII));
        segment(raw,238,new byte[]{65,100,111,98,101,0,100,0,0,0,0,1});
        segment(raw,218,new byte[]{1,1,0,0,63,0});raw.write(1);raw.write(255);raw.write(217);
        String result=new String(clean(raw.toByteArray(),1),StandardCharsets.ISO_8859_1);
        assertTrue(result.contains("ICC_PROFILE"));assertTrue(result.contains("Adobe"));assertFalse(result.contains("unknown-private"));
    }
    @Test public void rejectsTruncatedAndMalformedJpegs() throws Exception {
        for(byte[] raw:new byte[][] {new byte[]{},new byte[]{0,0},new byte[]{(byte)255,(byte)216,(byte)255,(byte)225,0,1},
                new byte[]{(byte)255,(byte)216,(byte)255,(byte)217},java.util.Arrays.copyOf(jpeg(false),jpeg(false).length-1)}) {
            assertThrows(IOException.class,()->clean(raw,1));
        }
    }
}

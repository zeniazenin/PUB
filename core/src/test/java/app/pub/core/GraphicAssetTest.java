package app.pub.core;

import com.google.gson.*;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Base64;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import static org.junit.Assert.*;

public class GraphicAssetTest {
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static JsonObject vector(String shapes) { return json("{\"type\":\"vector\",\"width\":400,\"height\":240,\"shapes\":["+shapes+"]}"); }
    private static final String LINE = "{\"id\":\"string-1\",\"type\":\"line\",\"x1\":20,\"y1\":30,\"x2\":380,\"y2\":30,\"stroke\":\"outline\"}";
    private static JsonObject image(String data) {
        JsonObject asset = json("{\"type\":\"image\",\"prompt\":\"An acoustic guitar\"}");
        if (data != null) asset.addProperty("data", data);
        return asset;
    }
    private static void rejects(Runnable operation) { assertThrows(IllegalArgumentException.class, operation::run); }

    @Test public void acceptsNativeDiagramAndStructuredPaths() {
        GraphicAsset.validate(vector(LINE + ",{\"id\":\"body\",\"type\":\"path\",\"commands\":[[\"M\",1,2],[\"C\",3,4,5,6,7,8],[\"Q\",9,10,11,12],[\"L\",13,14],[\"Z\"]],\"fill\":\"#80442211\",\"stroke\":\"primary\",\"strokeWidth\":2}"));
        GraphicAsset.validate(vector("{\"id\":\"label\",\"type\":\"text\",\"x\":20,\"y\":20,\"text\":\"E₂\",\"size\":18,\"fill\":\"onSurface\"}"));
        assertTrue(GraphicAsset.validId("guitar_front-1"));
        assertFalse(GraphicAsset.validId("../guitar"));
        assertFalse(GraphicAsset.validId("https://example.com"));
    }
    @Test public void rejectsExecutableExternalAndUnknownFields() {
        for (String field : new String[]{"url", "svg", "script", "onClick"}) {
            JsonObject asset = vector(LINE); asset.addProperty(field,"<svg onload='evil()'/>");
            rejects(() -> GraphicAsset.validate(asset));
        }
        JsonObject asset = vector(LINE);
        asset.getAsJsonArray("shapes").get(0).getAsJsonObject().addProperty("href", "https://example.com");
        rejects(() -> GraphicAsset.validate(asset));
    }
    @Test public void rejectsDuplicateIdsBadColorsAndMissingCoordinates() {
        rejects(() -> GraphicAsset.validate(vector(LINE + "," + LINE)));
        rejects(() -> GraphicAsset.validate(vector(LINE.replace("outline", "url(https://example.com)"))));
        rejects(() -> GraphicAsset.validate(vector(LINE.replace("\"x1\":20,", ""))));
        rejects(() -> GraphicAsset.validate(vector(LINE.replace("\"x1\":20", "\"x1\":\"20\""))));
        rejects(() -> GraphicAsset.validate(vector(LINE.replace("\"x1\":20", "\"x1\":1e400"))));
    }
    @Test public void rejectsDimensionAndComplexityAbuse() {
        JsonObject zero = vector(LINE); zero.addProperty("width",0); rejects(() -> GraphicAsset.validate(zero));
        JsonObject huge = vector(LINE); huge.addProperty("height",1025); rejects(() -> GraphicAsset.validate(huge));
        JsonObject shapes = vector(LINE);
        for (int i=1;i<129;i++) { JsonObject line=json(LINE); line.addProperty("id","s"+i); shapes.getAsJsonArray("shapes").add(line); }
        rejects(() -> GraphicAsset.validate(shapes));
    }
    @Test public void rejectsMalformedPathCommands() {
        for (String commands : new String[]{"[[\"L\",0,0]]", "[[\"M\",0]]", "[[\"M\",0,0],[\"A\",0,0]]", "[[\"M\",0,0],[\"Z\",1]]", "[[\"M\",0,0],[\"L\",2049,0]]"}) {
            JsonObject asset=vector("{\"id\":\"p\",\"type\":\"path\",\"commands\":"+commands+"}");
            rejects(() -> GraphicAsset.validate(asset));
        }
    }
    @Test public void acceptsPendingAndCompleteImageButRejectsNonPng() throws Exception {
        GraphicAsset.validate(image(null));
        byte[] png=png(2,2);
        GraphicAsset.validate(image(Base64.getEncoder().encodeToString(png)));
        assertArrayEquals(png,GraphicAsset.decodePng(Base64.getEncoder().encodeToString(png)));
        rejects(() -> GraphicAsset.validate(image("data:image/png;base64,aaaa")));
        rejects(() -> GraphicAsset.validate(image(Base64.getEncoder().encodeToString("<svg/>".getBytes()))));
        JsonObject unknown=image(null); unknown.addProperty("url","https://example.com/x.png"); rejects(() -> GraphicAsset.validate(unknown));
    }
    @Test public void rejectsHugeDimensionsCorruptionTruncationAndTrailingPayload() throws Exception {
        for (int[] dimensions : new int[][]{{0,1},{2049,1},{2048,2048}}) {
            String data=Base64.getEncoder().encodeToString(png(dimensions[0],dimensions[1]));
            rejects(() -> GraphicAsset.validate(image(data)));
        }
        byte[] corrupt=png(1,1); corrupt[20]^=1;
        rejects(() -> GraphicAsset.validate(image(Base64.getEncoder().encodeToString(corrupt))));
        byte[] original=png(1,1);
        rejects(() -> GraphicAsset.validate(image(Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(original,original.length-1)))));
        rejects(() -> GraphicAsset.validate(image(Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(original,original.length+1)))));
        rejects(() -> GraphicAsset.decodePng("A".repeat(((GraphicAsset.MAX_PNG_BYTES+2)/3)*4+1)));
    }
    @Test public void uiGraphicAllowsHighlightButRejectsUrlOrUnknownFields() {
        UiPacket.parse("{\"ui\":{\"type\":\"graphic\",\"asset\":\"guitar\",\"description\":\"Guitar strings\",\"highlight\":[\"string-1\"]}}");
        rejects(() -> UiPacket.parse("{\"ui\":{\"type\":\"graphic\",\"asset\":\"https://x\",\"description\":\"x\"}}"));
        rejects(() -> UiPacket.parse("{\"ui\":{\"type\":\"graphic\",\"asset\":\"guitar\",\"description\":\"x\",\"highlight\":[\"s\",\"s\"]}}"));
        rejects(() -> UiPacket.parse("{\"ui\":{\"type\":\"graphic\",\"asset\":\"guitar\",\"description\":\"x\",\"url\":\"x\"}}"));
    }
    @Test public void sharedFourImageBudgetIncludesPhotosAndGraphics() {
        String graphic="{\"type\":\"graphic\",\"asset\":\"guitar\",\"description\":\"x\"}";
        String photo="{\"type\":\"image\",\"asset\":\"12345678-1234-1234-1234-123456789abc.jpg\",\"description\":\"x\"}";
        UiPacket.parse("{\"ui\":{\"type\":\"column\",\"children\":["+graphic+","+graphic+","+photo+","+photo+"]}}");
        rejects(() -> UiPacket.parse("{\"ui\":{\"type\":\"column\",\"children\":["+graphic+","+graphic+","+photo+","+photo+","+graphic+"]}}"));
    }

    private static byte[] png(int width,int height) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        bytes.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        ByteArrayOutputStream header=new ByteArrayOutputStream();
        DataOutputStream h=new DataOutputStream(header); h.writeInt(width);h.writeInt(height);h.write(new byte[]{8,6,0,0,0});
        chunk(bytes,"IHDR",header.toByteArray());
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();
        try(DeflaterOutputStream z=new DeflaterOutputStream(compressed)) { z.write(new byte[18]); }
        chunk(bytes,"IDAT",compressed.toByteArray()); chunk(bytes,"IEND",new byte[0]);
        return bytes.toByteArray();
    }
    private static void chunk(ByteArrayOutputStream out,String type,byte[] data) throws Exception {
        byte[] kind=type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        DataOutputStream stream=new DataOutputStream(out); stream.writeInt(data.length);stream.write(kind);stream.write(data);
        CRC32 crc=new CRC32();crc.update(kind);crc.update(data);stream.writeInt((int)crc.getValue());
    }
}

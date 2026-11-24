package app.pub.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete Material colour scheme of a {@link StyleGuide}, in light or dark. Colours come from tonal palettes:
 * each keeps a hue and chroma (CIE LCh) and varies only the tone, which is CIE L* as in Material's tonal palettes, so
 * text on its role colour keeps Material's contrast. A colour the user picked is used exactly when its tone suits the
 * role (light enough for dark mode, dark enough for light mode), otherwise the palette's standard tone is used. Pure
 * computation, independent of Android.
 */
public final class StyleColors {
    /** Material 3 colour roles, as Compose names them; also the extra graphic colour names ({@link GraphicAsset}). */
    public static final List<String> ROLES = List.of(
        "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "inversePrimary",
        "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
        "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
        "error", "onError", "errorContainer", "onErrorContainer",
        "background", "onBackground", "surface", "onSurface", "surfaceVariant", "onSurfaceVariant", "surfaceTint",
        "inverseSurface", "inverseOnSurface", "outline", "outlineVariant", "scrim",
        "surfaceBright", "surfaceDim", "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer",
        "surfaceContainerHigh", "surfaceContainerHighest");
    /** WCAG contrast that text on a role colour keeps. */
    public static final double TEXT_CONTRAST = 4.5;
    private StyleColors() {}

    /** Role name to opaque ARGB colour, in {@link #ROLES} order. */
    public static Map<String, Integer> scheme(StyleGuide guide, boolean dark) {
        int seed = argb(guide.primary);
        double[] lch = lch(seed);
        double hue = lch[2], chroma = lch[1];
        Palette primary = new Palette(hue, chroma);
        Palette secondary = guide.secondary != null ? Palette.of(argb(guide.secondary)) : new Palette(hue, Math.min(chroma, 20));
        Palette tertiary = guide.tertiary != null ? Palette.of(argb(guide.tertiary)) : new Palette((hue + 60) % 360, Math.min(chroma, 32));
        Palette neutral = new Palette(hue, Math.min(chroma, 4));
        Palette neutralVariant = new Palette(hue, Math.min(chroma, 8));
        Map<String, Integer> s = new LinkedHashMap<>();
        if (dark) {
            int p = accent(seed, primary, true);
            int sc = guide.secondary != null ? accent(argb(guide.secondary), secondary, true) : secondary.tone(80);
            int t = guide.tertiary != null ? accent(argb(guide.tertiary), tertiary, true) : tertiary.tone(80);
            put(s, "primary", p, primary.onDark(p), primary.tone(30), primary.tone(90), primary.tone(40));
            put(s, "secondary", sc, secondary.onDark(sc), secondary.tone(30), secondary.tone(90), null);
            put(s, "tertiary", t, tertiary.onDark(t), tertiary.tone(30), tertiary.tone(90), null);
            s.put("error", 0xFFF2B8B5); s.put("onError", 0xFF601410); s.put("errorContainer", 0xFF8C1D18); s.put("onErrorContainer", 0xFFF9DEDC);
            // PUB's dark surfaces sit slightly above the background, as they did before style guides.
            surfaces(s, neutral, neutralVariant, p, new int[]{6, 90, 10, 90, 30, 80, 90, 20, 60, 30, 24, 6, 4, 10, 12, 17, 22});
        } else {
            int p = accent(seed, primary, false);
            int sc = guide.secondary != null ? accent(argb(guide.secondary), secondary, false) : secondary.tone(40);
            int t = guide.tertiary != null ? accent(argb(guide.tertiary), tertiary, false) : tertiary.tone(40);
            put(s, "primary", p, primary.onLight(p), primary.tone(90), primary.tone(10), primary.tone(80));
            put(s, "secondary", sc, secondary.onLight(sc), secondary.tone(90), secondary.tone(10), null);
            put(s, "tertiary", t, tertiary.onLight(t), tertiary.tone(90), tertiary.tone(10), null);
            s.put("error", 0xFFB3261E); s.put("onError", 0xFFFFFFFF); s.put("errorContainer", 0xFFF9DEDC); s.put("onErrorContainer", 0xFF410E0B);
            surfaces(s, neutral, neutralVariant, p, new int[]{98, 10, 98, 10, 90, 30, 20, 95, 50, 80, 98, 87, 100, 96, 94, 92, 90});
        }
        s.put("scrim", 0xFF000000);
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (String role : ROLES) ordered.put(role, s.get(role));
        return Collections.unmodifiableMap(ordered);
    }

    private static void put(Map<String, Integer> s, String name, int color, int on, int container, int onContainer, Integer inverse) {
        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        s.put(name, color); s.put("on" + cap, on); s.put(name + "Container", container); s.put("on" + cap + "Container", onContainer);
        if (inverse != null) s.put("inverse" + cap, inverse);
    }

    /** Tones in order: background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, inverseSurface,
     *  inverseOnSurface, outline, outlineVariant, surfaceBright, surfaceDim, then the five containers lowest to highest. */
    private static void surfaces(Map<String, Integer> s, Palette n, Palette nv, int p, int[] t) {
        s.put("background", n.tone(t[0])); s.put("onBackground", n.tone(t[1]));
        s.put("surface", n.tone(t[2])); s.put("onSurface", n.tone(t[3]));
        s.put("surfaceVariant", nv.tone(t[4])); s.put("onSurfaceVariant", nv.tone(t[5]));
        s.put("surfaceTint", p);
        s.put("inverseSurface", n.tone(t[6])); s.put("inverseOnSurface", n.tone(t[7]));
        s.put("outline", nv.tone(t[8])); s.put("outlineVariant", nv.tone(t[9]));
        s.put("surfaceBright", n.tone(t[10])); s.put("surfaceDim", n.tone(t[11]));
        s.put("surfaceContainerLowest", n.tone(t[12])); s.put("surfaceContainerLow", n.tone(t[13]));
        s.put("surfaceContainer", n.tone(t[14])); s.put("surfaceContainerHigh", n.tone(t[15]));
        s.put("surfaceContainerHighest", n.tone(t[16]));
    }

    /**
     * The user's colour itself when it suits the mode, else the palette's standard tone for the role (80 in dark mode,
     * 40 in light mode). In dark mode it suits when it is light (tone 60 to 95); in light mode when white text on it
     * keeps {@link #TEXT_CONTRAST}.
     */
    private static int accent(int picked, Palette palette, boolean dark) {
        double tone = tone(picked);
        boolean suits = dark ? tone >= 60 && tone <= 95 : contrast(0xFFFFFFFF, picked) >= TEXT_CONTRAST;
        return suits ? (picked | 0xFF000000) : palette.tone(dark ? 80 : 40);
    }

    /** CIE L* (Material's tone) of an sRGB colour, 0..100. */
    public static double tone(int argb) {
        double y = luminance(argb);
        return y > 216.0 / 24389 ? 116 * Math.cbrt(y) - 16 : y * 24389 / 27;
    }

    /** WCAG 2 contrast ratio of two opaque colours, 1..21. */
    public static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + .05) / (Math.min(la, lb) + .05);
    }

    /** "#RRGGBB" to opaque ARGB. */
    public static int argb(String hex) {
        if (!StyleGuide.validColor(hex)) throw new IllegalArgumentException("Expected #RRGGBB");
        return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
    }

    /** Opaque ARGB to "#RRGGBB". */
    public static String hex(int argb) { return String.format("#%06X", argb & 0xFFFFFF); }

    /** One hue and chroma at every tone; chroma is reduced where a tone cannot show it in sRGB. */
    private static final class Palette {
        final double hue, chroma;
        Palette(double hue, double chroma) { this.hue = hue; this.chroma = chroma; }
        static Palette of(int argb) { double[] lch = lch(argb); return new Palette(lch[2], lch[1]); }
        int tone(double t) { return fromLch(t, chroma, hue); }
        /** The lightest dark tone (from 20 down) that text on [color] needs for {@link #TEXT_CONTRAST}. */
        int onDark(int color) {
            for (int t = 20; t >= 0; t -= 5) { int on = tone(t); if (contrast(on, color) >= TEXT_CONTRAST) return on; }
            return 0xFF000000;
        }
        /** White when it keeps {@link #TEXT_CONTRAST} on [color], else the darkest tone. */
        int onLight(int color) {
            int white = tone(100);
            return contrast(white, color) >= TEXT_CONTRAST ? white : tone(10);
        }
    }

    // sRGB (D65) <-> CIE LCh(ab).
    private static final double XN = 0.95047, YN = 1.0, ZN = 1.08883;

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
    private static double luminance(int argb) {
        return 0.2126 * linear((argb >> 16) & 255) + 0.7152 * linear((argb >> 8) & 255) + 0.0722 * linear(argb & 255);
    }
    private static double f(double t) { return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116; }
    private static double fInverse(double t) { double cube = t * t * t; return cube > 216.0 / 24389 ? cube : (116 * t - 16) / (24389.0 / 27); }

    /** {L, C, h in degrees}. */
    static double[] lch(int argb) {
        double r = linear((argb >> 16) & 255), g = linear((argb >> 8) & 255), b = linear(argb & 255);
        double x = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b;
        double y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
        double z = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b;
        double fx = f(x / XN), fy = f(y / YN), fz = f(z / ZN);
        double l = 116 * fy - 16, a = 500 * (fx - fy), bb = 200 * (fy - fz);
        double h = Math.toDegrees(Math.atan2(bb, a));
        return new double[]{l, Math.hypot(a, bb), h < 0 ? h + 360 : h};
    }

    /** The colour at tone [l] with hue [h] and the most chroma up to [c] that sRGB can show. */
    static int fromLch(double l, double c, double h) {
        if (l <= 0) return 0xFF000000;
        if (l >= 100) return 0xFFFFFFFF;
        double[] rgb = rgb(l, c, h);
        if (rgb == null) {
            double low = 0, high = c;
            rgb = rgb(l, 0, h);
            for (int i = 0; i < 24; i++) {
                double mid = (low + high) / 2;
                double[] candidate = rgb(l, mid, h);
                if (candidate != null) { low = mid; rgb = candidate; } else high = mid;
            }
        }
        int argb = 0xFF000000;
        for (int i = 0; i < 3; i++) argb |= (int) Math.round(Math.min(1, Math.max(0, rgb[i])) * 255) << (16 - 8 * i);
        return argb;
    }

    /** Gamma-encoded sRGB 0..1 per channel, or null outside the sRGB gamut. */
    private static double[] rgb(double l, double c, double h) {
        double a = c * Math.cos(Math.toRadians(h)), b = c * Math.sin(Math.toRadians(h));
        double fy = (l + 16) / 116, fx = fy + a / 500, fz = fy - b / 200;
        double x = XN * fInverse(fx), y = YN * fInverse(fy), z = ZN * fInverse(fz);
        double[] lin = {
            3.2404542 * x - 1.5371385 * y - 0.4985314 * z,
            -0.9692660 * x + 1.8760108 * y + 0.0415560 * z,
            0.0556434 * x - 0.2040259 * y + 1.0572252 * z};
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            if (lin[i] < -1e-4 || lin[i] > 1 + 1e-4) return null;
            double v = Math.min(1, Math.max(0, lin[i]));
            out[i] = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        }
        return out;
    }
}

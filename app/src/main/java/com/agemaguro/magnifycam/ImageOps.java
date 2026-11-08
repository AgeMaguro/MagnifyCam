package com.agemaguro.magnifycam;

/**
 * Pure-Java pixel maths (no Android classes) so it can be tested on any computer.
 * Pixels are ARGB ints, the same layout as android.graphics.Bitmap.getPixels().
 *
 * - resample(): bicubic (Catmull-Rom) crop + enlarge, much crisper than the phone's default bilinear.
 * - sharpen(): unsharp mask on brightness only, so colours do not get fringes or noisy speckles.
 */
public final class ImageOps {
    private ImageOps() {}

    /** Largest picture we will build when saving (pixels). Keeps memory safe on phones. */
    public static final long MAX_OUTPUT_PIXELS = 12_500_000L;

    // ------------------------------------------------------------------ sizes

    /** Output width/height for a crop of cw x ch enlarged by scale, reduced if it would be too big. */
    public static int[] outputSize(int cw, int ch, float scale) {
        double w = cw * (double) scale, h = ch * (double) scale;
        double px = w * h;
        if (px > MAX_OUTPUT_PIXELS) {
            double k = Math.sqrt(MAX_OUTPUT_PIXELS / px);
            w *= k;
            h *= k;
        }
        return new int[] { Math.max(1, (int) Math.round(w)), Math.max(1, (int) Math.round(h)) };
    }

    /** Blur width (sigma, in output pixels) that suits a picture enlarged by this much. */
    public static float sigmaFor(float scale) {
        float s = Math.max(1f, Math.min(4f, scale));
        return 0.75f * s;
    }

    /** Slider 0..100 -> unsharp "amount" 0..2.5 (40 = 1.0, a normal strength). */
    public static float amountFor(int slider) {
        return Math.max(0, Math.min(100, slider)) / 100f * 2.5f;
    }

    // -------------------------------------------------------------- resample

    /**
     * Cut the box [left,top,right,bottom) out of a picture and scale it to outW x outH using Catmull-Rom.
     *
     * @param src      pixels of the region [rx0, ry0, rx1, ry1) of the full picture (row stride = rx1-rx0)
     * @param fullW    width of the full picture (for edge clamping)
     * @param fullH    height of the full picture
     */
    public static int[] resample(int[] src, int rx0, int ry0, int rx1, int ry1, int fullW, int fullH,
                                 double left, double top, double right, double bottom, int outW, int outH) {
        final int stride = rx1 - rx0;
        final double sx = (right - left) / (double) outW;
        final double sy = (bottom - top) / (double) outH;

        // Per output column: 4 tap positions (relative to the region) and weights.
        int[] xi = new int[outW * 4];
        float[] xw = new float[outW * 4];
        for (int x = 0; x < outW; x++) {
            double p = left + (x + 0.5) * sx - 0.5;
            int f = (int) Math.floor(p);
            float t = (float) (p - f);
            catmull(t, xw, x * 4);
            for (int k = 0; k < 4; k++) {
                int c = clamp(f - 1 + k, 0, fullW - 1);
                xi[x * 4 + k] = c - rx0;
            }
        }
        int[] yi = new int[4];
        float[] yw = new float[4];
        int[] out = new int[outW * outH];
        for (int y = 0; y < outH; y++) {
            double p = top + (y + 0.5) * sy - 0.5;
            int f = (int) Math.floor(p);
            float t = (float) (p - f);
            catmull(t, yw, 0);
            for (int k = 0; k < 4; k++) yi[k] = (clamp(f - 1 + k, 0, fullH - 1) - ry0) * stride;
            int o = y * outW;
            for (int x = 0; x < outW; x++) {
                float r = 0, g = 0, b = 0;
                int xb = x * 4;
                for (int j = 0; j < 4; j++) {
                    int row = yi[j];
                    float wy = yw[j];
                    for (int k = 0; k < 4; k++) {
                        int px = src[row + xi[xb + k]];
                        float w = wy * xw[xb + k];
                        r += w * ((px >> 16) & 255);
                        g += w * ((px >> 8) & 255);
                        b += w * (px & 255);
                    }
                }
                out[o + x] = 0xFF000000 | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
            }
        }
        return out;
    }

    private static void catmull(float t, float[] w, int o) {
        float t2 = t * t, t3 = t2 * t;
        w[o] = -0.5f * t3 + t2 - 0.5f * t;
        w[o + 1] = 1.5f * t3 - 2.5f * t2 + 1f;
        w[o + 2] = -1.5f * t3 + 2f * t2 + 0.5f * t;
        w[o + 3] = 0.5f * t3 - 0.5f * t2;
    }

    // --------------------------------------------------------------- sharpen

    /**
     * Unsharp mask on brightness. Changes pixels in place.
     *
     * @param amount    0 = nothing; 1 = normal; 2.5 = very strong
     * @param sigma     blur width in pixels (see sigmaFor)
     * @param threshold tiny brightness differences (noise) below this are left alone
     */
    public static void sharpen(int[] px, int w, int h, float amount, float sigma, int threshold) {
        if (amount <= 0f || w < 3 || h < 3) return;
        final int n = w * h;
        int[] luma = new int[n];
        for (int i = 0; i < n; i++) {
            int p = px[i];
            luma[i] = (77 * ((p >> 16) & 255) + 150 * ((p >> 8) & 255) + 29 * (p & 255)) >> 8;
        }
        int[] blur = new int[n];
        System.arraycopy(luma, 0, blur, 0, n);
        int[] tmp = new int[n];
        int[] radii = boxRadii(sigma);
        for (int r : radii) {
            if (r <= 0) continue;
            int rr = Math.min(r, (Math.min(w, h) - 1) / 2);
            if (rr <= 0) continue;
            boxH(blur, tmp, w, h, rr);
            boxV(tmp, blur, w, h, rr);
        }
        final float limit = 80f;
        for (int i = 0; i < n; i++) {
            float d = luma[i] - blur[i];
            if (d > threshold) d -= threshold;
            else if (d < -threshold) d += threshold;
            else continue;
            float delta = amount * d;
            if (delta > limit) delta = limit;
            else if (delta < -limit) delta = -limit;
            int p = px[i];
            int r = clamp255(((p >> 16) & 255) + delta);
            int g = clamp255(((p >> 8) & 255) + delta);
            int b = clamp255((p & 255) + delta);
            px[i] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
        }
    }

    /** Three box-blur radii that together approximate a Gaussian of the given sigma. */
    static int[] boxRadii(float sigma) {
        final int n = 3;
        double ideal = Math.sqrt(12.0 * sigma * sigma / n + 1);
        int wl = (int) Math.floor(ideal);
        if (wl % 2 == 0) wl--;
        int wu = wl + 2;
        double mIdeal = (12.0 * sigma * sigma - n * wl * wl - 4.0 * n * wl - 3.0 * n) / (-4.0 * wl - 4.0);
        int m = (int) Math.round(mIdeal);
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = ((i < m ? wl : wu) - 1) / 2;
        return r;
    }

    private static void boxH(int[] in, int[] out, int w, int h, int r) {
        final int div = 2 * r + 1, half = div / 2;
        for (int y = 0; y < h; y++) {
            int o = y * w;
            int sum = 0;
            for (int k = -r; k <= r; k++) sum += in[o + clamp(k, 0, w - 1)];
            for (int x = 0; x < w; x++) {
                out[o + x] = (sum + half) / div;
                sum += in[o + Math.min(x + r + 1, w - 1)] - in[o + Math.max(x - r, 0)];
            }
        }
    }

    private static void boxV(int[] in, int[] out, int w, int h, int r) {
        final int div = 2 * r + 1, half = div / 2;
        int[] col = new int[w];
        for (int k = -r; k <= r; k++) {
            int o = clamp(k, 0, h - 1) * w;
            for (int x = 0; x < w; x++) col[x] += in[o + x];
        }
        for (int y = 0; y < h; y++) {
            int o = y * w;
            for (int x = 0; x < w; x++) out[o + x] = (col[x] + half) / div;
            int add = Math.min(y + r + 1, h - 1) * w;
            int sub = Math.max(y - r, 0) * w;
            for (int x = 0; x < w; x++) col[x] += in[add + x] - in[sub + x];
        }
    }

    // --------------------------------------------------------------- helpers

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clamp255(float v) {
        int i = Math.round(v);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }
}

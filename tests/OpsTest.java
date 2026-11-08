import com.agemaguro.magnifycam.ImageOps;
import java.util.Arrays;

public class OpsTest {
    static int fails = 0;
    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        if (!ok) fails++;
    }
    static int gray(int v) { return 0xFF000000 | (v << 16) | (v << 8) | v; }
    static int g(int p) { return p & 255; }

    public static void main(String[] a) {
        // 1. identity at 1x
        int W = 40, H = 30;
        int[] img = new int[W * H];
        java.util.Random rnd = new java.util.Random(1);
        for (int i = 0; i < img.length; i++) img[i] = 0xFF000000 | rnd.nextInt(0xFFFFFF);
        int[] id = ImageOps.resample(img, 0, 0, W, H, W, H, 0, 0, W, H, W, H);
        check("resample 1x is identity", Arrays.equals(img, id), "");

        // 1b. crop with margin, 1x
        int[] reg = new int[(W) * (H)];
        int[] crop = ImageOps.resample(img, 0, 0, W, H, W, H, 10, 5, 30, 25, 20, 20);
        boolean ok = true;
        for (int y = 0; y < 20 && ok; y++) for (int x = 0; x < 20; x++) if (crop[y*20+x] != img[(y+5)*W + x+10]) { ok = false; break; }
        check("crop 1x matches source pixels", ok, "");

        // 2. linear ramp enlarged 2x/3x stays on the ramp (Catmull-Rom reproduces lines)
        int RW = 100, RH = 20;
        int[] ramp = new int[RW * RH];
        for (int y = 0; y < RH; y++) for (int x = 0; x < RW; x++) ramp[y*RW+x] = gray(x * 2);
        for (int s : new int[]{2, 3, 4}) {
            int[] up = ImageOps.resample(ramp, 0, 0, RW, RH, RW, RH, 20, 5, 60, 15, 40 * s, 10 * s);
            double maxErr = 0;
            for (int x = 8; x < 40 * s - 8; x++) {   // skip nothing at borders needed; interior only
                double srcX = 20 + (x + 0.5) / s - 0.5;
                double expect = srcX * 2;
                maxErr = Math.max(maxErr, Math.abs(g(up[(5 * s) * 40 * s + x]) - expect));
            }
            check("ramp " + s + "x error <= 1", maxErr <= 1.0, "maxErr=" + maxErr);
        }

        // 3. sizes and cap
        int[] sz = ImageOps.outputSize(1000, 750, 2f);
        check("outputSize 2x", sz[0] == 2000 && sz[1] == 1500, sz[0] + "x" + sz[1]);
        sz = ImageOps.outputSize(4000, 3000, 4f);
        check("outputSize capped", (long) sz[0] * sz[1] <= ImageOps.MAX_OUTPUT_PIXELS + 20000 && Math.abs(sz[0] / (double) sz[1] - 4.0 / 3) < 0.01, sz[0] + "x" + sz[1]);
        check("amountFor", ImageOps.amountFor(0) == 0f && Math.abs(ImageOps.amountFor(40) - 1f) < 1e-6 && Math.abs(ImageOps.amountFor(100) - 2.5f) < 1e-6, "");

        // 4. box radii
        for (float s : new float[]{0.75f, 1.5f, 2.25f, 3f}) {
            int[] r = ImageOps.class.getDeclaredMethods().length > 0 ? boxRadii(s) : null;
            // variance of three box blurs of radius r: sum (( (2r+1)^2 - 1)/12)
            double var = 0; for (int x : r) var += ((2.0*x+1)*(2.0*x+1) - 1) / 12.0;
            check("boxRadii sigma " + s, Math.abs(Math.sqrt(var) - s) < 0.35, Arrays.toString(r) + " sigma~" + String.format("%.2f", Math.sqrt(var)));
        }

        // 5. sharpen: blurred edge gets steeper, flat areas untouched, amount 0 = no change
        int EW = 80, EH = 40;
        int[] edge = new int[EW * EH];
        for (int y = 0; y < EH; y++) for (int x = 0; x < EW; x++) {
            double t = 1 / (1 + Math.exp(-(x - 40) / 0.9));   // blurry step, about what a soft phone photo has
            edge[y*EW+x] = gray((int) Math.round(60 + 130 * t));
        }
        int[] before = edge.clone();
        ImageOps.sharpen(edge, EW, EH, 0f, 1.5f, 2);
        check("amount 0 unchanged", Arrays.equals(edge, before), "");
        ImageOps.sharpen(edge, EW, EH, 1.5f, 1.5f, 2);
        int row = 20 * EW;
        int maxSlopeB = 0, maxSlopeA = 0;
        for (int x = 1; x < EW; x++) {
            maxSlopeB = Math.max(maxSlopeB, g(before[row + x]) - g(before[row + x - 1]));
            maxSlopeA = Math.max(maxSlopeA, g(edge[row + x]) - g(edge[row + x - 1]));
        }
        int riseB = 0, riseA = 0;
        for (int x = 0; x < EW; x++) { int vb = g(before[row+x]), va = g(edge[row+x]); if (vb > 60+13 && vb < 190-13) riseB++; if (va > 60+13 && va < 190-13) riseA++; }
        int over = 0; for (int x = 0; x < EW; x++) over = Math.max(over, g(edge[row+x]) - 190);
        check("edge slope increases", maxSlopeA > maxSlopeB * 1.3, maxSlopeB + " -> " + maxSlopeA);
        check("effective edge width shrinks", 130.0/maxSlopeA < 0.85 * 130.0/maxSlopeB, String.format("%.1f px -> %.1f px", 130.0/maxSlopeB, 130.0/maxSlopeA));
        check("overshoot (halo) modest", over <= 25, "overshoot=" + over);
        check("flat left untouched", g(edge[row + 2]) == g(before[row + 2]) && g(edge[row + 77]) == g(before[row + 77]), g(before[row+2]) + "," + g(edge[row+2]));
        boolean sameRows = true;
        for (int x = 0; x < EW; x++) if (edge[x] != edge[row + x]) sameRows = false;
        check("rows identical (no vertical artefacts)", sameRows, "");
        boolean grayOk = true; for (int p : edge) if (((p>>16)&255) != (p&255) || ((p>>8)&255) != (p&255)) grayOk = false;
        check("grey stays grey (luma-only)", grayOk, "");
        // colour: red/blue edge should not change hue wildly: channels move by same delta
        int[] col = new int[EW * EH];
        for (int y = 0; y < EH; y++) for (int x = 0; x < EW; x++) { double t = 1/(1+Math.exp(-(x-40)/2.0)); int v=(int)Math.round(100*t); col[y*EW+x]=0xFF000000|((80+v)<<16)|((120)<<8)|(60+v/2); }
        int[] cb = col.clone(); ImageOps.sharpen(col, EW, EH, 1.0f, 1.5f, 2);
        boolean sameDelta = true;
        for (int i = 0; i < col.length; i++) { int dr=((col[i]>>16)&255)-((cb[i]>>16)&255), dg=((col[i]>>8)&255)-((cb[i]>>8)&255), db=(col[i]&255)-(cb[i]&255); if (Math.abs(dr-dg)>1||Math.abs(dg-db)>1) { sameDelta=false; break; } }
        check("colour edge: all channels shift together", sameDelta, "");

        // 6. tiny / degenerate sizes don't crash
        try { ImageOps.sharpen(new int[4], 2, 2, 1f, 1f, 2); ImageOps.sharpen(new int[9], 3, 3, 2f, 3f, 2);
              ImageOps.sharpen(new int[3*50], 3, 50, 2f, 3f, 2); ImageOps.sharpen(new int[50*3], 50, 3, 2f, 3f, 2);
              check("degenerate sizes ok", true, ""); } catch (Throwable t) { check("degenerate sizes ok", false, t.toString()); }

        // 7. speed on a phone-photo-sized job (this PC is faster than a phone; roughly 3-5x slower on phone)
        int BW = 4000, BH = 3000;
        int[] big = new int[BW * BH]; for (int i = 0; i < big.length; i++) big[i] = 0xFF000000 | (i * 2654435761L > 0 ? (int)(i * 2654435761L) & 0xFFFFFF : 0);
        long t0 = System.nanoTime(); ImageOps.sharpen(big, BW, BH, 1.0f, 1.5f, 2); long t1 = System.nanoTime();
        System.out.println("INFO sharpen 12MP: " + (t1 - t0) / 1_000_000 + " ms");
        int[] small = new int[1000 * 750]; 
        t0 = System.nanoTime(); int[] up = ImageOps.resample(big, 0, 0, BW, BH, BW, BH, 1000, 1000, 2000, 1750, 2000, 1500); t1 = System.nanoTime();
        System.out.println("INFO resample 3MP out: " + (t1 - t0) / 1_000_000 + " ms");
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    // boxRadii is package-private; reach it by reflection
    static int[] boxRadii(float s) {
        try { var m = ImageOps.class.getDeclaredMethod("boxRadii", float.class); m.setAccessible(true); return (int[]) m.invoke(null, s); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}

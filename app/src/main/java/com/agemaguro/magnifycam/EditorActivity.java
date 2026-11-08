package com.agemaguro.magnifycam;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Second screen: pinch / drag / double-tap to magnify the picture you took, slide to sharpen it,
 * pick how much to enlarge it, then Save. The saved file is the part you can see, enlarged and sharpened,
 * in Pictures/MagnifyCam.
 */
public class EditorActivity extends Activity {
    static final String EXTRA_PATH = "path";
    private static final int MAX_LOAD_SIDE = 4096;   // biggest side we keep in memory (graphics limit)
    private static final int ACCENT = 0xFF7FB2FF;
    private static final int PANEL = 0xE60F1217;

    private ZoomImageView view;
    private TextView sharpValue, info, saveBtn;
    private SeekBar sharpBar;
    private final List<TextView> chips = new ArrayList<>();
    private View busy;
    private int enlarge = 2;
    private String path;
    private boolean saving;
    private TextView photoInfo;

    // Real (full-resolution) photo, used when it is bigger than the picture we keep in memory.
    private BitmapRegionDecoder decoder;
    private final Object regionLock = new Object();
    private int exifOrientation = ExifInterface.ORIENTATION_NORMAL;
    private int fullW, fullH;   // real photo size as displayed (after rotation)
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null) {
            finish();
            return;
        }
        buildUi();
        loadPicture();
    }

    // ------------------------------------------------------------------ screen

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        view = new ZoomImageView(this);
        view.setListener(this::updateInfo);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        // top bar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView back = pill("‹ Back", 0x99000000);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView compare = pill("Hold: original", 0x99000000);
        compare.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) view.setShowOriginal(true);
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)
                view.setShowOriginal(false);
            return true;
        });
        top.addView(compare);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // bottom panel
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(PANEL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout sharpRow = new LinearLayout(this);
        sharpRow.setOrientation(LinearLayout.HORIZONTAL);
        sharpRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView sharpLabel = label("Sharpen");
        sharpRow.addView(sharpLabel, new LinearLayout.LayoutParams(dp(78), -2));
        sharpBar = new SeekBar(this);
        sharpBar.setMax(100);
        sharpBar.setProgress(40);
        sharpRow.addView(sharpBar, new LinearLayout.LayoutParams(0, -2, 1f));
        sharpValue = label("40");
        sharpValue.setGravity(Gravity.END);
        sharpRow.addView(sharpValue, new LinearLayout.LayoutParams(dp(36), -2));
        panel.addView(sharpRow);

        LinearLayout enlargeRow = new LinearLayout(this);
        enlargeRow.setOrientation(LinearLayout.HORIZONTAL);
        enlargeRow.setGravity(Gravity.CENTER_VERTICAL);
        enlargeRow.setPadding(0, dp(6), 0, dp(6));
        enlargeRow.addView(label("Enlarge"), new LinearLayout.LayoutParams(dp(78), -2));
        for (int i = 1; i <= 4; i++) {
            final int n = i;
            TextView chip = pill(n + "×", 0xFF2A2F3A);
            chip.setGravity(Gravity.CENTER);
            chip.setOnClickListener(v -> {
                enlarge = n;
                styleChips();
                updateInfo();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lp.rightMargin = dp(8);
            enlargeRow.addView(chip, lp);
            chips.add(chip);
        }
        panel.addView(enlargeRow);
        styleChips();

        photoInfo = new TextView(this);
        photoInfo.setTextColor(0xFFB8C0CF);
        photoInfo.setTextSize(13);
        panel.addView(photoInfo);

        info = new TextView(this);
        info.setTextColor(0xFFB8C0CF);
        info.setTextSize(13);
        info.setPadding(0, 0, 0, dp(8));
        panel.addView(info);

        saveBtn = pill("Save to gallery", ACCENT);
        saveBtn.setTextColor(0xFF0B1220);
        saveBtn.setTypeface(Typeface.DEFAULT_BOLD);
        saveBtn.setTextSize(17);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setOnClickListener(v -> save());
        panel.addView(saveBtn, new LinearLayout.LayoutParams(-1, dp(50)));

        root.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // "working" cover that also blocks touches
        FrameLayout cover = new FrameLayout(this);
        cover.setBackgroundColor(0xAA000000);
        cover.setClickable(true);
        cover.addView(new ProgressBar(this), new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        cover.setVisibility(View.GONE);
        busy = cover;
        root.addView(cover, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);

        sharpBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                sharpValue.setText(String.valueOf(p));
                view.setAmount(ImageOps.amountFor(p));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        view.setAmount(ImageOps.amountFor(sharpBar.getProgress()));
    }

    private void styleChips() {
        for (int i = 0; i < chips.size(); i++) {
            TextView c = chips.get(i);
            boolean on = (i + 1) == enlarge;
            ((GradientDrawable) c.getBackground()).setColor(on ? ACCENT : 0xFF2A2F3A);
            c.setTextColor(on ? 0xFF0B1220 : 0xFFE8ECF3);
        }
    }

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(0xFFE8ECF3);
        v.setTextSize(15);
        return v;
    }

    private TextView pill(String text, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(0xFFE8ECF3);
        v.setTextSize(15);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(22));
        g.setColor(color);
        v.setBackground(g);
        return v;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ----------------------------------------------------------------- loading

    private void loadPicture() {
        busy.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, o);
                final int fileW = o.outWidth, fileH = o.outHeight;
                if (fileW <= 0 || fileH <= 0) throw new IOException("could not read the picture");
                int sample = 1;
                while (Math.max(fileW, fileH) / sample > MAX_LOAD_SIDE) sample *= 2;
                int orient = ExifInterface.ORIENTATION_NORMAL;
                try {
                    orient = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL);
                } catch (IOException ignored) {
                }
                exifOrientation = orient;
                boolean swap = orient == ExifInterface.ORIENTATION_ROTATE_90
                        || orient == ExifInterface.ORIENTATION_ROTATE_270
                        || orient == ExifInterface.ORIENTATION_TRANSPOSE
                        || orient == ExifInterface.ORIENTATION_TRANSVERSE;
                fullW = swap ? fileH : fileW;
                fullH = swap ? fileW : fileH;

                // Real-pixel access: only if the photo is bigger than what we keep in memory, and the
                // rotation is one we can map (0/90/180/270).
                boolean mappable = orient == ExifInterface.ORIENTATION_NORMAL
                        || orient == ExifInterface.ORIENTATION_UNDEFINED
                        || orient == ExifInterface.ORIENTATION_ROTATE_90
                        || orient == ExifInterface.ORIENTATION_ROTATE_180
                        || orient == ExifInterface.ORIENTATION_ROTATE_270;
                if (sample > 1 && mappable) {
                    try {
                        BitmapRegionDecoder d = BitmapRegionDecoder.newInstance(path, false);
                        if (d != null && d.getWidth() == fileW && d.getHeight() == fileH) decoder = d;
                        else if (d != null) d.recycle();
                    } catch (Throwable t) {
                        decoder = null;
                    }
                }

                o.inJustDecodeBounds = false;
                o.inSampleSize = sample;
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap b = BitmapFactory.decodeFile(path, o);
                if (b == null) throw new IOException("could not read the picture");
                b = applyOrientation(b, orient);
                Bitmap[] levels = buildPyramid(b);
                final Bitmap fb = b;
                final boolean real = decoder != null;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    view.setBitmap(fb, levels);
                    if (real) view.setDetail(fullW, fullH, this::decodeRegion);
                    enlarge = real ? 1 : 2;
                    styleChips();
                    double mp = fullW * (double) fullH / 1e6;
                    photoInfo.setText(String.format(Locale.US, "Original photo: %d × %d  (%.1f MP)%s",
                            fullW, fullH, mp, real ? "" : "  - shown at reduced size"));
                    view.setAmount(ImageOps.amountFor(sharpBar.getProgress()));
                    busy.setVisibility(View.GONE);
                    updateInfo();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Could not open that picture: " + t.getMessage(), Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });
    }

    /**
     * Real pixels of part of the photo. rect is in displayed (rotated) photo pixels; the result is rotated to match.
     * Safe to call from several threads. Returns null if it cannot be done.
     */
    private Bitmap decodeRegion(Rect d, int sample) {
        try {
            synchronized (regionLock) {
                if (decoder == null) return null;
                int fw = decoder.getWidth(), fh = decoder.getHeight();   // file orientation
                Rect f = new Rect();
                switch (exifOrientation) {
                    case ExifInterface.ORIENTATION_ROTATE_90:
                        f.set(d.top, fh - d.right, d.bottom, fh - d.left);
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_180:
                        f.set(fw - d.right, fh - d.bottom, fw - d.left, fh - d.top);
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_270:
                        f.set(fw - d.bottom, d.left, fw - d.top, d.right);
                        break;
                    default:
                        f.set(d);
                }
                if (!f.intersect(0, 0, fw, fh) || f.width() < 1 || f.height() < 1) return null;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = Math.max(1, sample);
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap b = decoder.decodeRegion(f, o);
                if (b == null) return null;
                return applyOrientation(b, exifOrientation);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap applyOrientation(Bitmap b, int o) {
        Matrix m = new Matrix();
        switch (o) {
            case ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.postScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: m.postRotate(90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.postRotate(270); m.postScale(-1, 1); break;
            default: return b;
        }
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    private static Bitmap[] buildPyramid(Bitmap b) {
        List<Bitmap> list = new ArrayList<>();
        list.add(b);
        Bitmap cur = b;
        while (cur.getWidth() >= 400 && cur.getHeight() >= 400) {
            cur = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
            list.add(cur);
        }
        return list.toArray(new Bitmap[0]);
    }

    // -------------------------------------------------------------------- info

    /** The on-screen part of the overview picture, converted to real-photo pixels (same box if no real pixels). */
    private Rect realCrop(Rect c, Bitmap overview) {
        if (decoder == null) return new Rect(c);
        float kx = fullW / (float) overview.getWidth(), ky = fullH / (float) overview.getHeight();
        Rect r = new Rect(
                Math.max(0, (int) Math.floor(c.left * kx)), Math.max(0, (int) Math.floor(c.top * ky)),
                Math.min(fullW, (int) Math.ceil(c.right * kx)), Math.min(fullH, (int) Math.ceil(c.bottom * ky)));
        if (r.width() < 1 || r.height() < 1) return new Rect(0, 0, fullW, fullH);
        return r;
    }

    private void updateInfo() {
        if (view == null || info == null || view.getBitmap() == null) return;
        Rect r = realCrop(view.visibleSourceRect(), view.getBitmap());
        int[] sz = ImageOps.outputSize(r.width(), r.height(), enlarge);
        float eff = sz[0] / (float) r.width();
        String t = "Saves what you see: " + sz[0] + " × " + sz[1] + " px";
        if (decoder != null && enlarge == 1 && eff > 0.99f) t += "  (real photo pixels)";
        if (eff + 0.01f < enlarge) t += "  (limited to " + String.format(Locale.US, "%.1f", eff) + "× to fit memory)";
        info.setText(t);
    }

    // -------------------------------------------------------------------- save

    private void save() {
        if (saving || view.getBitmap() == null) return;
        saving = true;
        busy.setVisibility(View.VISIBLE);
        final Bitmap src = view.getBitmap();
        final Rect crop = realCrop(view.visibleSourceRect(), src);
        final int[] size = ImageOps.outputSize(crop.width(), crop.height(), enlarge);
        final float scale = size[0] / (float) crop.width();
        final float amount = ImageOps.amountFor(sharpBar.getProgress());
        worker.execute(() -> {
            String result;
            try {
                result = render(src, crop, size[0], size[1], scale, amount);
            } catch (OutOfMemoryError oom) {
                result = "Not enough memory for that size. Try a smaller Enlarge, or zoom in less.";
            } catch (Throwable t) {
                result = "Could not save: " + t.getMessage();
            }
            final String msg = result;
            runOnUiThread(() -> {
                saving = false;
                busy.setVisibility(View.GONE);
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            });
        });
    }

    /** Cut, enlarge, sharpen, and write the JPEG into Pictures/MagnifyCam. Returns a message for the user. */
    private String render(Bitmap src, Rect crop, int outW, int outH, float scale, float amount) throws IOException {
        int[] px = null;
        if (decoder != null) px = renderFromRealPixels(crop, outW, outH);
        if (px == null) {
            // Fallback: use the reduced-size picture held in memory. (crop is then in real-photo pixels,
            // so convert it back to overview pixels first.)
            Rect c = crop;
            if (decoder != null) {
                float kx = src.getWidth() / (float) fullW, ky = src.getHeight() / (float) fullH;
                c = new Rect((int) Math.floor(crop.left * kx), (int) Math.floor(crop.top * ky),
                        Math.min(src.getWidth(), (int) Math.ceil(crop.right * kx)),
                        Math.min(src.getHeight(), (int) Math.ceil(crop.bottom * ky)));
            }
            // pixels of the part we need, plus a 2-pixel margin for the smooth-resize filter
            int rx0 = Math.max(0, c.left - 2), ry0 = Math.max(0, c.top - 2);
            int rx1 = Math.min(src.getWidth(), c.right + 2), ry1 = Math.min(src.getHeight(), c.bottom + 2);
            int rw = rx1 - rx0, rh = ry1 - ry0;
            int[] region = new int[rw * rh];
            src.getPixels(region, 0, rw, rx0, ry0, rw, rh);
            px = ImageOps.resample(region, rx0, ry0, rx1, ry1, src.getWidth(), src.getHeight(),
                    c.left, c.top, c.right, c.bottom, outW, outH);
            region = null;
        }
        ImageOps.sharpen(px, outW, outH, amount, ImageOps.sigmaFor(scale), 2);

        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, outW, 0, 0, outW, outH);
        px = null;

        String name = "MagnifyCam_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        ContentResolver cr = getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MagnifyCam");
        cv.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException("the gallery refused the new file");
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null || !out.compress(Bitmap.CompressFormat.JPEG, 95, os)) throw new IOException("writing failed");
        } catch (IOException | RuntimeException e) {
            cr.delete(uri, null, null);
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
        } finally {
            out.recycle();
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        return "Saved " + outW + " × " + outH + " to Pictures/MagnifyCam";
    }

    /** Cut the crop (real-photo pixels) straight out of the full-resolution file and resize it. Null if it can't. */
    private int[] renderFromRealPixels(Rect crop, int outW, int outH) {
        final int cw = crop.width(), ch = crop.height();
        // Decode at the smallest reduction that still gives at least the output size, within a memory limit.
        int s = 1;
        while (cw / (s * 2) >= outW && ch / (s * 2) >= outH) s *= 2;
        while ((long) (cw / s) * (ch / s) > 16_000_000L) s *= 2;
        Rect want = new Rect(Math.max(0, crop.left - 2 * s), Math.max(0, crop.top - 2 * s),
                Math.min(fullW, crop.right + 2 * s), Math.min(fullH, crop.bottom + 2 * s));
        Bitmap dec = decodeRegion(want, s);
        if (dec == null) return null;
        try {
            final int dw = dec.getWidth(), dh = dec.getHeight();
            final double sx = want.width() / (double) dw, sy = want.height() / (double) dh;
            int[] region = new int[dw * dh];
            dec.getPixels(region, 0, dw, 0, 0, dw, dh);
            dec.recycle();
            dec = null;
            return ImageOps.resample(region, 0, 0, dw, dh, dw, dh,
                    (crop.left - want.left) / sx, (crop.top - want.top) / sy,
                    (crop.right - want.left) / sx, (crop.bottom - want.top) / sy, outW, outH);
        } catch (OutOfMemoryError oom) {
            if (dec != null) dec.recycle();
            throw oom;
        }
    }

    // ---------------------------------------------------------------- cleanup

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
        synchronized (regionLock) {
            if (decoder != null) {
                decoder.recycle();
                decoder = null;
            }
        }
        if (isFinishing() && path != null && path.startsWith(getCacheDir().getAbsolutePath())) {
            //noinspection ResultOfMethodCallIgnored
            new File(path).delete();
        }
    }
}

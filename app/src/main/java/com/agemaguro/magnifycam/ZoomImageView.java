package com.agemaguro.magnifycam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows a picture you can pinch to magnify, drag, and double-tap.
 *
 * The picture held in memory ("overview") is at most 4096 px on its long side. If the real photo is bigger
 * (50 or 200 megapixels), a DetailSource can hand us the real pixels of just the part on screen; after you stop
 * moving, that part is redrawn from the real photo and (if Sharpen is on) sharpened.
 */
public class ZoomImageView extends View {
    /** Called whenever what is visible changes (zoom or drag), so the screen can update its info text. */
    public interface Listener {
        void onViewChanged();
    }

    /** Gives real pixels from the full-resolution photo. */
    public interface DetailSource {
        /**
         * @param rect   part of the full photo, in full-photo pixels (as displayed, already rotated)
         * @param sample 1, 2, 4... decode at 1/sample size
         * @return the part as a bitmap (about rect size / sample), or null if it cannot be decoded
         */
        Bitmap decode(Rect rect, int sample);
    }

    private static final float MAX_SCALE = 12f;          // screen pixels per overview pixel
    private static final long PREVIEW_DELAY_MOVE = 220;  // ms after you stop moving
    private static final long PREVIEW_DELAY_SLIDER = 70; // ms after the slider moves

    private Bitmap bmp;
    private Bitmap[] pyramid;           // bmp, then half size, quarter size ... (for smooth zoomed-out previews)
    private final Matrix m = new Matrix();
    private final float[] vals = new float[9];
    private float fitScale = 1f;

    private DetailSource detail;        // full-resolution pixels (optional)
    private int fullW, fullH;
    private float kx = 1f, ky = 1f;    // full-photo pixels per overview pixel

    private volatile int gen;           // bumped on every change; out-of-date previews are thrown away
    private Bitmap sharp;               // sharpened picture of the visible part
    private Bitmap detailLayer;         // unsharpened real-pixel picture of the visible part
    private int layerLeft, layerTop;
    private float amount;               // unsharp amount (0 = off)
    private boolean showOriginal;
    private Listener listener;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Runnable previewRunner = this::startPreview;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    public ZoomImageView(Context c) {
        super(c);
        paint.setFilterBitmap(true);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestureDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaleDetector.isInProgress()) return false;
                m.postTranslate(-dx, -dy);
                changed();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (bmp == null) return true;
                if (currentScale() > fitScale * 1.5f) {
                    resetFit();
                } else {
                    float target = Math.min(MAX_SCALE, Math.max(fitScale * 3f, 2f));
                    zoomBy(target / currentScale(), e.getX(), e.getY());
                }
                return true;
            }
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Give the view its picture. pyramid[0] must be the same bitmap as bmp. */
    public void setBitmap(Bitmap b, Bitmap[] levels) {
        bmp = b;
        pyramid = levels;
        sharp = null;
        detailLayer = null;
        detail = null;
        kx = ky = 1f;
        b.setHasMipMap(true);   // smoother when the picture is shown smaller than it really is
        if (getWidth() > 0) resetFit();
        invalidate();
    }

    /** Tell the view the real photo is fullW x fullH pixels and can supply real pixels on request. */
    public void setDetail(int fullW, int fullH, DetailSource src) {
        if (bmp == null || src == null) return;
        this.fullW = fullW;
        this.fullH = fullH;
        this.detail = src;
        kx = fullW / (float) bmp.getWidth();
        ky = fullH / (float) bmp.getHeight();
        removeCallbacks(previewRunner);
        postDelayed(previewRunner, PREVIEW_DELAY_SLIDER);
    }

    public Bitmap getBitmap() {
        return bmp;
    }

    /** Sharpen strength as an unsharp amount (see ImageOps.amountFor). */
    public void setAmount(float a) {
        amount = a;
        gen++;
        removeCallbacks(previewRunner);
        if (a <= 0f) sharp = null;
        postDelayed(previewRunner, PREVIEW_DELAY_SLIDER);
        invalidate();
    }

    /** True while the user holds the "original" button: show the unsharpened picture. */
    public void setShowOriginal(boolean v) {
        showOriginal = v;
        invalidate();
    }

    /** Whole-pixel box of the overview picture that is currently on screen. */
    public Rect visibleSourceRect() {
        if (bmp == null || getWidth() == 0) return new Rect(0, 0, 1, 1);
        Matrix inv = new Matrix();
        if (!m.invert(inv)) return new Rect(0, 0, bmp.getWidth(), bmp.getHeight());
        RectF r = new RectF(0, 0, getWidth(), getHeight());
        inv.mapRect(r);
        int l = Math.max(0, (int) Math.floor(r.left));
        int t = Math.max(0, (int) Math.floor(r.top));
        int rt = Math.min(bmp.getWidth(), (int) Math.ceil(r.right));
        int b = Math.min(bmp.getHeight(), (int) Math.ceil(r.bottom));
        if (rt - l < 8) rt = Math.min(bmp.getWidth(), l + 8);
        if (b - t < 8) b = Math.min(bmp.getHeight(), t + 8);
        if (rt - l < 1 || b - t < 1) return new Rect(0, 0, bmp.getWidth(), bmp.getHeight());
        return new Rect(l, t, rt, b);
    }

    /** Screen pixels per overview pixel. */
    public float currentScale() {
        m.getValues(vals);
        return vals[Matrix.MSCALE_X];
    }

    // -------------------------------------------------------------- gestures

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        gestureDetector.onTouchEvent(e);
        return true;
    }

    private void zoomBy(float factor, float fx, float fy) {
        if (bmp == null) return;
        float s = currentScale();
        float target = Math.max(fitScale, Math.min(Math.max(MAX_SCALE, fitScale), s * factor));
        float f = target / s;
        m.postScale(f, f, fx, fy);
        changed();
    }

    private void resetFit() {
        if (bmp == null || getWidth() == 0 || getHeight() == 0) return;
        fitScale = Math.min(getWidth() / (float) bmp.getWidth(), getHeight() / (float) bmp.getHeight());
        m.setScale(fitScale, fitScale);
        m.postTranslate((getWidth() - bmp.getWidth() * fitScale) / 2f,
                (getHeight() - bmp.getHeight() * fitScale) / 2f);
        changed();
    }

    /** Keep the picture from being dragged out of sight, then refresh. */
    private void changed() {
        clampPosition();
        gen++;
        sharp = null;
        detailLayer = null;
        removeCallbacks(previewRunner);
        postDelayed(previewRunner, PREVIEW_DELAY_MOVE);
        invalidate();
        if (listener != null) listener.onViewChanged();
    }

    private void clampPosition() {
        if (bmp == null) return;
        m.getValues(vals);
        float s = vals[Matrix.MSCALE_X];
        float w = bmp.getWidth() * s, h = bmp.getHeight() * s;
        float tx = vals[Matrix.MTRANS_X], ty = vals[Matrix.MTRANS_Y];
        float nx, ny;
        if (w <= getWidth()) nx = (getWidth() - w) / 2f;
        else nx = Math.min(0f, Math.max(getWidth() - w, tx));
        if (h <= getHeight()) ny = (getHeight() - h) / 2f;
        else ny = Math.min(0f, Math.max(getHeight() - h, ty));
        if (nx != tx || ny != ty) m.postTranslate(nx - tx, ny - ty);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        resetFit();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        gen++;
        exec.shutdownNow();
    }

    // ------------------------------------------------------------ live preview

    private void startPreview() {
        final Bitmap[] levels = pyramid;
        if (bmp == null || levels == null || getWidth() == 0) return;
        final DetailSource ds = detail;
        final Matrix snap = new Matrix(m);
        snap.getValues(vals);
        final float scale = vals[Matrix.MSCALE_X];
        final float kxx = kx, kyy = ky;
        // Real-photo pixels are only worth fetching once we are magnifying beyond the overview's own pixels.
        final boolean useDetail = ds != null && kxx > 1.05f && scale > 1f;
        final float amt = amount;
        if (amt <= 0f && !useDetail) return;
        final int token = ++gen;

        // The part of the picture that is on screen, in screen pixels.
        RectF r = new RectF(0, 0, bmp.getWidth(), bmp.getHeight());
        snap.mapRect(r);
        final int l = Math.max(0, (int) Math.floor(r.left));
        final int t = Math.max(0, (int) Math.floor(r.top));
        final int rt = Math.min(getWidth(), (int) Math.ceil(r.right));
        final int b = Math.min(getHeight(), (int) Math.ceil(r.bottom));
        final int w = rt - l, h = b - t;
        if (w < 3 || h < 3) return;
        final int fw = fullW, fh = fullH;

        try {
            exec.execute(() -> {
                if (token != gen) return;
                try {
                    Bitmap view = null;      // the visible part drawn at screen resolution, not sharpened yet
                    boolean fromDetail = false;
                    if (useDetail) {
                        Matrix inv = new Matrix();
                        if (snap.invert(inv)) {
                            RectF vr = new RectF(l, t, rt, b);
                            inv.mapRect(vr);   // overview pixels
                            Rect want = new Rect(
                                    clampInt((int) Math.floor(vr.left * kxx), 0, fw),
                                    clampInt((int) Math.floor(vr.top * kyy), 0, fh),
                                    clampInt((int) Math.ceil(vr.right * kxx), 0, fw),
                                    clampInt((int) Math.ceil(vr.bottom * kyy), 0, fh));
                            if (want.width() >= 4 && want.height() >= 4) {
                                int s = 1;
                                while (s < 64 && want.width() / (s * 2) >= w && want.height() / (s * 2) >= h) s *= 2;
                                Bitmap dec = ds.decode(want, s);
                                if (dec != null && token == gen) {
                                    Matrix mm = new Matrix(snap);
                                    mm.preScale(1f / kxx, 1f / kyy);
                                    mm.preTranslate(want.left, want.top);
                                    mm.preScale(want.width() / (float) dec.getWidth(),
                                            want.height() / (float) dec.getHeight());
                                    mm.postTranslate(-l, -t);
                                    view = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                                    view.eraseColor(Color.BLACK);
                                    new Canvas(view).drawBitmap(dec, mm, new Paint(Paint.FILTER_BITMAP_FLAG));
                                    fromDetail = true;
                                }
                                if (dec != null) dec.recycle();
                            }
                        }
                    }
                    if (token != gen) return;
                    if (view == null) {
                        if (amt <= 0f) return;
                        // Use a half/quarter-size copy when zoomed out, so small details don't shimmer.
                        int k = 0;
                        float z = scale;
                        while (z < 0.5f && k < levels.length - 1) {
                            z *= 2f;
                            k++;
                        }
                        Matrix mm = new Matrix(snap);
                        mm.preScale((float) (1 << k), (float) (1 << k));
                        mm.postTranslate(-l, -t);
                        view = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        view.eraseColor(Color.BLACK);
                        new Canvas(view).drawBitmap(levels[k], mm, new Paint(Paint.FILTER_BITMAP_FLAG));
                    }
                    Bitmap sharpOut = null;
                    if (amt > 0f && token == gen) {
                        int[] px = new int[w * h];
                        view.getPixels(px, 0, w, 0, 0, w, h);
                        // Blur width is measured in real photo pixels, so convert the zoom to that unit.
                        float zEff = fromDetail ? scale / kxx : scale;
                        ImageOps.sharpen(px, w, h, amt, ImageOps.sigmaFor(zEff), 2);
                        sharpOut = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
                    }
                    final Bitmap layer = fromDetail ? view : null;
                    final Bitmap sh = sharpOut;
                    post(() -> {
                        if (token == gen) {
                            detailLayer = layer;
                            sharp = sh;
                            layerLeft = l;
                            layerTop = t;
                            invalidate();
                        }
                    });
                } catch (OutOfMemoryError | RuntimeException ignored) {
                    // No preview this time; saving still works.
                }
            });
        } catch (RuntimeException ignored) {
            // executor already shut down (screen closing)
        }
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Color.BLACK);
        if (bmp == null) return;
        c.save();
        c.concat(m);
        c.drawBitmap(bmp, 0, 0, paint);
        c.restore();
        Bitmap top = showOriginal ? detailLayer : (sharp != null ? sharp : detailLayer);
        if (top != null) c.drawBitmap(top, layerLeft, layerTop, null);
    }
}

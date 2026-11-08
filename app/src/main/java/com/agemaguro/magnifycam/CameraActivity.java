package com.agemaguro.magnifycam;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Size;
import android.util.SizeF;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.lifecycle.LiveData;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * First screen: live camera with pinch / slider zoom, tap to focus, torch, and a shutter.
 * After a shot (or a picture picked from the gallery) the EditorActivity opens.
 */
public class CameraActivity extends ComponentActivity {
    private static final int ACCENT = 0xFF7FB2FF;

    private PreviewView previewView;
    private TextView zoomLabel, torchBtn, detailBtn, lensInfo, message;
    private LinearLayout lensRow;
    private final List<TextView> lensPills = new ArrayList<>();
    private SeekBar zoomBar;
    private View shutter, focusRing;

    private ProcessCameraProvider provider;
    private Camera camera;
    private ImageCapture imageCapture;
    private LiveData<ZoomState> zoomLive;
    private boolean torchOn;
    private boolean maxDetail = true;
    private SharedPreferences prefs;
    private Updater updater;

    /** One back camera (lens) of the phone. */
    private static class Lens {
        String id;
        float ratio = 1f;     // magnification compared with the main camera
        float megapixels;     // largest photo the phone lets this app take with the lens
        float sensorMp;       // pixels on the sensor itself (can be much more than the app is allowed to use)
        String label;
    }

    private final List<Lens> lenses = new ArrayList<>();
    private Lens selected;    // null = the phone's default back camera
    private boolean capturing;
    private boolean starting;
    private long lastPinch;

    private ActivityResultLauncher<String> permissionLauncher;
    private ActivityResultLauncher<String> pickLauncher;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("magnifycam", MODE_PRIVATE);
        maxDetail = prefs.getBoolean("maxDetail", true);
        deleteOldShots();
        buildUi();
        updater = new Updater(this);
        updater.checkOnOpen();

        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) startCamera();
            else showMessage("Camera access is off.\nTap here to open the app settings and allow Camera.\n\nYou can still use \"Gallery\" to open a picture.");
        });
        pickLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), this::onPicked);

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the settings screen with the permission now allowed?
        if (camera == null && !starting
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (updater != null) updater.release();
    }

    // ---------------------------------------------------------------------- UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        setupTouch();

        message = new TextView(this);
        message.setTextColor(0xFFE8ECF3);
        message.setTextSize(16);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(32), dp(32), dp(32), dp(32));
        message.setVisibility(View.GONE);
        message.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        root.addView(message, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        focusRing = new View(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(dp(2), ACCENT);
        ring.setColor(Color.TRANSPARENT);
        focusRing.setBackground(ring);
        focusRing.setVisibility(View.GONE);
        root.addView(focusRing, new FrameLayout.LayoutParams(dp(64), dp(64)));

        // top bar: zoom readout + torch
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(8), dp(12), dp(8));
        zoomLabel = pill("1.0×");
        top.addView(zoomLabel);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        detailBtn = pill(maxDetail ? "Detail: Max" : "Detail: Standard");
        detailBtn.setOnClickListener(v -> {
            maxDetail = !maxDetail;
            prefs.edit().putBoolean("maxDetail", maxDetail).apply();
            detailBtn.setText(maxDetail ? "Detail: Max" : "Detail: Standard");
            if (provider != null) bindCamera();
        });
        top.addView(detailBtn);
        View gap = new View(this);
        top.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
        torchBtn = pill("Torch: off");
        torchBtn.setVisibility(View.GONE);
        torchBtn.setOnClickListener(v -> {
            if (camera == null) return;
            torchOn = !torchOn;
            camera.getCameraControl().enableTorch(torchOn);
            torchBtn.setText(torchOn ? "Torch: on" : "Torch: off");
        });
        top.addView(torchBtn);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // bottom: zoom slider, then gallery / shutter
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(20), dp(8), dp(20), dp(20));
        lensRow = new LinearLayout(this);
        lensRow.setOrientation(LinearLayout.HORIZONTAL);
        lensRow.setGravity(Gravity.CENTER);
        lensRow.setVisibility(View.GONE);
        bottom.addView(lensRow, new LinearLayout.LayoutParams(-1, -2));
        lensInfo = new TextView(this);
        lensInfo.setTextColor(0xFFB8C0CF);
        lensInfo.setTextSize(12);
        lensInfo.setGravity(Gravity.CENTER);
        lensInfo.setPadding(0, dp(4), 0, 0);
        bottom.addView(lensInfo, new LinearLayout.LayoutParams(-1, -2));
        zoomBar = new SeekBar(this);
        zoomBar.setMax(100);
        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                if (fromUser && camera != null) camera.getCameraControl().setLinearZoom(p / 100f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        bottom.addView(zoomBar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, 0);

        FrameLayout left = new FrameLayout(this);
        TextView gallery = pill("Gallery");
        gallery.setOnClickListener(v -> pickLauncher.launch("image/*"));
        left.addView(gallery, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL | Gravity.START));
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        FrameLayout shutterFrame = new FrameLayout(this);
        GradientDrawable outer = new GradientDrawable();
        outer.setShape(GradientDrawable.OVAL);
        outer.setStroke(dp(4), Color.WHITE);
        outer.setColor(Color.TRANSPARENT);
        shutterFrame.setBackground(outer);
        shutter = new View(this);
        GradientDrawable inner = new GradientDrawable();
        inner.setShape(GradientDrawable.OVAL);
        inner.setColor(Color.WHITE);
        shutter.setBackground(inner);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER);
        shutterFrame.addView(shutter, ip);
        shutterFrame.setOnClickListener(v -> takePicture());
        row.addView(shutterFrame, new LinearLayout.LayoutParams(dp(78), dp(78)));

        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        bottom.addView(row);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);
    }

    private TextView pill(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(0xFFE8ECF3);
        v.setTextSize(15);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(22));
        g.setColor(0x99000000);
        v.setBackground(g);
        return v;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void showMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ camera

    private void startCamera() {
        if (starting) return;
        starting = true;
        message.setVisibility(View.GONE);
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                provider = future.get();
                buildLenses();
                bindCamera();
            } catch (Exception e) {
                provider = null;
                starting = false;
                showMessage("Could not start the camera: " + e.getMessage());
            }
        }, getMainExecutor());
    }

    private ResolutionSelector selector(boolean forCapture) {
        ResolutionSelector.Builder b = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY);
        if (forCapture && maxDetail) {
            b.setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY);
            b.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE);
        }
        return b.build();
    }

    private CameraSelector cameraSelector() {
        if (selected == null) return CameraSelector.DEFAULT_BACK_CAMERA;
        final String id = selected.id;
        return new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .addCameraFilter(infos -> {
                    List<CameraInfo> out = new ArrayList<>();
                    for (CameraInfo ci : infos) {
                        if (id.equals(Camera2CameraInfo.from(ci).getCameraId())) out.add(ci);
                    }
                    return out;
                })
                .build();
    }

    private void bindCamera() {
        provider.unbindAll();
        Preview preview = new Preview.Builder().setResolutionSelector(selector(false)).build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        imageCapture = new ImageCapture.Builder()
                .setResolutionSelector(selector(true))
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build();
        try {
            camera = provider.bindToLifecycle(this, cameraSelector(), preview, imageCapture);
        } catch (Exception e) {
            if (maxDetail) {
                // This phone/lens can't do max detail together with the preview: fall back to standard.
                maxDetail = false;
                detailBtn.setText("Detail: Standard");
                Toast.makeText(this, "Max detail isn't available on this lens; using standard.", Toast.LENGTH_LONG).show();
                bindCamera();
            } else if (selected != null) {
                selected = null;   // that lens would not open; go back to the main camera
                bindCamera();
            } else {
                showMessage("Could not start the camera: " + e.getMessage());
            }
            return;
        }
        if (zoomLive != null) zoomLive.removeObservers(this);
        zoomLive = camera.getCameraInfo().getZoomState();
        final float lensRatio = selected == null ? 1f : selected.ratio;
        zoomLive.observe(this, zs -> {
            zoomLabel.setText(String.format(Locale.US, "%.1f×", zs.getZoomRatio() * lensRatio));
            zoomBar.setProgress(Math.round(zs.getLinearZoom() * 100f));
        });
        torchBtn.setVisibility(camera.getCameraInfo().hasFlashUnit() ? View.VISIBLE : View.GONE);
        torchOn = false;
        torchBtn.setText("Torch: off");
        styleLensPills();
    }

    // -------------------------------------------------------------- lenses

    /** List the phone's back cameras (wide, ultra-wide, telephoto...) and add a button for each. */
    private void buildLenses() {
        lenses.clear();
        lensPills.clear();
        lensRow.removeAllViews();
        try {
            List<CameraInfo> back = CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.getAvailableCameraInfos());
            float baseEq = 0f;
            List<Lens> found = new ArrayList<>();
            for (CameraInfo ci : back) {
                Camera2CameraInfo c2 = Camera2CameraInfo.from(ci);
                Lens l = new Lens();
                l.id = c2.getCameraId();
                float eq = 0f;
                float[] fl = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                SizeF ps = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                if (fl != null && fl.length > 0 && ps != null) {
                    // 35 mm-equivalent focal length: how "zoomed in" this lens is
                    eq = fl[0] * 43.27f / (float) Math.hypot(ps.getWidth(), ps.getHeight());
                }
                if (found.isEmpty()) baseEq = eq;   // the first one is the phone's default (main) camera
                l.ratio = (eq > 0f && baseEq > 0f) ? eq / baseEq : 1f;
                l.megapixels = maxMegapixels(c2);
                l.sensorMp = sensorMegapixels(c2);
                found.add(l);
            }
            // sort by magnification, drop near-duplicates
            java.util.Collections.sort(found, (a, b) -> Float.compare(a.ratio, b.ratio));
            for (Lens l : found) {
                if (!lenses.isEmpty()) {
                    Lens prev = lenses.get(lenses.size() - 1);
                    if (Math.abs(l.ratio - prev.ratio) / prev.ratio < 0.12f) {
                        if (l.megapixels > prev.megapixels
                                || (l.megapixels == prev.megapixels && l.sensorMp > prev.sensorMp)) {
                            lenses.set(lenses.size() - 1, l);
                        }
                        continue;
                    }
                }
                lenses.add(l);
            }
            for (Lens l : lenses) {
                float r = Math.round(l.ratio * 10f) / 10f;
                l.label = (r == Math.round(r)) ? String.format(Locale.US, "%d×", Math.round(r)) : String.format(Locale.US, "%.1f×", r);
            }
        } catch (Throwable t) {
            lenses.clear();
        }
        if (lenses.size() > 1) {
            for (final Lens l : lenses) {
                TextView pill = pill(l.label);
                pill.setOnClickListener(v -> {
                    selected = l;
                    if (provider != null) bindCamera();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.leftMargin = lp.rightMargin = dp(4);
                lensRow.addView(pill, lp);
                lensPills.add(pill);
            }
            lensRow.setVisibility(View.VISIBLE);
        } else {
            lensRow.setVisibility(View.GONE);
        }
    }

    /** Biggest photo (in megapixels) this lens says it can take. */
    private static float maxMegapixels(Camera2CameraInfo c2) {
        long best = 0;
        StreamConfigurationMap map = c2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            best = Math.max(best, biggest(map.getOutputSizes(ImageFormat.JPEG)));
            best = Math.max(best, biggest(map.getHighResolutionOutputSizes(ImageFormat.JPEG)));
        }
        if (Build.VERSION.SDK_INT >= 31) {
            StreamConfigurationMap mx = c2.getCameraCharacteristic(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
            if (mx != null) {
                best = Math.max(best, biggest(mx.getOutputSizes(ImageFormat.JPEG)));
                best = Math.max(best, biggest(mx.getHighResolutionOutputSizes(ImageFormat.JPEG)));
            }
        }
        return best / 1e6f;
    }

    /** Pixels on the sensor itself, whether or not the phone lets apps use them all. */
    private static float sensorMegapixels(Camera2CameraInfo c2) {
        long best = 0;
        try {
            Size a = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            if (a != null) best = Math.max(best, (long) a.getWidth() * a.getHeight());
            if (Build.VERSION.SDK_INT >= 31) {
                Size m = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION);
                if (m != null) best = Math.max(best, (long) m.getWidth() * m.getHeight());
            }
        } catch (Throwable ignored) {
            // some phones refuse to say; then we just do not show the sensor size
        }
        return best / 1e6f;
    }

    private static long biggest(Size[] sizes) {
        long best = 0;
        if (sizes != null) for (Size z : sizes) best = Math.max(best, (long) z.getWidth() * z.getHeight());
        return best;
    }

    private void styleLensPills() {
        for (int i = 0; i < lensPills.size(); i++) {
            boolean on = (selected == null ? i == indexOfDefault() : lenses.get(i) == selected);
            ((GradientDrawable) lensPills.get(i).getBackground()).setColor(on ? ACCENT : 0x99000000);
            lensPills.get(i).setTextColor(on ? 0xFF0B1220 : 0xFFE8ECF3);
        }
        Lens cur = selected != null ? selected : (lenses.isEmpty() ? null : lenses.get(indexOfDefault()));
        if (cur == null) {
            lensInfo.setText("");
        } else {
            if (cur.sensorMp >= cur.megapixels * 1.5f) {
                // e.g. a 200 MP sensor that the phone only lets apps use as a binned ~12 MP picture
                lensInfo.setText(String.format(Locale.US, "Sensor %.0f MP · this app can take up to %.0f MP",
                        cur.sensorMp, cur.megapixels));
            } else {
                lensInfo.setText(String.format(Locale.US, "This lens can take photos up to %.0f MP", cur.megapixels));
            }
        }
    }

    /** Index of the main (1x) lens in the sorted list. */
    private int indexOfDefault() {
        int best = 0;
        float diff = Float.MAX_VALUE;
        for (int i = 0; i < lenses.size(); i++) {
            float d = Math.abs(lenses.get(i).ratio - 1f);
            if (d < diff) {
                diff = d;
                best = i;
            }
        }
        return best;
    }

    /** Pinch to zoom, tap to focus. */
    private void setupTouch() {
        final ScaleGestureDetector scale = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        if (camera == null) return true;
                        ZoomState zs = camera.getCameraInfo().getZoomState().getValue();
                        if (zs == null) return true;
                        float r = zs.getZoomRatio() * d.getScaleFactor();
                        r = Math.max(zs.getMinZoomRatio(), Math.min(zs.getMaxZoomRatio(), r));
                        camera.getCameraControl().setZoomRatio(r);
                        lastPinch = SystemClock.uptimeMillis();
                        return true;
                    }
                });
        final GestureDetector tap = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (SystemClock.uptimeMillis() - lastPinch > 400) focusAt(e.getX(), e.getY());
                return true;
            }
        });
        previewView.setOnTouchListener((v, e) -> {
            scale.onTouchEvent(e);
            tap.onTouchEvent(e);
            return true;
        });
    }

    private void focusAt(float x, float y) {
        if (camera == null) return;
        MeteringPoint p = previewView.getMeteringPointFactory().createPoint(x, y);
        FocusMeteringAction a = new FocusMeteringAction.Builder(p,
                FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(4, TimeUnit.SECONDS)
                .build();
        camera.getCameraControl().startFocusAndMetering(a);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) focusRing.getLayoutParams();
        lp.leftMargin = Math.round(x) - lp.width / 2;
        lp.topMargin = Math.round(y) - lp.height / 2;
        focusRing.setLayoutParams(lp);
        focusRing.setVisibility(View.VISIBLE);
        focusRing.removeCallbacks(hideRing);
        focusRing.postDelayed(hideRing, 900);
    }

    private final Runnable hideRing = () -> focusRing.setVisibility(View.GONE);

    // ----------------------------------------------------------------- capture

    private void takePicture() {
        if (capturing || imageCapture == null) {
            if (imageCapture == null) Toast.makeText(this, "The camera is not ready yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        capturing = true;
        shutter.setAlpha(0.4f);
        final File file = new File(getCacheDir(), "shot_" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions opts = new ImageCapture.OutputFileOptions.Builder(file).build();
        imageCapture.takePicture(opts, getMainExecutor(), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(ImageCapture.OutputFileResults results) {
                capturing = false;
                shutter.setAlpha(1f);
                openEditor(file.getAbsolutePath());
            }

            @Override
            public void onError(ImageCaptureException e) {
                capturing = false;
                shutter.setAlpha(1f);
                Toast.makeText(CameraActivity.this, "Could not take the picture: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    /** A picture chosen from the gallery: copy it to our private cache, then edit it. */
    private void onPicked(Uri uri) {
        if (uri == null) return;
        final File file = new File(getCacheDir(), "shot_" + System.currentTimeMillis() + ".jpg");
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new java.io.FileOutputStream(file)) {
                if (in == null) throw new java.io.IOException("could not open it");
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Could not open that picture: " + e.getMessage(), Toast.LENGTH_LONG).show());
                return;
            }
            runOnUiThread(() -> openEditor(file.getAbsolutePath()));
        }).start();
    }

    private void openEditor(String path) {
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_PATH, path);
        startActivity(i);
    }

    /** Throw away leftover temporary pictures from earlier runs. */
    private void deleteOldShots() {
        File[] files = getCacheDir().listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 60 * 60 * 1000L;
        for (File f : files) {
            if (f.getName().startsWith("shot_") && f.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}

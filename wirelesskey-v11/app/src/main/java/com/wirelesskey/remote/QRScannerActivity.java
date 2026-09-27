package com.wirelesskey.remote;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QRScannerActivity extends ComponentActivity {
    public static final String EXTRA_QR_VALUE = "wirelesskey_qr_value";
    private static final int CAMERA_REQUEST = 813;
    private static final int IMAGE_REQUEST = 814;

    private PreviewView previewView;
    private TextView status;
    private ProcessCameraProvider cameraProvider;
    private BarcodeScanner barcodeScanner;
    private ExecutorService cameraExecutor;
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        cameraExecutor = Executors.newSingleThreadExecutor();

        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        barcodeScanner = BarcodeScanning.getClient(options);

        setContentView(buildUi());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(18), dp(14), dp(18), dp(14));
        overlay.setGravity(Gravity.CENTER_HORIZONTAL);
        overlay.setBackgroundColor(0x22000000);

        TextView title = new TextView(this);
        title.setText("Scan WirelessKey QR");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        overlay.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        status = new TextView(this);
        status.setText("Point the camera at the QR shown on your PC");
        status.setTextColor(0xffdbeafe);
        status.setTextSize(10);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(30));
        overlay.addView(status, statusLp);

        View spacer = new View(this);
        overlay.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);

        Button choose = makeButton("Choose QR image");
        choose.setOnClickListener(v -> openImagePicker());

        Button retry = makeButton("Restart camera");
        retry.setOnClickListener(v -> restartCamera());

        Button cancel = makeButton("Cancel");
        cancel.setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        actions.addView(choose, new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        retryLp.leftMargin = dp(8);
        actions.addView(retry, retryLp);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        cancelLp.leftMargin = dp(8);
        actions.addView(cancel, cancelLp);

        overlay.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        return root;
    }

    private Button makeButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(9);
        b.setBackgroundColor(0xff1e3a5f);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private void setStatus(String text, int color) {
        runOnUiThread(() -> {
            if (status != null) {
                status.setText(text);
                status.setTextColor(color);
            }
        });
    }

    private void startCamera() {
        if (completed.get() || isFinishing()) return;

        setStatus("Starting camera…", 0xffbfdbfe);

        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(this);

        providerFuture.addListener(() -> {
            try {
                if (completed.get() || isFinishing()) return;

                cameraProvider = providerFuture.get();
                cameraProvider.unbindAll();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                analysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    if (completed.get()) {
                        imageProxy.close();
                        return;
                    }

                    if (!processing.compareAndSet(false, true)) {
                        imageProxy.close();
                        return;
                    }

                    android.media.Image mediaImage = imageProxy.getImage();
                    if (mediaImage == null) {
                        processing.set(false);
                        imageProxy.close();
                        return;
                    }

                    InputImage input = InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.getImageInfo().getRotationDegrees());

                    barcodeScanner.process(input)
                            .addOnSuccessListener(this::handleBarcodes)
                            .addOnFailureListener(error ->
                                    setStatus("Scanning… hold the QR steady", 0xffffd166))
                            .addOnCompleteListener(task -> {
                                processing.set(false);
                                imageProxy.close();
                            });
                });

                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis);

                setStatus("Point the camera at the WirelessKey QR", 0xffdbeafe);

            } catch (SecurityException security) {
                setStatus("Camera permission unavailable", 0xffff6b6b);
            } catch (Exception error) {
                setStatus("Camera failed to start · use Choose QR image", 0xffff6b6b);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void restartCamera() {
        completed.set(false);
        processing.set(false);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
            return;
        }

        try {
            if (cameraProvider != null) cameraProvider.unbindAll();
        } catch (Exception ignored) {
        }
        startCamera();
    }

    private void handleBarcodes(List<Barcode> barcodes) {
        if (barcodes == null || barcodes.isEmpty() || completed.get()) return;

        for (Barcode barcode : barcodes) {
            String raw = barcode.getRawValue();
            if (raw == null || raw.trim().isEmpty()) continue;

            if (isWirelessKeyPayload(raw)) {
                finishWithResult(raw.trim());
                return;
            }
        }

        setStatus("QR found, but it is not a WirelessKey pairing code", 0xffffd166);
    }

    private boolean isWirelessKeyPayload(String raw) {
        try {
            JSONObject obj = new JSONObject(raw);
            return "wirelesskey_pair".equals(obj.optString("type", ""))
                    && !obj.optString("fingerprint", "").isEmpty()
                    && !obj.optString("code", "").isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void finishWithResult(String raw) {
        if (!completed.compareAndSet(false, true)) return;

        try {
            if (cameraProvider != null) cameraProvider.unbindAll();
        } catch (Exception ignored) {
        }

        Intent data = new Intent();
        data.putExtra(EXTRA_QR_VALUE, raw);
        setResult(Activity.RESULT_OK, data);
        finish();
    }

    private void openImagePicker() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        startActivityForResult(pick, IMAGE_REQUEST);
    }

    private void decodeImage(Uri uri) {
        setStatus("Reading QR image…", 0xffbfdbfe);
        try {
            InputImage image = InputImage.fromFilePath(this, uri);
            barcodeScanner.process(image)
                    .addOnSuccessListener(this::handleBarcodes)
                    .addOnFailureListener(error ->
                            setStatus("Could not read a QR from that image", 0xffff6b6b));
        } catch (Exception error) {
            setStatus("Could not open that image", 0xffff6b6b);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == IMAGE_REQUEST && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            decodeImage(data.getData());
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != CAMERA_REQUEST) return;

        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            setStatus("Camera denied · use Choose QR image", 0xffff6b6b);
        }
    }

    @Override
    protected void onDestroy() {
        completed.set(true);

        try {
            if (cameraProvider != null) cameraProvider.unbindAll();
        } catch (Exception ignored) {
        }

        if (barcodeScanner != null) barcodeScanner.close();
        if (cameraExecutor != null) cameraExecutor.shutdownNow();

        super.onDestroy();
    }
}

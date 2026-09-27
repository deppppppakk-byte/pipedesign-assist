package com.wirelesskey.remote;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
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

    private GradientDrawable surface(int color, float radiusDp, int strokeColor) {
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if(strokeColor!=0)d.setStroke(dp(1),strokeColor);
        return d;
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

        View shade=new View(this);
        shade.setBackgroundColor(0x33000000);
        root.addView(shade,new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        ScanFrameView frame=new ScanFrameView(this);
        FrameLayout.LayoutParams frameLp=new FrameLayout.LayoutParams(
                dp(300),dp(210),Gravity.CENTER);
        root.addView(frame,frameLp);

        LinearLayout chrome = new LinearLayout(this);
        chrome.setOrientation(LinearLayout.VERTICAL);
        chrome.setPadding(dp(16), dp(12), dp(16), dp(14));
        chrome.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout header=new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12),0,dp(8),0);
        header.setBackground(surface(0xdd141920,14,0x5537414f));

        LinearLayout copy=new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("WirelessKey");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setTypeface(null, android.graphics.Typeface.BOLD);

        TextView subtitle=new TextView(this);
        subtitle.setText("QR PAIRING");
        subtitle.setTextColor(0xff7fb7ff);
        subtitle.setTextSize(7);
        subtitle.setLetterSpacing(0.10f);
        subtitle.setTypeface(null,android.graphics.Typeface.BOLD);

        copy.addView(title,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,0,1.2f));
        copy.addView(subtitle,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,0,0.8f));
        header.addView(copy,new LinearLayout.LayoutParams(0,dp(42),1f));

        Button cancelTop=makeButton("Close");
        cancelTop.setOnClickListener(v->{
            setResult(Activity.RESULT_CANCELED);
            finish();
        });
        header.addView(cancelTop,new LinearLayout.LayoutParams(dp(62),dp(32)));

        chrome.addView(header,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(46)));

        status = new TextView(this);
        status.setText("Align the PC pairing QR inside the frame");
        status.setTextColor(0xffd9e7f7);
        status.setTextSize(9);
        status.setGravity(Gravity.CENTER);
        status.setBackground(surface(0xaa11161d,12,0x44343f4b));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(30));
        statusLp.topMargin=dp(8);
        statusLp.leftMargin=dp(24);
        statusLp.rightMargin=dp(24);
        chrome.addView(status, statusLp);

        View spacer = new View(this);
        chrome.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actionDock=new LinearLayout(this);
        actionDock.setOrientation(LinearLayout.VERTICAL);
        actionDock.setPadding(dp(10),dp(8),dp(10),dp(8));
        actionDock.setBackground(surface(0xe6151a21,16,0x5537414f));

        TextView help=new TextView(this);
        help.setText("Camera not ideal? Pair from a saved QR screenshot.");
        help.setTextColor(0xff9baabc);
        help.setTextSize(8);
        help.setGravity(Gravity.CENTER_VERTICAL);
        actionDock.addView(help,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(24)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);

        Button choose = makeButton("Choose QR image");
        choose.setBackground(surface(0xff1f4f83,11,0));
        choose.setOnClickListener(v -> openImagePicker());

        Button retry = makeButton("Restart camera");
        retry.setOnClickListener(v -> restartCamera());

        Button cancel = makeButton("Cancel");
        cancel.setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        actions.addView(choose, new LinearLayout.LayoutParams(0, dp(38), 1.25f));
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        retryLp.leftMargin = dp(7);
        actions.addView(retry, retryLp);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(38), 0.8f);
        cancelLp.leftMargin = dp(7);
        actions.addView(cancel, cancelLp);

        actionDock.addView(actions,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(38)));

        chrome.addView(actionDock,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(72)));

        root.addView(chrome, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        return root;
    }

    private Button makeButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(0xffedf3fb);
        b.setTextSize(8.5f);
        b.setBackground(surface(0xff222a34,11,0));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(8),0,dp(8),0);
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

    @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
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

    private static final class ScanFrameView extends View {
        private final Paint corner=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wash=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        ScanFrameView(android.content.Context context){
            super(context);
            density=getResources().getDisplayMetrics().density;
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas canvas){
            super.onDraw(canvas);

            float pad=6f*density;
            RectF rect=new RectF(pad,pad,getWidth()-pad,getHeight()-pad);

            wash.setColor(0x221d79ff);
            canvas.drawRoundRect(rect,18f*density,18f*density,wash);

            corner.setColor(0xff7fb7ff);
            corner.setStyle(Paint.Style.STROKE);
            corner.setStrokeWidth(3f*density);
            corner.setStrokeCap(Paint.Cap.ROUND);

            float len=34f*density;
            float l=rect.left, t=rect.top, r=rect.right, b=rect.bottom;

            canvas.drawLine(l,t,l+len,t,corner);
            canvas.drawLine(l,t,l,t+len,corner);
            canvas.drawLine(r,t,r-len,t,corner);
            canvas.drawLine(r,t,r,t+len,corner);
            canvas.drawLine(l,b,l+len,b,corner);
            canvas.drawLine(l,b,l,b-len,corner);
            canvas.drawLine(r,b,r-len,b,corner);
            canvas.drawLine(r,b,r,b-len,corner);
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

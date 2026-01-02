package com.example.smartguiderepo;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.tensorflow.lite.task.vision.detector.Detection;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DetectionActivity extends AppCompatActivity {

    private PreviewView cameraPreview;
    private OverlayView overlayView;
    private TextView tvTitle;
    private ImageView btnBack, btnMicOverlay;

    private ObjectDetectorHelper detectorHelper;
    private ExecutorService cameraExecutor;
    private TextToSpeech tts;
    private Camera camera;

    private String currentMode = "General";
    private String targetObject = "";
    private long lastSpeakTime = 0;
    private boolean isDetecting = true;
    private boolean isTorchOn = false;
    private long lastBrightnessCheck = 0;

    private static final int VOICE_REQ_CODE = 202;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detection);

        cameraPreview = findViewById(R.id.cameraPreview);
        overlayView = findViewById(R.id.overlay);
        tvTitle = findViewById(R.id.tvTitle);
        btnBack = findViewById(R.id.btnBack);
        btnMicOverlay = findViewById(R.id.btnMicOverlay);

        btnMicOverlay.setOnClickListener(v -> {
            isDetecting = false;
            speak("Listening");
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            startActivityForResult(intent, VOICE_REQ_CODE);
        });

        if (getIntent().hasExtra("MODE")) currentMode = getIntent().getStringExtra("MODE");
        if (getIntent().hasExtra("TARGET")) targetObject = getIntent().getStringExtra("TARGET").toLowerCase();

        tvTitle.setText(currentMode.equalsIgnoreCase("Finder") ? "Finding: " + targetObject : "Smart Guide Vision");
        btnBack.setOnClickListener(v -> finish());

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                speak(currentMode.equalsIgnoreCase("Finder") ? "Searching for " + targetObject : "Detection started");
            }
        });

        // Corrected initialization
        detectorHelper = new ObjectDetectorHelper(this);
        cameraExecutor = Executors.newSingleThreadExecutor();

        if (allPermissionsGranted()) startCamera();
        else ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 101);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    if (!isDetecting || detectorHelper == null) {
                        imageProxy.close();
                        return;
                    }

                    checkBrightness(imageProxy);

                    // --- FIX: Run UI-related tasks on the Main Thread ---
                    runOnUiThread(() -> {
                        Bitmap bitmap = cameraPreview.getBitmap();
                        if (bitmap != null) {
                            // Move AI work back to the background thread to keep UI smooth
                            cameraExecutor.execute(() -> {
                                List<Detection> results = detectorHelper.detect(bitmap);
                                List<YoloDetector.BoundingBox> mappedResults = new ArrayList<>();

                                if (results != null) {
                                    float bw = bitmap.getWidth();
                                    float bh = bitmap.getHeight();

                                    for (Detection det : results) {
                                        String label = det.getCategories().get(0).getLabel();
                                        float score = det.getCategories().get(0).getScore();
                                        RectF rawBox = det.getBoundingBox();

                                        RectF normalizedBox = new RectF(
                                                rawBox.left / bw, rawBox.top / bh,
                                                rawBox.right / bw, rawBox.bottom / bh
                                        );

                                        if (currentMode.equalsIgnoreCase("Finder")) {
                                            if (label.toLowerCase().contains(targetObject)) {
                                                mappedResults.add(new YoloDetector.BoundingBox(label, score, normalizedBox));
                                            }
                                        } else if (label.equals("person") || label.equals("car") || label.equals("chair")) {
                                            mappedResults.add(new YoloDetector.BoundingBox(label, score, normalizedBox));
                                        }
                                    }
                                }

                                // Update UI with detections
                                runOnUiThread(() -> {
                                    overlayView.setDetections(mappedResults);
                                    processFeedback(mappedResults);
                                });
                            });
                        }
                    });
                    imageProxy.close();
                });

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e("Camera", "Binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // Other methods (checkBrightness, toggleTorch, speak, etc.) remain the same
    private void processFeedback(List<YoloDetector.BoundingBox> results) {
        if (results.isEmpty()) return;
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSpeakTime > 3000) {
            String label = results.get(0).label;
            speak(currentMode.equalsIgnoreCase("Finder") ? "Found " + label : "Caution, " + label + " ahead");
            lastSpeakTime = currentTime;
        }
    }

    private void speak(String text) {
        if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    private void checkBrightness(androidx.camera.core.ImageProxy image) {
        long now = System.currentTimeMillis();
        if (now - lastBrightnessCheck < 1000) return;
        lastBrightnessCheck = now;
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        long sum = 0;
        for (int i = 0; i < data.length; i += 20) sum += (data[i] & 0xFF);
        long average = sum / (data.length / 20);
        if (average < 50 && !isTorchOn) toggleTorch(true);
        else if (average > 110 && isTorchOn) toggleTorch(false);
    }

    private void toggleTorch(boolean status) {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(status);
            isTorchOn = status;
        }
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}
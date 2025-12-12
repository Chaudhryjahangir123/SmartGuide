package com.example.smartguiderepo;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
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

    private YoloDetector yoloDetector;
    private ExecutorService cameraExecutor;
    private TextToSpeech tts;
    private Camera camera; // Control for Torch

    private String currentMode = "General";
    private String targetObject = "";
    private long lastSpeakTime = 0;
    private boolean isDetecting = true;

    // Torch Variables
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

        if (getIntent().hasExtra("MODE")) {
            currentMode = getIntent().getStringExtra("MODE");
        }
        if (getIntent().hasExtra("TARGET")) {
            targetObject = getIntent().getStringExtra("TARGET").toLowerCase();
        }

        if (currentMode.equalsIgnoreCase("Finder")) {
            tvTitle.setText("Finding: " + targetObject);
        } else {
            tvTitle.setText("Smart Guide Vision");
        }

        btnBack.setOnClickListener(v -> finish());

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                String startMsg = currentMode.equalsIgnoreCase("Finder") ? "Searching for " + targetObject : "Detection started";
                speak(startMsg);
            }
        });

        try {
            yoloDetector = new YoloDetector(this, "best_float32.tflite");
        } catch (Exception e) {
            Toast.makeText(this, "Error loading model", Toast.LENGTH_LONG).show();
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 101);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        isDetecting = true;

        if (requestCode == VOICE_REQ_CODE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            String command = result.get(0).toLowerCase();

            if (command.contains("stop")) {
                finish();
            } else if (command.contains("find")) {
                currentMode = "Finder";
                targetObject = command.replace("find", "").trim();
                tvTitle.setText("Finding: " + targetObject);
                speak("Now looking for " + targetObject);
            } else {
                speak("Command not understood");
            }
        }
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
                    if (!isDetecting || yoloDetector == null) {
                        imageProxy.close();
                        return;
                    }

                    // --- NEW: Check Brightness ---
                    checkBrightness(imageProxy);

                    try {
                        runOnUiThread(() -> {
                            Bitmap bitmap = cameraPreview.getBitmap();
                            if (bitmap != null) {
                                List<YoloDetector.BoundingBox> results = yoloDetector.detect(bitmap);
                                List<YoloDetector.BoundingBox> filteredResults = new ArrayList<>();

                                if (currentMode.equalsIgnoreCase("Finder")) {
                                    for (YoloDetector.BoundingBox box : results) {
                                        if (box.label.toLowerCase().contains(targetObject)) {
                                            filteredResults.add(box);
                                        }
                                    }
                                } else {
                                    filteredResults = results;
                                }

                                overlayView.setDetections(filteredResults);
                                processFeedback(filteredResults);
                            }
                        });
                    } catch (Exception e) {
                        Log.e("Camera", "Error", e);
                    } finally {
                        imageProxy.close();
                    }
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                // Capture camera instance to control torch
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e("Camera", "Binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // --- NEW: Brightness Logic ---
    private void checkBrightness(androidx.camera.core.ImageProxy image) {
        long now = System.currentTimeMillis();
        // Check only every 1 second to save battery
        if (now - lastBrightnessCheck < 1000) return;
        lastBrightnessCheck = now;

        // Calculate average pixel intensity of the Y (Luminance) plane
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);

        long sum = 0;
        // Sample every 20th pixel to speed up calculation
        for (int i = 0; i < data.length; i += 20) {
            sum += (data[i] & 0xFF);
        }
        long average = sum / (data.length / 20);

        // Logic: < 50 is Dark, > 110 is Bright
        if (average < 50 && !isTorchOn) {
            toggleTorch(true);
        } else if (average > 110 && isTorchOn) {
            toggleTorch(false);
        }
    }

    private void toggleTorch(boolean status) {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(status);
            isTorchOn = status;
            speak(status ? "Light On" : "Light Off");
        }
    }

    private void processFeedback(List<YoloDetector.BoundingBox> results) {
        if (results.isEmpty()) return;

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSpeakTime > 2500) {
            YoloDetector.BoundingBox bestObj = results.get(0);
            String label = bestObj.label;

            if (!AppSettings.isEnglish) {
                label = translateToUrdu(label);
            }

            if (currentMode.equalsIgnoreCase("Finder")) {
                speak("Found " + label);
            } else {
                speak("I see " + label);
            }
            lastSpeakTime = currentTime;
        }
    }

    private String translateToUrdu(String label) {
        if (label.equalsIgnoreCase("Rock")) return "Pathar";
        if (label.equalsIgnoreCase("Paper")) return "Kaghaz";
        if (label.equalsIgnoreCase("Scissors")) return "Qainchi";
        return label;
    }

    private void speak(String text) {
        if (tts != null) {
            if (!AppSettings.isEnglish) {
                if (text.contains("Searching for")) text = "Talaash jari hai " + translateToUrdu(targetObject);
                if (text.contains("Detection started")) text = "Detection shuru ho gayee";
                if (text.contains("Found")) text = "Mil gaya " + text.replace("Found ", "");
                if (text.contains("I see")) text = "Mujhay nazar aya " + text.replace("I see ", "");
                if (text.contains("Light On")) text = "Light On"; // Keep simple or translate
                if (text.contains("Light Off")) text = "Light Off";
            }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }
}
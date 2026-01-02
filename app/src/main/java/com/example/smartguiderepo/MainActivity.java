package com.example.smartguiderepo;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private PreviewView cameraPreview;
    private OverlayView overlayView;
    private Button btnDetect, btnVoice, btnLanguage;

    private YoloDetector yoloDetector;
    private TextToSpeech tts;
    private ExecutorService cameraExecutor;
    private Camera camera;

    private boolean isDetecting = false;
    private boolean isEnglish = true;
    private boolean isTorchOn = false;
    private long lastSpeakTime = 0;

    private static final int VOICE_RECOGNITION_REQUEST = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cameraPreview = findViewById(R.id.cameraPreview);
        overlayView = findViewById(R.id.overlay);
        btnDetect = findViewById(R.id.btnDetect);
        btnVoice = findViewById(R.id.btnVoice);
        btnLanguage = findViewById(R.id.btnLanguage);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
            }
        });

        try {
            // Ensure filename matches exactly!
            yoloDetector = new YoloDetector(this, "detect.tflite");
        } catch (IOException e) {
            Toast.makeText(this, "Model Load Failed", Toast.LENGTH_LONG).show();
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 101);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();

        btnDetect.setOnClickListener(v -> {
            isDetecting = !isDetecting;
            String status = isDetecting ? "Detection Started" : "Detection Paused";
            speak(status);
            if (!isDetecting) overlayView.setDetections(new ArrayList<>()); // Clear boxes when paused
        });

        btnVoice.setOnClickListener(v -> startVoiceRecognition());
        btnLanguage.setOnClickListener(v -> toggleLanguage());
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                // --- CRITICAL FIX: Safe Analyzer Logic ---
                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    if (!isDetecting || yoloDetector == null) {
                        imageProxy.close();
                        return;
                    }

                    try {
                        // We run on UI thread because we need the View's Bitmap
                        runOnUiThread(() -> {
                            try {
                                Bitmap bitmap = cameraPreview.getBitmap();
                                if (bitmap != null) {
                                    List<YoloDetector.BoundingBox> results = yoloDetector.detect(bitmap);
                                    overlayView.setDetections(results);

                                    if (!results.isEmpty() && System.currentTimeMillis() - lastSpeakTime > 2000) {
                                        String label = results.get(0).label;
                                        if (!isEnglish) label = translateToUrdu(label);
                                        speak("I see " + label);
                                        lastSpeakTime = System.currentTimeMillis();
                                    }
                                }
                            } catch (Exception e) {
                                Log.e("Camera", "Error processing frame", e);
                            }
                        });
                    } catch (Exception e) {
                        Log.e("Camera", "Analyzer failed", e);
                    } finally {
                        // Always close the imageProxy to prevent freezing
                        imageProxy.close();
                    }
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e("CameraX", "Binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ... (Your Helper Methods - Keep these exactly as they were) ...

    private void speak(String message) {
        if (!isEnglish) {
            switch (message) {
                case "Detection Started": message = "ڈیٹیکشن شروع ہو گئی ہے"; break;
                case "Detection Paused": message = "ڈیٹیکشن روک دی گئی ہے"; break;
                // Add more cases if needed
            }
        }
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    private String translateToUrdu(String label) {
        if (label.equals("Rock")) return "پتھر";
        if (label.equals("Paper")) return "کاغذ";
        if (label.equals("Scissors")) return "قینچی";
        return label;
    }

    private void startVoiceRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, isEnglish ? "en-US" : "ur-PK");
        try { startActivityForResult(intent, VOICE_RECOGNITION_REQUEST); } catch (Exception e) {}
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOICE_RECOGNITION_REQUEST && resultCode == RESULT_OK && data != null) {
            ArrayList<String> res = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (!res.isEmpty()) {
                String cmd = res.get(0).toLowerCase();
                if (cmd.contains("start")) { isDetecting = true; speak("Detection Started"); }
                else if (cmd.contains("stop")) { isDetecting = false; speak("Detection Paused"); }
                else if (cmd.contains("torch")) { toggleTorch(); }
            }
        }
    }

    private void toggleLanguage() {
        isEnglish = !isEnglish;
        btnLanguage.setText(isEnglish ? "Urdu" : "English");
        speak(isEnglish ? "English Selected" : "اردو منتخب کی گئی");
    }

    private void toggleTorch() {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            isTorchOn = !isTorchOn;
            camera.getCameraControl().enableTorch(isTorchOn);
            speak(isTorchOn ? "Light On" : "Light Off");
        }
    }
}
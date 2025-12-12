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

    // UI Components
    private PreviewView cameraPreview;
    private OverlayView overlayView;
    private Button btnDetect, btnVoice, btnLanguage;

    // Logic Components
    private YoloDetector yoloDetector;
    private TextToSpeech tts;
    private ExecutorService cameraExecutor;
    private Camera camera; // CameraX Interface

    // State Variables
    private boolean isDetecting = false;
    private boolean isEnglish = true;
    private boolean isTorchOn = false;
    private long lastSpeakTime = 0;

    // Constants
    private static final int VOICE_RECOGNITION_REQUEST = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Initialize UI
        cameraPreview = findViewById(R.id.cameraPreview);
        overlayView = findViewById(R.id.overlay);
        btnDetect = findViewById(R.id.btnDetect);
        btnVoice = findViewById(R.id.btnVoice);
        btnLanguage = findViewById(R.id.btnLanguage);

        // 2. Initialize TTS (Restored from your old code)
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.ENGLISH);
            }
        });

        // 3. Load YOLO Model
        try {
            yoloDetector = new YoloDetector(this, "best_float32.tflite");
        } catch (IOException e) {
            Toast.makeText(this, "Model Load Failed", Toast.LENGTH_LONG).show();
        }

        // 4. Start Camera
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 101);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();

        // 5. Button Listeners (Restored & Updated)
        btnDetect.setOnClickListener(v -> {
            isDetecting = !isDetecting;
            String status = isDetecting ? "Detection Started" : "Detection Paused";
            speak(status); // Uses your custom speak logic
        });

        btnVoice.setOnClickListener(v -> startVoiceRecognition()); // Restored

        btnLanguage.setOnClickListener(v -> toggleLanguage()); // Restored
    }

    // --- CAMERA X LOGIC (Replaces old SurfaceView) ---
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

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    if (!isDetecting || yoloDetector == null) {
                        imageProxy.close();
                        return;
                    }

                    // CameraX Thread: Get Bitmap
                    runOnUiThread(() -> {
                        Bitmap bitmap = cameraPreview.getBitmap();
                        if (bitmap != null) {
                            List<YoloDetector.BoundingBox> results = yoloDetector.detect(bitmap);
                            overlayView.setDetections(results);

                            // Speak logic with delay
                            if (!results.isEmpty() && System.currentTimeMillis() - lastSpeakTime > 2000) {
                                String label = results.get(0).label;
                                // Basic Translation for Voice
                                if (!isEnglish) label = translateToUrdu(label);
                                speak("I see " + label);
                                lastSpeakTime = System.currentTimeMillis();
                            }
                        }
                    });
                    imageProxy.close();
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                // Bind and store camera instance for Torch control
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e("CameraX", "Binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // --- RESTORED FEATURES FROM YOUR OLD CODE ---

    /** 🔊 Speak function supporting Urdu + English */
    private void speak(String message) {
        if (!isEnglish) {
            // Your custom Urdu mapping
            switch (message) {
                case "Detection Started": message = "ڈیٹیکشن شروع ہو گئی ہے"; break;
                case "Detection Paused": message = "ڈیٹیکشن روک دی گئی ہے"; break;
                case "Command not recognized": message = "کمانڈ سمجھ میں نہیں آئی"; break;
                case "I see Rock": message = "مجھے پتھر نظر آ رہا ہے"; break;
                case "I see Paper": message = "مجھے کاغذ نظر آ رہا ہے"; break;
                case "I see Scissors": message = "مجھے قینچی نظر آ رہی ہے"; break;
            }
        }
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    // Helper for Object labels
    private String translateToUrdu(String label) {
        if (label.equals("Rock")) return "پتھر";
        if (label.equals("Paper")) return "کاغذ";
        if (label.equals("Scissors")) return "قینچی";
        return label;
    }

    /** 🎤 Voice recognition (Restored) */
    private void startVoiceRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, isEnglish ? "en-US" : "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, isEnglish ? "Say Detect or Stop" : "کمانڈ بولیں");
        try {
            startActivityForResult(intent, VOICE_RECOGNITION_REQUEST);
        } catch (Exception e) {
            Toast.makeText(this, "Voice Not Supported", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOICE_RECOGNITION_REQUEST && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                String command = results.get(0).toLowerCase(Locale.ROOT);
                if (command.contains("detect") || command.contains("start")) {
                    isDetecting = true;
                    speak("Detection Started");
                } else if (command.contains("stop")) {
                    isDetecting = false;
                    speak("Detection Paused");
                } else if (command.contains("torch") || command.contains("light")) {
                    toggleTorch();
                }
            }
        }
    }

    /** 🌐 Language toggle (Restored) */
    private void toggleLanguage() {
        if (isEnglish) {
            int result = tts.setLanguage(new Locale("ur", "PK"));
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Urdu not supported", Toast.LENGTH_SHORT).show();
            } else {
                speak("زبان اردو میں بدل دی گئی ہے");
                btnLanguage.setText("English");
                isEnglish = false;
            }
        } else {
            tts.setLanguage(Locale.ENGLISH);
            speak("Language changed to English");
            btnLanguage.setText("Urdu");
            isEnglish = true;
        }
    }

    /** 🔦 Torch Control (Updated for CameraX) */
    private void toggleTorch() {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            isTorchOn = !isTorchOn;
            camera.getCameraControl().enableTorch(isTorchOn);
            speak(isTorchOn ? "Light On" : "Light Off");
        }
    }
}
package com.example.smartguiderepo;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;

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
    private Vibrator vibrator;

    private String currentMode = "General";
    private String targetObject = "";
    private long lastSpeakTime = 0;
    private long lastVibrateTime = 0;
    private boolean isDetecting = true;
    private boolean isTorchOn = false;
    private long lastBrightnessCheck = 0;
    

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detection);

        cameraPreview = findViewById(R.id.cameraPreview);
        overlayView = findViewById(R.id.overlay);
        tvTitle = findViewById(R.id.tvTitle);
        btnBack = findViewById(R.id.btnBack);
        btnMicOverlay = findViewById(R.id.btnMicOverlay);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        btnMicOverlay.setOnClickListener(v -> {
            isDetecting = false;
            // Immediate localized prompt
            speak("Listening", "سن رہا ہوں");

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, AppSettings.isEnglish ? "en-US" : "ur-PK");
            startActivityForResult(intent, 202);
        });

        if (getIntent().hasExtra("MODE")) currentMode = getIntent().getStringExtra("MODE");
        if (getIntent().hasExtra("TARGET")) targetObject = getIntent().getStringExtra("TARGET").toLowerCase();

        tvTitle.setText(currentMode.equalsIgnoreCase("Finder") ? "OBJECT FINDER " + targetObject : "INDOOR");
        btnBack.setOnClickListener(v -> finish());

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                if (currentMode.equalsIgnoreCase("Finder")) {
                    speak("Searching", "تلاش شروع");
                } else {
                    speak("Detection started", "تلاش شروع ہو گئی ہے");
                }
            }
        });

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
                    if (!isDetecting) { imageProxy.close(); return; }

                    runOnUiThread(() -> {
                        Bitmap bitmap = cameraPreview.getBitmap();
                        if (bitmap != null) {
                            cameraExecutor.execute(() -> {
                                List<Detection> results = detectorHelper.detect(bitmap);
                                List<YoloDetector.BoundingBox> mappedResults = new ArrayList<>();

                                if (results != null) {
                                    for (Detection det : results) {
                                        String label = det.getCategories().get(0).getLabel().toLowerCase();
                                        float score = det.getCategories().get(0).getScore();
                                        RectF rawBox = det.getBoundingBox();

                                        // 1. Calculate distance using Triangle Similarity
                                        float knownWidth = OBJECT_WIDTHS.getOrDefault(label, 40f); // Default to 40cm
                                        float pixelWidth = rawBox.width();
                                        float distanceCm = (knownWidth * FOCAL_LENGTH) / pixelWidth;
                                        float distanceMeters = distanceCm / 100f;

                                        RectF normBox = new RectF(
                                                rawBox.left / bitmap.getWidth(),
                                                rawBox.top / bitmap.getHeight(),
                                                rawBox.right / bitmap.getWidth(),
                                                rawBox.bottom / bitmap.getHeight()
                                        );

                                        if (currentMode.equalsIgnoreCase("Finder")) {
                                            if (label.contains(targetObject)) {
                                                mappedResults.add(new YoloDetector.BoundingBox(label, score, normBox, distanceMeters));
                                            }
                                        } else {
                                            mappedResults.add(new YoloDetector.BoundingBox(label, score, normBox, distanceMeters));
                                        }
                                    }
                                }
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
            } catch (Exception e) { Log.e("Camera", "Error", e); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processFeedback(List<YoloDetector.BoundingBox> results) {
        if (results.isEmpty()) return;

        long currentTime = System.currentTimeMillis();

        // VIBRATION DEBOUNCE (Existing)
        if (currentTime - lastVibrateTime > 1500) {
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
            }
            lastVibrateTime = currentTime;
        }

        // TTS DEBOUNCE AND DISTANCE FORMATTING
        if (currentTime - lastSpeakTime > 3000) {
            YoloDetector.BoundingBox primaryResult = results.get(0);
            String originalLabel = primaryResult.label;
            String translatedLabel = translateLabel(originalLabel);

            // Round to nearest 0.5 meters
            float roundedDistance = Math.round(primaryResult.distance * 2) / 2.0f;
            String distStr = String.valueOf(roundedDistance);

            if (currentMode.equalsIgnoreCase("Finder")) {
                speak(originalLabel + " found at " + distStr + " meters",
                        translatedLabel + " مل گیا، " + distStr + " میٹر دور");
            } else {
                speak("Caution, " + originalLabel + ", " + distStr + " meters",
                        "خبردار، " + translatedLabel + "، " + distStr + " میٹر");
            }
            lastSpeakTime = currentTime;
        }
    }

    private String translateLabel(String label) {
        if (AppSettings.isEnglish) return label;

        switch(label.toLowerCase().trim()) {
            case "person": return "انسان";
            case "chair": return "کرسی";
            case "bottle": return "بوتل";
            case "bed": return "بستر";
            case "dining table": return "میز";
            case "bench": return "بینچ";
            case "door": return "دروازہ";
            case "window": return "کھڑکی";
            case "stairs": return "سیڑھیاں";
            case "cell phone": return "موبائل";
            case "laptop": return "لیپ ٹاپ";
            case "mouse": return "ماؤس";
            case "remote": return "ریموٹ";
            case "keyboard": return "کی بورڈ";
            case "tv": return "ٹی وی";
            case "cup": return "کپ";
            case "bowl": return "پیالہ";
            case "spoon": return "چمچ";
            case "knife": return "چاقو";
            case "fork": return "کانٹا";
            case "apple": return "سیب";
            case "banana": return "کیلا";
            case "orange": return "مالٹا";
            case "backpack": return "بستہ";
            case "handbag": return "پرس";
            case "umbrella": return "چھتری";
            case "tie": return "ٹائی";
            case "suitcase": return "سوٹ کیس";
            case "book": return "کتاب";
            case "clock": return "گھڑی";
            case "vase": return "گلدان";
            case "scissors": return "قینچی";
            case "toothbrush": return "ٹوتھ برش";
            case "bicycle": return "سائیکل";
            case "car": return "گاڑی";
            case "motorcycle": return "موٹر سائیکل";
            case "bus": return "بس";
            case "truck": return "ٹرک";
            case "traffic light": return "ٹریفک لائٹ";
            case "stop sign": return "رکنے کا اشارہ";
            default: return label;
        }
    }

    private void checkBrightness(androidx.camera.core.ImageProxy image) {
        long now = System.currentTimeMillis();
        if (now - lastBrightnessCheck < 2000) return;
        lastBrightnessCheck = now;

        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        long sum = 0;
        for (int i = 0; i < data.length; i += 50) sum += (data[i] & 0xFF);
        long avg = sum / (data.length / 50);

        runOnUiThread(() -> {
            if (avg < 40 && !isTorchOn) toggleTorch(true);
            else if (avg > 80 && isTorchOn) toggleTorch(false);
        });
    }

    private void toggleTorch(boolean status) {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(status);
            isTorchOn = status;
        }
    }

    private void speak(String englishText, String urduText) {
        if (tts != null) {
            if (AppSettings.isEnglish) {
                tts.setLanguage(Locale.US);
                tts.speak(englishText, TextToSpeech.QUEUE_FLUSH, null, null);
            } else {
                tts.setLanguage(new Locale("ur", "PK"));
                tts.speak(urduText, TextToSpeech.QUEUE_FLUSH, null, null);
            }
        }
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        isDetecting = true; // Resume detection after voice input
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (tts != null) { tts.stop(); tts.shutdown(); }
    }

    // Average widths in centimeters
    private static final java.util.Map<String, Float> OBJECT_WIDTHS = new java.util.HashMap<String, Float>() {{
        put("person", 45f);
        put("door", 90f);
        put("chair", 50f);
        put("laptop", 35f);
        put("cell phone", 15f);
        put("bottle", 8f);
    }};

    // Approximate focal length in pixels (Calibration: F = (Pixel_Width * Distance) / Real_Width)
    private static final float FOCAL_LENGTH = 650f;
}
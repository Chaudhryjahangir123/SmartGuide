package com.example.smartguiderepo;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.RectF;
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


    private static final float ZONE_CRITICAL = 1.0f;
    private static final float ZONE_WARNING = 2.5f;


    private static final long[] PATTERN_CRITICAL = {0, 600, 100, 600};
    private static final long[] PATTERN_WARNING = {0, 200, 150, 200};


    private static final long INTERVAL_CRITICAL = 3000;
    private static final long INTERVAL_WARNING = 5000;
    private static final long INTERVAL_FAR = 10000;


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

                                        float knownWidth = OBJECT_WIDTHS.getOrDefault(label, 40f);
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


        YoloDetector.BoundingBox closest = results.get(0);
        for (YoloDetector.BoundingBox b : results) {
            if (b.distance < closest.distance) closest = b;
        }

        float distance = closest.distance;
        long currentTime = System.currentTimeMillis();


        long interval;
        if (distance <= ZONE_CRITICAL) interval = INTERVAL_CRITICAL;
        else if (distance <= ZONE_WARNING) interval = INTERVAL_WARNING;
        else interval = INTERVAL_FAR;


        if (currentTime - lastVibrateTime > interval) {
            triggerVibration(distance);
            lastVibrateTime = currentTime;
        }


        if (currentTime - lastSpeakTime > (interval + 3000)) {
            triggerVoiceFeedback(closest);
            lastSpeakTime = currentTime;
        }
    }

    private void triggerVibration(float distance) {
        if (vibrator == null || !vibrator.hasVibrator()) return;

        VibrationEffect effect;
        if (distance <= ZONE_CRITICAL) {

            effect = VibrationEffect.createWaveform(PATTERN_CRITICAL, -1);
        } else if (distance <= ZONE_WARNING) {

            effect = VibrationEffect.createWaveform(PATTERN_WARNING, -1);
        } else {

            effect = VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE);
        }
        vibrator.vibrate(effect);
    }

    private void triggerVoiceFeedback(YoloDetector.BoundingBox item) {
        String label = item.label;
        String translated = translateLabel(label);
        float roundedDist = Math.round(item.distance * 2) / 2.0f; // Round to 0.5m

        String enMsg, urMsg;

        if (item.distance <= ZONE_CRITICAL) {
            enMsg = "Stop! " + label + " is very close.";
            urMsg = "رک جائیں! " + translated + " بالکل قریب ہے۔";
        } else if (item.distance <= ZONE_WARNING) {
            enMsg = "Caution, " + label + " at " + roundedDist + " meters.";
            urMsg = "خبردار، " + translated + " " + roundedDist + " میٹر پر ہے۔";
        } else {
            enMsg = label + " detected at " + roundedDist + " meters.";
            urMsg = translated + " نظر آیا، " + roundedDist + " میٹر دور۔";
        }

        speak(enMsg, urMsg);
    }
    // ---------------------------------

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
        isDetecting = true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (tts != null) { tts.stop(); tts.shutdown(); }
    }

    private static final java.util.Map<String, Float> OBJECT_WIDTHS = new java.util.HashMap<String, Float>() {{
        put("person", 45f);
        put("door", 90f);
        put("chair", 50f);
        put("laptop", 35f);
        put("cell phone", 15f);
        put("bottle", 8f);
    }};

    private static final float FOCAL_LENGTH = 650f;
}
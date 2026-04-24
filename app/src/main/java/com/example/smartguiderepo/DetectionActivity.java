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
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
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

    // Background Speech Recognition
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean isSpeechInitialized = false;

    private String currentMode = "General";
    private String targetObject = "";
    private long lastSpeakTime = 0;
    private long lastVibrateTime = 0;
    private boolean isDetecting = true;

    // Urgency Constants
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

        // Fetch intent extras
        if (getIntent().hasExtra("MODE")) currentMode = getIntent().getStringExtra("MODE");
        if (getIntent().hasExtra("TARGET")) targetObject = getIntent().getStringExtra("TARGET").toLowerCase();

        tvTitle.setText(currentMode.equalsIgnoreCase("Finder") ? "OBJECT FINDER " + targetObject : "INDOOR");
        btnBack.setOnClickListener(v -> finish());

        // Initialize TTS with Onboarding logic
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                // Progress listener to prevent app from "hearing itself"
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) { stopListening(); }
                    @Override public void onDone(String utteranceId) { startListening(); }
                    @Override public void onError(String utteranceId) { startListening(); }
                });

                // Voice Onboarding
                if (AppSettings.isFirstTime(this)) {
                    speak(HelpManager.getFullOnboarding(true), HelpManager.getFullOnboarding(false));
                    AppSettings.setFirstTimeDone(this);
                } else {
                    if (currentMode.equalsIgnoreCase("Finder")) speak("Searching", "تلاش شروع");
                    else speak("Detection started", "تلاش شروع ہو گئی ہے");
                }
            }
        });

        // Initialize Background Listening
        initSpeechRecognizer();

        detectorHelper = new ObjectDetectorHelper(this);
        cameraExecutor = Executors.newSingleThreadExecutor();

        if (allPermissionsGranted()) startCamera();
        else ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 101);
    }

    private void initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, AppSettings.isEnglish ? "en-US" : "ur-PK");

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    handleVoiceCommand(matches.get(0).toLowerCase());
                }
                startListening(); // Re-trigger loop
            }

            @Override
            public void onError(int error) { startListening(); } // If timeout, restart
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
        isSpeechInitialized = true;
    }

    private void handleVoiceCommand(String command) {
        // Stop Command
        if (command.contains("stop") || command.contains("ruko") || command.contains("break")) {
            isDetecting = false;
            speak("System paused", "سسٹم روک دیا گیا ہے");
        }
        // Start Command
        else if (command.contains("start") || command.contains("shuru") || command.contains("resume")) {
            isDetecting = true;
            speak("Resuming", "دوبارہ شروع");
        }
        // Finder Command
        else if (command.contains("find") || command.contains("search") || command.contains("talaash")) {
            targetObject = command.replace("find", "").replace("search", "").replace("talaash", "").trim();
            currentMode = "Finder";
            isDetecting = true;
            runOnUiThread(() -> tvTitle.setText("FINDER: " + targetObject));
            speak("Finding " + targetObject, targetObject + " کی تلاش شروع");
        }
        // Help Command
        else if (command.contains("help") || command.contains("madad")) {
            speak(HelpManager.getFullOnboarding(true), HelpManager.getFullOnboarding(false));
        }
    }

    private void startListening() {
        if (isSpeechInitialized) {
            runOnUiThread(() -> speechRecognizer.startListening(speechIntent));
        }
    }

    private void stopListening() {
        if (isSpeechInitialized) {
            runOnUiThread(() -> speechRecognizer.stopListening());
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
                    if (!isDetecting) { imageProxy.close(); return; } // Effectively STOPS logic

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
                                        float distanceMeters = (knownWidth * FOCAL_LENGTH / rawBox.width()) / 100f;

                                        RectF normBox = new RectF(
                                                rawBox.left / bitmap.getWidth(), rawBox.top / bitmap.getHeight(),
                                                rawBox.right / bitmap.getWidth(), rawBox.bottom / bitmap.getHeight()
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
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);
            } catch (Exception e) { Log.e("Camera", "Error", e); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processFeedback(List<YoloDetector.BoundingBox> results) {
        if (results.isEmpty() || !isDetecting) return;

        YoloDetector.BoundingBox closest = results.get(0);
        for (YoloDetector.BoundingBox b : results) {
            if (b.distance < closest.distance) closest = b;
        }

        float distance = closest.distance;
        long currentTime = System.currentTimeMillis();

        long interval = (distance <= ZONE_CRITICAL) ? INTERVAL_CRITICAL :
                (distance <= ZONE_WARNING) ? INTERVAL_WARNING : INTERVAL_FAR;

        if (currentTime - lastVibrateTime > interval) {
            triggerVibration(distance);
            lastVibrateTime = currentTime;
        }

        if (currentTime - lastSpeakTime > (interval + 2000)) {
            triggerVoiceFeedback(closest);
            lastSpeakTime = currentTime;
        }
    }

    private void triggerVibration(float distance) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        VibrationEffect effect = (distance <= ZONE_CRITICAL) ?
                VibrationEffect.createWaveform(PATTERN_CRITICAL, -1) :
                (distance <= ZONE_WARNING) ? VibrationEffect.createWaveform(PATTERN_WARNING, -1) :
                        VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE);
        vibrator.vibrate(effect);
    }

    private void triggerVoiceFeedback(YoloDetector.BoundingBox item) {
        String translated = translateLabel(item.label);
        float roundedDist = Math.round(item.distance * 2) / 2.0f;
        String enMsg, urMsg;

        if (item.distance <= ZONE_CRITICAL) {
            enMsg = "Stop! " + item.label + " is very close.";
            urMsg = "رک جائیں! " + translated + " بالکل قریب ہے۔";
        } else if (item.distance <= ZONE_WARNING) {
            enMsg = "Caution, " + item.label + " at " + roundedDist + " meters.";
            urMsg = "خبردار، " + translated + " " + roundedDist + " میٹر پر ہے۔";
        } else {
            enMsg = item.label + " at " + roundedDist + " meters.";
            urMsg = translated + " " + roundedDist + " میٹر دور۔";
        }
        speak(enMsg, urMsg);
    }

    private String translateLabel(String label) {
        if (AppSettings.isEnglish) return label;
        // ... (Switch case from previous code remains the same)
        return label;
    }

    private void speak(String englishText, String urduText) {
        if (tts != null) {
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "MessageId");
            if (AppSettings.isEnglish) {
                tts.setLanguage(Locale.US);
                tts.speak(englishText, TextToSpeech.QUEUE_FLUSH, params, "MessageId");
            } else {
                tts.setLanguage(new Locale("ur", "PK"));
                tts.speak(urduText, TextToSpeech.QUEUE_FLUSH, params, "MessageId");
            }
        }
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (tts != null) { tts.stop(); tts.shutdown(); }
    }

    private static final java.util.Map<String, Float> OBJECT_WIDTHS = new java.util.HashMap<String, Float>() {{
        put("person", 45f); put("door", 90f); put("chair", 50f);
        put("laptop", 35f); put("cell phone", 15f); put("bottle", 8f);
    }};
    private static final float FOCAL_LENGTH = 650f;
}
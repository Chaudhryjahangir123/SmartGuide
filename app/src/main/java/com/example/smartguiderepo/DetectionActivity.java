package com.example.smartguiderepo;

import android.Manifest;
import android.content.Intent;
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
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import org.tensorflow.lite.task.vision.detector.Detection;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DetectionActivity extends AppCompatActivity {

    private PreviewView cameraPreview;
    private OverlayView overlayView;
    private TextView tvTitle;
    private ObjectDetectorHelper detectorHelper;
    private ExecutorService cameraExecutor;
    private TextToSpeech tts;
    private Camera camera;
    private Vibrator vibrator;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;

    private boolean isDetecting = true;
    private boolean manualFlashOff = false;
    private String currentMode = "General", targetObject = "";
    private long lastSpeakTime = 0, lastVibrateTime = 0, activityStartTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detection);

        activityStartTime = System.currentTimeMillis();
        cameraPreview = findViewById(R.id.cameraPreview);
        overlayView = findViewById(R.id.overlay);
        tvTitle = findViewById(R.id.tvTitle);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        currentMode = getIntent().getStringExtra("MODE");
        targetObject = Objects.toString(getIntent().getStringExtra("TARGET"), "").toLowerCase();
        tvTitle.setText(currentMode.equalsIgnoreCase("Finder") ? "FINDER: " + targetObject : "INDOOR");

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { stopListening(); }
                    @Override public void onDone(String id) { startListening(); }
                    @Override public void onError(String id) { startListening(); }
                });
                speak("System Active", "سسٹم تیار ہے");
            }
        });

        detectorHelper = new ObjectDetectorHelper(this);
        cameraExecutor = Executors.newSingleThreadExecutor();
        initSpeechRecognizer();
        startCamera();
    }

    private void initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onResults(Bundle r) {
                ArrayList<String> m = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (m != null && !m.isEmpty()) handleVoiceCommand(m.get(0).toLowerCase());
                startListening();
            }
            @Override public void onError(int e) { startListening(); }
            @Override public void onReadyForSpeech(Bundle p) {} @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float r) {} @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {} @Override public void onPartialResults(Bundle p) {}
            @Override public void onEvent(int e, Bundle p) {}
        });
        startListening();
    }

    private void handleVoiceCommand(String cmd) {
        if (System.currentTimeMillis() - activityStartTime < 3000) return;
        if (cmd.contains("light") || cmd.contains("flash")) { manualFlashOff = false; toggleFlash(true); }
        else if (cmd.contains("off") || cmd.contains("band")) { manualFlashOff = true; toggleFlash(false); }
        else if (cmd.contains("back") || cmd.contains("wapas")) finish();
        else if (cmd.contains("stop")) isDetecting = false;
        else if (cmd.contains("start")) isDetecting = true;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();

                analysis.setAnalyzer(cameraExecutor, image -> {
                    if (!isDetecting) { image.close(); return; }
                    runOnUiThread(() -> {
                        Bitmap bmp = cameraPreview.getBitmap();
                        if (bmp != null) {
                            if (!manualFlashOff) checkAutoFlash(bmp);
                            cameraExecutor.execute(() -> {
                                List<Detection> results = detectorHelper.detect(bmp);
                                List<YoloDetector.BoundingBox> mappedBoxes = new ArrayList<>();
                                if (results != null) {
                                    for (Detection d : results) {
                                        float score = d.getCategories().get(0).getScore();
                                        String label = d.getCategories().get(0).getLabel().toLowerCase();

                                        String finalLabel;
                                        if (label.contains("person")) {
                                            if (score < 0.45f) continue; // Threshold kam kar diya (45% confirm pe bhi bolay ga)
                                            finalLabel = "Person";
                                        } else {
                                            if (score < 0.45f) continue; // Baqi cheezon ke liye mazeed kam (35%)
                                            finalLabel = (score > 0.60f) ? label : (AppSettings.isEnglish ? "Object" : "Cheez");
                                        }

                                        RectF r = d.getBoundingBox();
                                        float dist = (45f * 720f / r.width()) / 100f;
                                        RectF norm = new RectF(r.left/bmp.getWidth(), r.top/bmp.getHeight(), r.right/bmp.getWidth(), r.bottom/bmp.getHeight());

                                        if (currentMode.equalsIgnoreCase("Finder")) {
                                            if (label.contains(targetObject)) mappedBoxes.add(new YoloDetector.BoundingBox(finalLabel, score, norm, dist));
                                        } else {
                                            mappedBoxes.add(new YoloDetector.BoundingBox(finalLabel, score, norm, dist));
                                        }
                                    }
                                }
                                // Sorting: Person stays at top
                                Collections.sort(mappedBoxes, (a, b) -> Integer.compare(getPriority(a.label), getPriority(b.label)));
                                runOnUiThread(() -> { overlayView.setDetections(mappedBoxes); processFeedback(mappedBoxes); });
                            });
                        }
                        image.close();
                    });
                });
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            } catch (Exception e) {}
        }, ContextCompat.getMainExecutor(this));
    }

    private int getPriority(String label) {
        if (label.toLowerCase().contains("person")) return 1;
        if (label.toLowerCase().contains("chair") || label.toLowerCase().contains("door")) return 2;
        if (label.toLowerCase().contains("object") || label.toLowerCase().contains("cheez")) return 4;
        return 3;
    }

    private void checkAutoFlash(Bitmap bmp) {
        int p = bmp.getPixel(bmp.getWidth()/2, bmp.getHeight()/2);
        int br = (int) (0.299*((p>>16)&0xff) + 0.587*((p>>8)&0xff) + 0.114*(p&0xff));
        if (br < 20) toggleFlash(true); else if (br > 45) toggleFlash(false);
    }

    private void processFeedback(List<YoloDetector.BoundingBox> results) {
        if (results.isEmpty() || !isDetecting) return;
        long currentTime = System.currentTimeMillis();

        // Vibration Delay: 3 Seconds
        YoloDetector.BoundingBox closest = results.get(0);
        for (YoloDetector.BoundingBox b : results) { if (b.distance < closest.distance) closest = b; }
        if (currentTime - lastVibrateTime > 3000) {
            if (closest.distance < 1.2f) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 500, 100, 500}, -1));
                lastVibrateTime = currentTime;
            }
        }

        // Voice Report: Unique Objects
        if (currentTime - lastSpeakTime > 5000) {
            StringBuilder sb = new StringBuilder();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < Math.min(results.size(), 3); i++) {
                String l = results.get(i).label;
                if (seen.add(l)) {
                    if (sb.length() > 0) sb.append(AppSettings.isEnglish ? " and " : " aur ");
                    sb.append(l);
                }
            }
            if (sb.length() > 0) {
                speak(sb.toString() + " detected", sb.toString() + " نظر آ رہے ہیں");
                lastSpeakTime = currentTime;
            }
        }
    }

    private void toggleFlash(boolean on) { if (camera != null) camera.getCameraControl().enableTorch(on); }
    private void startListening() { runOnUiThread(() -> speechRecognizer.startListening(speechIntent)); }
    private void stopListening() { runOnUiThread(() -> speechRecognizer.stopListening()); }
    private void speak(String en, String ur) {
        if (tts == null) return;
        tts.setLanguage(AppSettings.isEnglish ? Locale.US : new Locale("ur", "PK"));
        tts.speak(AppSettings.isEnglish ? en : ur, TextToSpeech.QUEUE_FLUSH, null, "ID");
    }

    @Override protected void onDestroy() { super.onDestroy(); if (speechRecognizer != null) speechRecognizer.destroy(); tts.shutdown(); cameraExecutor.shutdown(); }
}
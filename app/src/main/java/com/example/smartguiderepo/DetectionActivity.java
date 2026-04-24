package com.example.smartguiderepo;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.jiangdg.usb.USBMonitor;
import com.jiangdg.uvc.IFrameCallback;
import com.jiangdg.uvc.UVCCamera;

import org.tensorflow.lite.task.vision.detector.Detection;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class DetectionActivity extends AppCompatActivity {

    private static final String TAG        = "DetectionActivity";
    private static final int    UVC_WIDTH  = 640;
    private static final int    UVC_HEIGHT = 480;

    // ── Views ──────────────────────────────────────────────────────────────
    private PreviewView cameraPreview;   // built-in camera preview
    private SurfaceView uvcPreview;      // USB webcam preview
    private OverlayView overlayView;
    private TextView    tvTitle;
    private ImageView   btnBack, btnMicOverlay;

    // ── Detection ──────────────────────────────────────────────────────────
    private ObjectDetectorHelper detectorHelper;
    private ExecutorService      cameraExecutor;
    private Camera               camera;

    // ── USB / UVC ──────────────────────────────────────────────────────────
    private USBMonitor         usbMonitor;
    private UVCCamera          uvcCamera;
    private volatile boolean   uvcActive      = false;
    private final AtomicBoolean processingFrame = new AtomicBoolean(false);

    // ── Feedback ───────────────────────────────────────────────────────────
    private TextToSpeech tts;
    private Vibrator     vibrator;

    // ── Speech recognition ─────────────────────────────────────────────────
    private SpeechRecognizer speechRecognizer;
    private Intent           speechIntent;
    private boolean          isSpeechInitialized = false;

    // ── State ──────────────────────────────────────────────────────────────
    private String  currentMode  = "General";
    private String  targetObject = "";
    private long    lastSpeakTime   = 0;
    private long    lastVibrateTime = 0;
    private boolean isDetecting  = true;

    // ── Urgency constants ──────────────────────────────────────────────────
    private static final float  ZONE_CRITICAL     = 1.0f;
    private static final float  ZONE_WARNING      = 2.5f;
    private static final long[] PATTERN_CRITICAL  = {0, 600, 100, 600};
    private static final long[] PATTERN_WARNING   = {0, 200, 150, 200};
    private static final long   INTERVAL_CRITICAL = 3000;
    private static final long   INTERVAL_WARNING  = 5000;
    private static final long   INTERVAL_FAR      = 10000;

    // ══════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    @ExperimentalCamera2Interop
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detection);

        cameraPreview = findViewById(R.id.cameraPreview);
        uvcPreview    = findViewById(R.id.uvcPreview);
        overlayView   = findViewById(R.id.overlay);
        tvTitle       = findViewById(R.id.tvTitle);
        btnBack       = findViewById(R.id.btnBack);
        btnMicOverlay = findViewById(R.id.btnMicOverlay);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        if (getIntent().hasExtra("MODE"))   currentMode  = getIntent().getStringExtra("MODE");
        if (getIntent().hasExtra("TARGET")) targetObject = getIntent().getStringExtra("TARGET").toLowerCase();

        tvTitle.setText(currentMode.equalsIgnoreCase("Finder")
                ? "OBJECT FINDER " + targetObject : "INDOOR");
        btnBack.setOnClickListener(v -> finish());

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { stopListening(); }
                    @Override public void onDone(String id)  { startListening(); }
                    @Override public void onError(String id) { startListening(); }
                });
                if (AppSettings.isFirstTime(this)) {
                    speak(HelpManager.getFullOnboarding(true), HelpManager.getFullOnboarding(false));
                    AppSettings.setFirstTimeDone(this);
                } else {
                    if (currentMode.equalsIgnoreCase("Finder")) speak("Searching", "تلاش شروع");
                    else speak("Detection started", "تلاش شروع ہو گئی ہے");
                }
            }
        });

        initSpeechRecognizer();

        detectorHelper = new ObjectDetectorHelper(this);
        cameraExecutor = Executors.newSingleThreadExecutor();

        initUSBMonitor();

        if (allPermissionsGranted()) startCamera();
        else ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 101);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (usbMonitor != null) usbMonitor.register();
    }

    @Override
    protected void onPause() {
        if (usbMonitor != null) usbMonitor.unregister();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (cameraExecutor  != null) cameraExecutor.shutdown();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        releaseUVCCamera();
        if (usbMonitor != null) { usbMonitor.destroy(); usbMonitor = null; }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  USB Monitor — detects Logitech C270 attach / detach
    // ══════════════════════════════════════════════════════════════════════

    private void initUSBMonitor() {
        usbMonitor = new USBMonitor(this, new USBMonitor.OnDeviceConnectListener() {

            @Override
            public void onAttach(UsbDevice device) {
                Log.d(TAG, "USB attached: " + device.getDeviceName());
                usbMonitor.requestPermission(device);
            }

            @Override
            public void onConnect(UsbDevice device,
                                  USBMonitor.UsbControlBlock ctrlBlock,
                                  boolean createNew) {
                Log.d(TAG, "USB permission granted — opening UVC camera");
                openUVCCamera(ctrlBlock);
            }

            @Override
            public void onDisconnect(UsbDevice device,
                                     USBMonitor.UsbControlBlock ctrlBlock) {
                Log.d(TAG, "USB disconnected");
                onUSBCameraGone();
            }

            @Override
            public void onDetach(UsbDevice device) {
                Log.d(TAG, "USB detached");
                onUSBCameraGone();
            }

            @Override
            public void onCancel(UsbDevice device) {
                Log.w(TAG, "USB permission denied");
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    //  UVC Camera open / release
    // ══════════════════════════════════════════════════════════════════════

    private void openUVCCamera(USBMonitor.UsbControlBlock ctrlBlock) {
        cameraExecutor.execute(() -> {
            try {
                releaseUVCCamera();

                uvcCamera = new UVCCamera();
                uvcCamera.open(ctrlBlock);

                // C270 supports MJPEG natively — prefer it for efficiency
                try {
                    uvcCamera.setPreviewSize(UVC_WIDTH, UVC_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG);
                } catch (IllegalArgumentException ex) {
                    uvcCamera.setPreviewSize(UVC_WIDTH, UVC_HEIGHT); // fallback to YUYV
                }

                // Display to SurfaceView
                uvcCamera.setPreviewDisplay(uvcPreview.getHolder().getSurface());

                // Frame callback for object detection (NV21 bytes)
                uvcCamera.setFrameCallback(uvcFrameCallback, UVCCamera.PIXEL_FORMAT_NV21);
                uvcCamera.startPreview();

                uvcActive = true;
                runOnUiThread(() -> {
                    stopCameraX();
                    cameraPreview.setVisibility(View.GONE);
                    uvcPreview.setVisibility(View.VISIBLE);
                });
                Log.d(TAG, "UVC camera started " + UVC_WIDTH + "x" + UVC_HEIGHT);

            } catch (Exception e) {
                Log.e(TAG, "openUVCCamera failed: " + e.getMessage());
                uvcActive = false;
            }
        });
    }

    // Receives NV21 frames from the webcam on the USB read thread
    private final IFrameCallback uvcFrameCallback = new IFrameCallback() {
        @Override
        public void onFrame(ByteBuffer frame) {
            if (!isDetecting || !uvcActive) return;
            if (!processingFrame.compareAndSet(false, true)) return; // drop frame if busy

            byte[] nv21 = new byte[frame.remaining()];
            frame.get(nv21);

            cameraExecutor.execute(() -> {
                try {
                    Bitmap bmp = nv21ToBitmap(nv21, UVC_WIDTH, UVC_HEIGHT);
                    if (bmp != null) runDetection(bmp);
                } finally {
                    processingFrame.set(false);
                }
            });
        }
    };

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void onUSBCameraGone() {
        releaseUVCCamera();
        runOnUiThread(() -> {
            uvcPreview.setVisibility(View.GONE);
            cameraPreview.setVisibility(View.VISIBLE);
            startCamera(); // fall back to built-in camera
        });
    }

    private void releaseUVCCamera() {
        uvcActive = false;
        if (uvcCamera != null) {
            uvcCamera.setFrameCallback(null, 0);
            uvcCamera.stopPreview();
            uvcCamera.destroy();
            uvcCamera = null;
        }
    }

    private void stopCameraX() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try { f.get().unbindAll(); }
            catch (Exception e) { Log.e(TAG, "stopCameraX: " + e.getMessage()); }
        }, ContextCompat.getMainExecutor(this));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Frame conversion + detection  (shared by both camera paths)
    // ══════════════════════════════════════════════════════════════════════

    private Bitmap nv21ToBitmap(byte[] nv21, int width, int height) {
        try {
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, width, height), 90, bos);
            byte[] jpg = bos.toByteArray();
            return BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
        } catch (Exception e) {
            Log.e(TAG, "nv21ToBitmap: " + e.getMessage());
            return null;
        }
    }

    private void runDetection(Bitmap bitmap) {
        List<Detection> results = detectorHelper.detect(bitmap);
        List<YoloDetector.BoundingBox> mapped = new ArrayList<>();

        if (results != null) {
            for (Detection det : results) {
                String label = det.getCategories().get(0).getLabel().toLowerCase();
                float  score = det.getCategories().get(0).getScore();
                RectF  raw   = det.getBoundingBox();

                float knownWidth     = OBJECT_WIDTHS.getOrDefault(label, 40f);
                float distanceMeters = (knownWidth * FOCAL_LENGTH / raw.width()) / 100f;

                RectF norm = new RectF(
                        raw.left  / bitmap.getWidth(),  raw.top    / bitmap.getHeight(),
                        raw.right / bitmap.getWidth(),  raw.bottom / bitmap.getHeight());

                if (currentMode.equalsIgnoreCase("Finder")) {
                    if (label.contains(targetObject))
                        mapped.add(new YoloDetector.BoundingBox(label, score, norm, distanceMeters));
                } else {
                    mapped.add(new YoloDetector.BoundingBox(label, score, norm, distanceMeters));
                }
            }
        }

        runOnUiThread(() -> {
            overlayView.setDetections(mapped);
            processFeedback(mapped);
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    //  CameraX — built-in camera fallback
    // ══════════════════════════════════════════════════════════════════════

    @ExperimentalCamera2Interop
    private CameraSelector getExternalCameraSelector() {
        try {
            CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (String id : mgr.getCameraIdList()) {
                CameraCharacteristics c = mgr.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                    final String extId = id;
                    return new CameraSelector.Builder()
                            .addCameraFilter(list -> {
                                List<CameraInfo> r = new ArrayList<>();
                                for (CameraInfo info : list) {
                                    if (Camera2CameraInfo.from(info).getCameraId().equals(extId))
                                        r.add(info);
                                }
                                return r;
                            }).build();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getExternalCameraSelector: " + e.getMessage());
        }
        return null;
    }

    @ExperimentalCamera2Interop
    private void startCamera() {
        if (uvcActive) return; // USB webcam is running — skip CameraX

        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                analysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    if (!isDetecting || uvcActive) { imageProxy.close(); return; }
                    runOnUiThread(() -> {
                        Bitmap bmp = cameraPreview.getBitmap();
                        if (bmp != null) cameraExecutor.execute(() -> runDetection(bmp));
                    });
                    imageProxy.close();
                });

                CameraSelector selector = getExternalCameraSelector();
                if (selector == null) selector = CameraSelector.DEFAULT_BACK_CAMERA;

                provider.unbindAll();
                camera = provider.bindToLifecycle(this, selector, preview, analysis);

            } catch (Exception e) { Log.e(TAG, "startCamera: " + e.getMessage()); }
        }, ContextCompat.getMainExecutor(this));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Speech recognition
    // ══════════════════════════════════════════════════════════════════════

    private void initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                AppSettings.isEnglish ? "en-US" : "ur-PK");

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty())
                    handleVoiceCommand(matches.get(0).toLowerCase());
                startListening();
            }
            @Override public void onError(int error)          { startListening(); }
            @Override public void onReadyForSpeech(Bundle p)  {}
            @Override public void onBeginningOfSpeech()       {}
            @Override public void onRmsChanged(float rms)     {}
            @Override public void onBufferReceived(byte[] b)  {}
            @Override public void onEndOfSpeech()             {}
            @Override public void onPartialResults(Bundle p)  {}
            @Override public void onEvent(int t, Bundle p)    {}
        });
        isSpeechInitialized = true;
    }

    private void handleVoiceCommand(String command) {
        if (command.contains("stop") || command.contains("ruko") || command.contains("break")) {
            isDetecting = false;
            speak("System paused", "سسٹم روک دیا گیا ہے");
        } else if (command.contains("start") || command.contains("shuru") || command.contains("resume")) {
            isDetecting = true;
            speak("Resuming", "دوبارہ شروع");
        } else if (command.contains("find") || command.contains("search") || command.contains("talaash")) {
            targetObject = command.replace("find","").replace("search","").replace("talaash","").trim();
            currentMode  = "Finder";
            isDetecting  = true;
            runOnUiThread(() -> tvTitle.setText("FINDER: " + targetObject));
            speak("Finding " + targetObject, targetObject + " کی تلاش شروع");
        } else if (command.contains("help") || command.contains("madad")) {
            speak(HelpManager.getFullOnboarding(true), HelpManager.getFullOnboarding(false));
        }
    }

    private void startListening() {
        if (isSpeechInitialized)
            runOnUiThread(() -> speechRecognizer.startListening(speechIntent));
    }

    private void stopListening() {
        if (isSpeechInitialized)
            runOnUiThread(() -> speechRecognizer.stopListening());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Haptic + voice feedback
    // ══════════════════════════════════════════════════════════════════════

    private void processFeedback(List<YoloDetector.BoundingBox> results) {
        if (results.isEmpty() || !isDetecting) return;

        YoloDetector.BoundingBox closest = results.get(0);
        for (YoloDetector.BoundingBox b : results)
            if (b.distance < closest.distance) closest = b;

        float distance    = closest.distance;
        long  currentTime = System.currentTimeMillis();
        long  interval    = (distance <= ZONE_CRITICAL) ? INTERVAL_CRITICAL
                          : (distance <= ZONE_WARNING)  ? INTERVAL_WARNING : INTERVAL_FAR;

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
        VibrationEffect effect = (distance <= ZONE_CRITICAL)
                ? VibrationEffect.createWaveform(PATTERN_CRITICAL, -1)
                : (distance <= ZONE_WARNING)
                        ? VibrationEffect.createWaveform(PATTERN_WARNING, -1)
                        : VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE);
        vibrator.vibrate(effect);
    }

    private void triggerVoiceFeedback(YoloDetector.BoundingBox item) {
        String translated  = translateLabel(item.label);
        float  roundedDist = Math.round(item.distance * 2) / 2.0f;
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
        return label;
    }

    private void speak(String english, String urdu) {
        if (tts == null) return;
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "MessageId");
        if (AppSettings.isEnglish) {
            tts.setLanguage(Locale.US);
            tts.speak(english, TextToSpeech.QUEUE_FLUSH, params, "MessageId");
        } else {
            tts.setLanguage(new Locale("ur", "PK"));
            tts.speak(urdu, TextToSpeech.QUEUE_FLUSH, params, "MessageId");
        }
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Constants
    // ══════════════════════════════════════════════════════════════════════

    private static final java.util.Map<String, Float> OBJECT_WIDTHS =
            new java.util.HashMap<String, Float>() {{
                put("person",     45f);
                put("door",       90f);
                put("chair",      50f);
                put("laptop",     35f);
                put("cell phone", 15f);
                put("bottle",      8f);
            }};

    private static final float FOCAL_LENGTH = 650f;
}

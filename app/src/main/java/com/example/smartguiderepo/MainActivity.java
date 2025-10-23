package com.example.smartguiderepo;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.Toast;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;
import android.content.res.AssetFileDescriptor;
import org.tensorflow.lite.Interpreter;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private SurfaceView cameraPreview;
    private SurfaceHolder surfaceHolder;
    private Camera camera;
    private TextToSpeech tts;
    private Button btnDetect, btnVoice, btnLanguage;

    private static final int CAMERA_PERMISSION_CODE = 100;
    private static final int VOICE_RECOGNITION_REQUEST = 200;

    private boolean isCameraOn = false;
    private boolean isEnglish = true;
    private Interpreter tflite;

    // Torch variables
    private boolean torchOn = false;
    private final int LOW_LIGHT_THRESHOLD = 70;
    private final int BRIGHT_LIGHT_THRESHOLD = 120;
    private final long BRIGHTNESS_INTERVAL = 3000; // 3 seconds
    private long lastBrightnessCheckTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cameraPreview = findViewById(R.id.cameraPreview);
        btnDetect = findViewById(R.id.btnDetect);
        btnVoice = findViewById(R.id.btnVoice);
        btnLanguage = findViewById(R.id.btnLanguage);

        // Initialize Text-to-Speech
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.ENGLISH);
                tts.setSpeechRate(1.0f);
            }
        });

        // Load TensorFlow Lite model
        try {
            tflite = new Interpreter(loadModelFile());
            Toast.makeText(this, "Model loaded successfully", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Model loading failed", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }

        // Request camera permission
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        } else {
            openCamera();
        }

        // Button Listeners
        btnDetect.setOnClickListener(v -> {
            if (isCameraOn) speak("Model is ready — add real input here");
            else speak("Camera not ready");
        });

        btnVoice.setOnClickListener(v -> startVoiceRecognition());
        btnLanguage.setOnClickListener(v -> toggleLanguage());
    }

    /** 🔊 Speak Function (Supports Urdu + English) */
    private void speak(String message) {
        if (!isEnglish) {
            switch (message) {
                case "Object ahead":
                    message = "سامنے رکاوٹ موجود ہے";
                    break;
                case "Navigation stopped":
                    message = "نیویگیشن بند کر دی گئی ہے";
                    break;
                case "Command not recognized":
                    message = "کمانڈ سمجھ میں نہیں آئی";
                    break;
                case "Camera not ready":
                    message = "کیمرہ تیار نہیں ہے";
                    break;
                case "Low light detected. Turning on flashlight.":
                    message = "روشنی کم ہے، فلیش آن کیا جا رہا ہے";
                    break;
                case "Bright light detected. Turning off flashlight.":
                    message = "روشنی زیادہ ہے، فلیش بند کیا جا رہا ہے";
                    break;
            }
        }
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    /** 🎥 Camera Setup */
    private void openCamera() {
        surfaceHolder = cameraPreview.getHolder();
        surfaceHolder.addCallback(this);
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        try {
            camera = Camera.open();
            camera.setDisplayOrientation(90); // portrait mode

            Camera.Parameters params = camera.getParameters();

            // Enable continuous focus
            if (params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }

            // Choose best preview size
            Camera.Size bestSize = null;
            for (Camera.Size size : params.getSupportedPreviewSizes()) {
                if (bestSize == null || (size.width * size.height > bestSize.width * bestSize.height)) {
                    bestSize = size;
                }
            }
            if (bestSize != null) {
                params.setPreviewSize(bestSize.width, bestSize.height);
            }

            camera.setParameters(params);
            camera.setPreviewDisplay(holder);
            camera.startPreview();
            isCameraOn = true;

            // Set brightness callback
            new android.os.Handler().postDelayed(() -> camera.setPreviewCallback(previewCallback), 500);

        } catch (IOException | RuntimeException e) {
            Toast.makeText(this, "Camera unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    /** 🔦 Brightness + Flashlight Control */
    private final Camera.PreviewCallback previewCallback = (data, camera) -> {
        int frameSum = 0;
        int sampleCount = 0;

        for (int i = 0; i < data.length; i += 500) {
            frameSum += (data[i] & 0xFF);
            sampleCount++;
        }

        int avgBrightness = frameSum / sampleCount;
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastBrightnessCheckTime > BRIGHTNESS_INTERVAL) {
            lastBrightnessCheckTime = currentTime;
            Camera.Parameters params = camera.getParameters();

            // Low light → turn ON torch
            if (avgBrightness < LOW_LIGHT_THRESHOLD && !torchOn) {
                speak("Low light detected. Turning on flashlight.");
                params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
                camera.setParameters(params);
                torchOn = true;
            }

            // Bright → turn OFF torch
            else if (avgBrightness > BRIGHT_LIGHT_THRESHOLD && torchOn) {
                speak("Bright light detected. Turning off flashlight.");
                params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                camera.setParameters(params);
                camera.stopPreview();
                camera.startPreview();
                torchOn = false;
            }

            Log.d("Brightness", "Avg: " + avgBrightness + " | Torch: " + torchOn);
        }
    };

    /** 🧠 Load TensorFlow Model */
    private MappedByteBuffer loadModelFile() throws IOException {
        AssetFileDescriptor fileDescriptor = this.getAssets().openFd("yolov5n-fp16.tflite");
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    /** 🎤 Voice Recognition */
    private void startVoiceRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, isEnglish ? "en-US" : "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, isEnglish ? "Say Detect or Stop" : "کمانڈ بولیں: ڈیٹیکٹ یا سٹاپ");

        try {
            startActivityForResult(intent, VOICE_RECOGNITION_REQUEST);
        } catch (Exception e) {
            speak(isEnglish ? "Voice recognition not supported on this device" :
                    "وائس کمانڈ اس ڈیوائس پر دستیاب نہیں ہے");
        }
    }

    /** 🎤 Handle Voice Commands */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOICE_RECOGNITION_REQUEST && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                String command = results.get(0).toLowerCase(Locale.ROOT);
                if (command.contains("detect") || command.contains("ڈیٹیکٹ")) {
                    speak("Object ahead");
                } else if (command.contains("stop") || command.contains("سٹاپ")) {
                    speak("Navigation stopped");
                } else {
                    speak("Command not recognized");
                }
            }
        }
    }

    /** 🌐 Language Toggle */
    private void toggleLanguage() {
        if (isEnglish) {
            int result = tts.setLanguage(new Locale("ur", "PK"));
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Urdu TTS not available on this device", Toast.LENGTH_SHORT).show();
                speak("Urdu language not supported on this device");
                return;
            }
            speak("زبان اردو میں بدل دی گئی ہے");
            btnLanguage.setText("Switch to English");
        } else {
            tts.setLanguage(Locale.ENGLISH);
            speak("Language changed to English");
            btnLanguage.setText("Switch to Urdu");
        }
        isEnglish = !isEnglish;
    }

    /** ⚙️ Permissions */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE &&
                grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
        }
    }

    /** 🧹 Cleanup */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (camera != null) {
            camera.stopPreview();
            camera.release();
        }
    }

    @Override public void surfaceChanged(@NonNull SurfaceHolder h, int f, int w, int he) {}
    @Override public void surfaceDestroyed(@NonNull SurfaceHolder h) {
        if (camera != null) {
            camera.stopPreview();
            camera.release();
            camera = null;
        }
        isCameraOn = false;
    }
}

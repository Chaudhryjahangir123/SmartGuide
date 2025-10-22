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
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import java.util.Random;
import org.tensorflow.lite.Interpreter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import android.content.res.AssetFileDescriptor;





public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private SurfaceView cameraPreview;
    private SurfaceHolder surfaceHolder;
    private Camera camera;
    private TextToSpeech tts;
    private Button btnDetect, btnVoice, btnLanguage;

    private static final int CAMERA_PERMISSION_CODE = 100;
    private static final int VOICE_RECOGNITION_REQUEST = 200;

    private long lastBrightnessCheckTime = 0;
    private static final int BRIGHTNESS_INTERVAL = 3000; // check every 3 seconds

    private boolean isCameraOn = false;
    private boolean isEnglish = true;
    private final String[] fakeObjects = {"door", "wall", "person", "stairs", "chair"};
    private final Random random = new Random();

    private Interpreter tflite;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cameraPreview = findViewById(R.id.cameraPreview);
        btnDetect = findViewById(R.id.btnDetect);
        btnVoice = findViewById(R.id.btnVoice);
        btnLanguage = findViewById(R.id.btnLanguage);

        // Initialize TTS
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.ENGLISH);
                tts.setSpeechRate(1.0f);
            }
        });
        try {
            tflite = new Interpreter(loadModelFile());
            Toast.makeText(this, "Model loaded successfully", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Model loading failed", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }


        // Camera permission
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        } else {
            openCamera();
        }

        // Detect button
        btnDetect.setOnClickListener(v -> {
            if (isCameraOn) speak("Object ahead");
            else speak("Camera not ready");
        });
        btnDetect.setOnClickListener(v -> {
            if (isCameraOn) {
                speak("Model is ready — add real input here");
            } else {
                speak("Camera not ready");
            }
        });
        // Voice command button
        btnVoice.setOnClickListener(v -> startVoiceRecognition());

        // Language toggle button
        btnLanguage.setOnClickListener(v -> toggleLanguage());
    }

    // Unified speak() that auto-translates when Urdu is active
    private void speak(String message) {
        if (!isEnglish) {
            if (message.equalsIgnoreCase("Object ahead"))
                message = "سامنے رکاوٹ موجود ہے";
            else if (message.equalsIgnoreCase("Navigation stopped"))
                message = "نیویگیشن بند کر دی گئی ہے";
            else if (message.equalsIgnoreCase("Command not recognized"))
                message = "کمانڈ سمجھ میں نہیں آئی";
            else if (message.equalsIgnoreCase("Camera not ready"))
                message = "کیمرہ تیار نہیں ہے";
        }
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    // Camera setup
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

// Enable continuous auto-focus if supported
            if (params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if (params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }

// Set highest possible preview size for better clarity
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

// Optional small delay before setting callback to stabilize focus
            new android.os.Handler().postDelayed(() -> {
                camera.setPreviewCallback(previewCallback);
            }, 500);


        } catch (IOException | RuntimeException e) {
            Toast.makeText(this, "Camera unavailable", Toast.LENGTH_SHORT).show();
        }
    }
    private final Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            // (your existing brightness + YOLO detection code here)
            // --- Brightness Detection ---
            int frameSum = 0;
            int sampleCount = 0;

// Sample every few pixels (not too dense for performance)
            for (int i = 0; i < data.length; i += 500) {
                frameSum += (data[i] & 0xFF);
                sampleCount++;
            }

            int avgBrightness = frameSum / sampleCount;
            long currentTime = System.currentTimeMillis();

// Check brightness only every 3 seconds
            if (currentTime - lastBrightnessCheckTime > BRIGHTNESS_INTERVAL) {
                lastBrightnessCheckTime = currentTime;

                if (avgBrightness < 60) {
                    speak("Low light detected. Please turn on the flashlight.");
                } else if (avgBrightness > 180) {
                    speak("Bright light detected.");
                }
            }

        }
    };


    private MappedByteBuffer loadModelFile() throws IOException {
        AssetFileDescriptor fileDescriptor = this.getAssets().openFd("yolov5n-fp16.tflite");
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    private void runObjectDetection(android.graphics.Bitmap bitmap) {
        // Resize to model input size (YOLOv5n uses 640x640)
        android.graphics.Bitmap resized = android.graphics.Bitmap.createScaledBitmap(bitmap, 640, 640, true);

        int inputSize = 640;
        int[] intValues = new int[inputSize * inputSize];
        resized.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize);

        float[][][][] input = new float[1][inputSize][inputSize][3];
        for (int i = 0; i < intValues.length; ++i) {
            int pixel = intValues[i];
            input[0][i / inputSize][i % inputSize][0] = ((pixel >> 16) & 0xFF) / 255.0f;
            input[0][i / inputSize][i % inputSize][1] = ((pixel >> 8) & 0xFF) / 255.0f;
            input[0][i / inputSize][i % inputSize][2] = (pixel & 0xFF) / 255.0f;
        }

        // Output buffer (simplified for now)
        float[][] output = new float[1][1000]; // adjust shape later

        try {
            tflite.run(input, output);
            speak("Object detected"); // temporary feedback
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override public void surfaceChanged(@NonNull SurfaceHolder h, int f, int w, int he) { }
    @Override public void surfaceDestroyed(@NonNull SurfaceHolder h) {
        if (camera != null) {
            camera.stopPreview();
            camera.release();
            camera = null;
        }
        isCameraOn = false;
    }

    // Voice recognition
    private void startVoiceRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say Detect or Stop");
        try {
            startActivityForResult(intent, VOICE_RECOGNITION_REQUEST);
        } catch (Exception e) {
            speak(isEnglish ? "Voice recognition not supported on this device"
                    : "وائس کمانڈ اس ڈیوائس پر دستیاب نہیں ہے");
        }
    }



    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOICE_RECOGNITION_REQUEST && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                String command = results.get(0).toLowerCase(Locale.ROOT);
                if (command.contains("detect")) {
                    speak("Object ahead");
                } else if (command.contains("stop")) {
                    speak("Navigation stopped");
                } else {
                    speak("Command not recognized");
                }
            }
        }
    }


    // Language toggle
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
}

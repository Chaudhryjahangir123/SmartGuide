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

        // Voice command button
        btnVoice.setOnClickListener(v -> startVoiceRecognition());

        // Language toggle button
        btnLanguage.setOnClickListener(v -> toggleLanguage());
    }

    private void speak(String message) {
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    private void openCamera() {
        surfaceHolder = cameraPreview.getHolder();
        surfaceHolder.addCallback(this);
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        try {
            camera = Camera.open();
            camera.setPreviewDisplay(holder);
            camera.startPreview();
            isCameraOn = true;
        } catch (IOException | RuntimeException e) {
            Toast.makeText(this, "Camera unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void surfaceChanged(@NonNull SurfaceHolder h, int f, int w, int he) {}
    @Override public void surfaceDestroyed(@NonNull SurfaceHolder h) {
        if (camera != null) { camera.stopPreview(); camera.release(); camera = null; }
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
            speak("Voice recognition not supported on this device");
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
                    speak(isEnglish ? "Object ahead" : "سامنے رکاوٹ موجود ہے");
                } else if (command.contains("stop")) {
                    speak(isEnglish ? "Navigation stopped" : "نیویگیشن بند کر دی گئی ہے");
                } else {
                    speak(isEnglish ? "Command not recognized" : "کمانڈ سمجھ میں نہیں آئی");
                }
            }
        }
    }

    // Toggle between English and Urdu
    private void toggleLanguage() {
        if (isEnglish) {
            tts.setLanguage(new Locale("ur", "PK"));
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
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (camera != null) { camera.stopPreview(); camera.release(); }
    }
}

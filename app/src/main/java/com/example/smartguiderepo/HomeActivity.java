package com.example.smartguiderepo;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.Locale;

public class HomeActivity extends AppCompatActivity {

    private static final int VOICE_REQ = 101;
    private TextToSpeech tts;
    private Vibrator vibrator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        RelativeLayout btnMic = findViewById(R.id.btnMic);
        LinearLayout btnHome = findViewById(R.id.btnHome);
        LinearLayout btnCamera = findViewById(R.id.btnCamera);
        LinearLayout btnFind = findViewById(R.id.btnFind);
        LinearLayout btnLanguage = findViewById(R.id.btnLanguage);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                // Initial setup
                if (AppSettings.isEnglish) tts.setLanguage(Locale.US);
                else tts.setLanguage(new Locale("ur", "PK"));
            }
        });

        btnHome.setOnClickListener(v -> {
            triggerShortVibration();
            speak("You are already on the Home screen", "آپ پہلے ہی ہوم اسکرین پر ہیں");
        });

        btnCamera.setOnClickListener(v -> {
            triggerShortVibration();
            Intent intent = new Intent(HomeActivity.this, DetectionActivity.class);
            intent.putExtra("MODE", "General");
            startActivity(intent);
        });

        btnFind.setOnClickListener(v -> {
            triggerShortVibration();
            Intent intent = new Intent(HomeActivity.this, DetectionActivity.class);
            intent.putExtra("MODE", "Finder");
            startActivity(intent);
        });

        btnLanguage.setOnClickListener(v -> {
            triggerShortVibration();
            AppSettings.isEnglish = !AppSettings.isEnglish;
            if (AppSettings.isEnglish) {
                speak("English Selected", "English Selected");
            } else {
                speak("Urdu Selected", "اردو منتخب کی گئی ہے");
            }
        });

        btnMic.setOnClickListener(v -> startVoice());
    }

    private void triggerShortVibration() {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(100);
            }
        }
    }

    // UPDATED: Forces language check every time it speaks
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

    private void startVoice() {
        triggerShortVibration();
        speak("What do you want to find?", "آپ کیا تلاش کرنا چاہتے ہیں؟");

        findViewById(android.R.id.content).postDelayed(() -> {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, AppSettings.isEnglish ? "en-US" : "ur-PK");
            startActivityForResult(intent, VOICE_REQ);
        }, 1800);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOICE_REQ && resultCode == RESULT_OK && data != null) {
            ArrayList<String> result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            String command = result.get(0).toLowerCase();

            if (command.contains("start") || command.contains("camera") || command.contains("shuru")) {
                startActivity(new Intent(this, DetectionActivity.class));
            } else if (command.contains("find") || command.contains("talaash")) {
                Intent intent = new Intent(this, DetectionActivity.class);
                intent.putExtra("MODE", "Finder");
                String target = command.replace("find", "").replace("talaash", "").replace("karein", "").trim();
                intent.putExtra("TARGET", target);
                startActivity(intent);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}
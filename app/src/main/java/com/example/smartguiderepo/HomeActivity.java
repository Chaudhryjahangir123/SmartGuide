package com.example.smartguiderepo;

import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.Locale;

public class HomeActivity extends AppCompatActivity {

    private static final int VOICE_REQ = 101;
    private TextToSpeech tts; // Added TTS

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        RelativeLayout btnMic = findViewById(R.id.btnMic);
        ImageView btnCamera = findViewById(R.id.btnCamera);
        ImageView btnFind = findViewById(R.id.btnFind);
        ImageView btnLanguage = findViewById(R.id.btnLanguage);

        // 1. Initialize TTS
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
            }
        });

        // 2. Language Button Logic (The Fix)
        btnLanguage.setOnClickListener(v -> {
            // Toggle Global Setting
            AppSettings.isEnglish = !AppSettings.isEnglish;

            if (AppSettings.isEnglish) {
                // Switch to English
                tts.setLanguage(Locale.US);
                speak("English Selected");
                Toast.makeText(this, "English Selected", Toast.LENGTH_SHORT).show();
                btnLanguage.setImageAlpha(255); // Full Brightness

                // Optional: Update UI Text
                // tvMicLabel.setText("Tap to Speak");
            } else {
                // Switch to Urdu
                // Note: Android TTS might fall back to default if Urdu isn't installed.
                // We send the Urdu text, and if the engine supports it, it speaks.
                speak("اردو منتخب کی گئی ہے"); // "Urdu Muntakhib Ki Gayi Hai"
                Toast.makeText(this, "Urdu Selected", Toast.LENGTH_SHORT).show();
                btnLanguage.setImageAlpha(128); // Dimmed to show difference

                // Optional: Update UI Text
                // tvMicLabel.setText("بولنے کے لیے ٹیپ کریں");
            }
        });

        btnMic.setOnClickListener(v -> startVoice());

        btnCamera.setOnClickListener(v -> {
            Intent intent = new Intent(HomeActivity.this, DetectionActivity.class);
            intent.putExtra("MODE", "General");
            startActivity(intent);
        });

        btnFind.setOnClickListener(v -> {
            Intent intent = new Intent(HomeActivity.this, DetectionActivity.class);
            intent.putExtra("MODE", "Finder");
            startActivity(intent);
        });
    }

    private void speak(String text) {
        if (tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    private void startVoice() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, AppSettings.isEnglish ? "en-US" : "ur-PK");
        startActivityForResult(intent, VOICE_REQ);
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
                // Remove "find" or "talaash karein" from the string
                String target = command.replace("find", "").replace("talaash karein", "").trim();
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
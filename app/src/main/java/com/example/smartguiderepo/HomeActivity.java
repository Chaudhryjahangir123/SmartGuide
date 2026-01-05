package com.example.smartguiderepo;

import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.widget.LinearLayout; // Updated Import
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.Locale;

public class HomeActivity extends AppCompatActivity {

    private static final int VOICE_REQ = 101;
    private TextToSpeech tts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        // UI Element Initialization
        RelativeLayout btnMic = findViewById(R.id.btnMic);

        // FIXED: Changed from ImageView to LinearLayout to match your new XML
        LinearLayout btnHome = findViewById(R.id.btnHome);
        LinearLayout btnCamera = findViewById(R.id.btnCamera);
        LinearLayout btnFind = findViewById(R.id.btnFind);
        LinearLayout btnLanguage = findViewById(R.id.btnLanguage);

        // Initialize TTS
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
            }
        });

        // 1. Home Button (Already on Home, so maybe just provide feedback)
        btnHome.setOnClickListener(v -> {
            speak("You are already on the Home screen");
        });

        // 2. Camera Button Logic
        btnCamera.setOnClickListener(v -> {
            Intent intent = new Intent(HomeActivity.this, DetectionActivity.class);
            intent.putExtra("MODE", "General");
            startActivity(intent);
        });

        // 3. Find/Search Button Logic
        btnFind.setOnClickListener(v -> {
            Intent intent = new Intent(HomeActivity.this, DetectionActivity.class);
            intent.putExtra("MODE", "Finder");
            startActivity(intent);
        });

        // 4. Language Button Logic
        btnLanguage.setOnClickListener(v -> {
            AppSettings.isEnglish = !AppSettings.isEnglish;

            if (AppSettings.isEnglish) {
                tts.setLanguage(Locale.US);
                speak("English Selected");
                Toast.makeText(this, "English Selected", Toast.LENGTH_SHORT).show();
            } else {
                speak("اردو منتخب کی گئی ہے");
                Toast.makeText(this, "Urdu Selected", Toast.LENGTH_SHORT).show();
            }
        });

        btnMic.setOnClickListener(v -> startVoice());
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
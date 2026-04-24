package com.example.smartguiderepo;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.Locale;

public class HomeActivity extends AppCompatActivity {
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean isSpeechInitialized = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        // Speech Intent setup
        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { stopListening(); }
                    @Override public void onDone(String id) { startListening(); }
                    @Override public void onError(String id) { startListening(); }
                });
                speak("Welcome. Say Start.", "خوش آمدید۔ شروع بولیں۔");
            }
        });

        initSpeech();

        // UI Click Listeners (Compressed)
        findViewById(R.id.btnCamera).setOnClickListener(v -> goToDetection("General", ""));
        findViewById(R.id.btnFind).setOnClickListener(v -> goToDetection("Finder", ""));
        findViewById(R.id.btnLanguage).setOnClickListener(v -> toggleLanguage());

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
    }

    private void initSpeech() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onResults(Bundle b) {
                ArrayList<String> matches = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null) handleCommand(matches.get(0).toLowerCase());
                startListening();
            }
            @Override public void onError(int e) { startListening(); }
            @Override public void onReadyForSpeech(Bundle p) {} @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float r) {} @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {} @Override public void onPartialResults(Bundle p) {}
            @Override public void onEvent(int t, Bundle p) {}
        });
        isSpeechInitialized = true;
        startListening();
    }

    private void handleCommand(String cmd) {
        if (cmd.contains("start") || cmd.contains("shuru") || cmd.contains("camera")) {
            goToDetection("General", "");
        } else if (cmd.contains("find") || cmd.contains("search") || cmd.contains("talash")) {
            String target = cmd.replaceAll(".*(find|search|talash|dhundo)", "").trim();
            goToDetection("Finder", target.isEmpty() ? "object" : target);
        } else if (cmd.contains("language") || cmd.contains("zaban")) {
            toggleLanguage();
        }
    }

    private void goToDetection(String mode, String target) {
        stopListening();
        if (speechRecognizer != null) { speechRecognizer.destroy(); isSpeechInitialized = false; }
        Intent i = new Intent(this, DetectionActivity.class);
        i.putExtra("MODE", mode); i.putExtra("TARGET", target);
        startActivity(i);
    }

    private void toggleLanguage() {
        AppSettings.isEnglish = !AppSettings.isEnglish;
        speak(AppSettings.isEnglish ? "English Selected" : "Urdu Selected", AppSettings.isEnglish ? "English" : "اردو منتخب");
    }

    private void startListening() { if (isSpeechInitialized) runOnUiThread(() -> speechRecognizer.startListening(speechIntent)); }
    private void stopListening() { if (isSpeechInitialized) runOnUiThread(() -> speechRecognizer.stopListening()); }

    private void speak(String en, String ur) {
        if (tts != null) {
            tts.setLanguage(AppSettings.isEnglish ? Locale.US : new Locale("ur", "PK"));
            tts.speak(AppSettings.isEnglish ? en : ur, TextToSpeech.QUEUE_FLUSH, null, "ID");
        }
    }

    @Override protected void onResume() { super.onResume(); if(!isSpeechInitialized) initSpeech(); startListening(); }
    @Override protected void onPause() { super.onPause(); stopListening(); }
    @Override protected void onDestroy() { super.onDestroy(); if(speechRecognizer!=null) speechRecognizer.destroy(); tts.shutdown(); }
}
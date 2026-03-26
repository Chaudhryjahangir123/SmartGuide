package com.example.smartguiderepo;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.Locale;

public class HomeActivity extends AppCompatActivity {

    private TextToSpeech tts;
    private Vibrator vibrator;

    // Background Speech Recognition variables
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean isSpeechInitialized = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        // UI components (kept for accessibility/UI standards)
        RelativeLayout btnMic = findViewById(R.id.btnMic);
        LinearLayout btnHome = findViewById(R.id.btnHome);
        LinearLayout btnCamera = findViewById(R.id.btnCamera);
        LinearLayout btnFind = findViewById(R.id.btnFind);
        LinearLayout btnLanguage = findViewById(R.id.btnLanguage);

        // 1. Initialize Background Listening
        initSpeechRecognizer();

        // 2. Initialize TTS with Onboarding Logic
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                // Prevent app from "hearing itself" while speaking
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) { stopListening(); }
                    @Override public void onDone(String utteranceId) { startListening(); }
                    @Override public void onError(String utteranceId) { startListening(); }
                });

                // Auto-Onboarding for First Time Users
                if (AppSettings.isFirstTime(this)) {
                    speak(HelpManager.getFullOnboarding(true), HelpManager.getFullOnboarding(false));
                    AppSettings.setFirstTimeDone(this);
                } else {
                    speak("Welcome back to Smart Guide", "اسمارٹ گائیڈ میں خوش آمدید");
                }
            }
        });

        // UI Click Listeners (remain for standard navigation)
        btnHome.setOnClickListener(v -> {
            triggerShortVibration();
            speak("You are already on the Home screen", "آپ پہلے ہی ہوم اسکرین پر ہیں");
        });

        btnCamera.setOnClickListener(v -> {
            triggerShortVibration();
            startActivity(new Intent(HomeActivity.this, DetectionActivity.class).putExtra("MODE", "General"));
        });

        btnFind.setOnClickListener(v -> {
            triggerShortVibration();
            startActivity(new Intent(HomeActivity.this, DetectionActivity.class).putExtra("MODE", "Finder"));
        });

        btnLanguage.setOnClickListener(v -> {
            triggerShortVibration();
            toggleLanguage();
        });

        btnMic.setOnClickListener(v -> triggerShortVibration()); // Logic handled by background loop
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
                startListening(); // Restart loop immediately
            }

            @Override public void onError(int error) { startListening(); } // If timeout, restart loop
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
        Log.d("VoiceHome", "Command: " + command);

        // Feature: Open Camera/Indoor
        if (command.contains("camera") || command.contains("indoor") || command.contains("shuru")) {
            startActivity(new Intent(this, DetectionActivity.class).putExtra("MODE", "General"));
        }
        // Feature: Search for Objects
        else if (command.contains("find") || command.contains("search") || command.contains("talaash")) {
            String target = command.replace("find", "").replace("search", "").replace("talaash", "").trim();
            Intent intent = new Intent(this, DetectionActivity.class);
            intent.putExtra("MODE", "Finder");
            intent.putExtra("TARGET", target);
            startActivity(intent);
        }
        // Feature: Language Switch
        else if (command.contains("language") || command.contains("zaban")) {
            toggleLanguage();
        }
        // Feature: Universal Help
        else if (command.contains("help") || command.contains("madad") || command.contains("guide")) {
            speak(HelpManager.getFullOnboarding(true), HelpManager.getFullOnboarding(false));
        }
    }

    private void toggleLanguage() {
        AppSettings.isEnglish = !AppSettings.isEnglish;
        // Update the speech recognition language immediately
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, AppSettings.isEnglish ? "en-US" : "ur-PK");
        if (AppSettings.isEnglish) speak("English Selected", "English Selected");
        else speak("Urdu Selected", "اردو منتخب کی گئی ہے");
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

    private void speak(String englishText, String urduText) {
        if (tts != null) {
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "HomeID");
            if (AppSettings.isEnglish) {
                tts.setLanguage(Locale.US);
                tts.speak(englishText, TextToSpeech.QUEUE_FLUSH, params, "HomeID");
            } else {
                tts.setLanguage(new Locale("ur", "PK"));
                tts.speak(urduText, TextToSpeech.QUEUE_FLUSH, params, "HomeID");
            }
        }
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

    @Override
    protected void onResume() {
        super.onResume();
        startListening(); // Ensure we are listening when user returns to Home
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopListening(); // Stop listening when user moves to another screen
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (tts != null) { tts.stop(); tts.shutdown(); }
    }
}
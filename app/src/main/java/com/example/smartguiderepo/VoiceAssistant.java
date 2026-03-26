package com.example.smartguiderepo;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

public class VoiceAssistant {
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private VoiceCommandListener listener;
    private boolean isListening = false;

    public interface VoiceCommandListener {
        void onCommandReceived(String command);
    }

    public VoiceAssistant(Context context, VoiceCommandListener listener) {
        this.listener = listener;
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, AppSettings.isEnglish ? "en-US" : "ur-PK");

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    listener.onCommandReceived(matches.get(0).toLowerCase());
                }
                startListening();
            }

            @Override
            public void onError(int error) {
                startListening();
            }

            // Leave other methods empty
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    public void startListening() {
        if (!isListening) {
            speechRecognizer.startListening(speechIntent);
            isListening = true;
        }
    }

    public void stopListening() {
        speechRecognizer.stopListening();
        isListening = false;
    }

    public void shutdown() {
        speechRecognizer.destroy();
    }
}
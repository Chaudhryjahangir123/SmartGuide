package com.example.smartguiderepo;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import org.tensorflow.lite.task.vision.detector.Detection;
import org.tensorflow.lite.task.vision.detector.ObjectDetector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.tensorflow.lite.support.image.TensorImage;

public class ObjectDetectorHelper {

    private ObjectDetector detector;
    private final Context context;

    public ObjectDetectorHelper(Context context) {
        this.context = context;
        initDetector();
    }

    private void initDetector() {
        try {
            // Configure the detector
            ObjectDetector.ObjectDetectorOptions options =
                    ObjectDetector.ObjectDetectorOptions.builder()
                            .setMaxResults(3)          // Only keep top 3 objects
                            .setScoreThreshold(0.5f)   // 50% confidence required
                            .build();

            // Load the model from assets/detect.tflite
            detector = ObjectDetector.createFromFileAndOptions(
                    context,
                    "detect.tflite",
                    options
            );
        } catch (IOException e) {
            Log.e("SmartGuide", "Error loading model", e);
        }
    }

    public String analyzeImage(Bitmap image) {
        if (detector == null) return "Model not loaded";

        // --- FIX STARTS HERE ---
        // Convert the Android Bitmap to a TensorFlow TensorImage
        TensorImage tensorImage = TensorImage.fromBitmap(image);

        // Run detection on the tensorImage, NOT the raw bitmap
        List<Detection> results = detector.detect(tensorImage);
        // --- FIX ENDS HERE ---

        // Process results
        StringBuilder sb = new StringBuilder();
        for (Detection result : results) {
            String label = result.getCategories().get(0).getLabel();

            // SMARTGUIDE LOGIC: Only care about people or hazards
            if (label.equals("person") || label.equals("car") || label.equals("truck")) {
                sb.append(label).append(", ");
            }
        }

        if (sb.length() > 0) {
            return "Detected: " + sb.toString();
        } else {
            return "";
        }
    }
}
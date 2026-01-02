package com.example.smartguiderepo;

import android.content.Context;
import android.graphics.Bitmap;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.task.vision.detector.Detection;
import org.tensorflow.lite.task.vision.detector.ObjectDetector;
import java.io.IOException;
import java.util.List;

public class ObjectDetectorHelper {
    private ObjectDetector detector;

    public ObjectDetectorHelper(Context context) {
        try {
            // Adjust options for speed/accuracy
            ObjectDetector.ObjectDetectorOptions options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setMaxResults(3)
                    .setScoreThreshold(0.5f)
                    .build();

            // Loads the brain from assets/detect.tflite
            detector = ObjectDetector.createFromFileAndOptions(context, "detect.tflite", options);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public List<Detection> detect(Bitmap image) {
        if (detector == null) return null;
        // Converts Bitmap to Tensor format automatically
        return detector.detect(TensorImage.fromBitmap(image));
    }
}
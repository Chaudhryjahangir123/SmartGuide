package com.example.smartguiderepo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.common.ops.CastOp;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class YoloDetector {

    // Threshold reduced to 25% to make sure boxes appear even if confidence is low
    private final float CONFIDENCE_THRESHOLD = 0.25f;

    private Interpreter interpreter;
    private ImageProcessor imageProcessor;

    private int inputSize = 320;
    private int numClasses;
    private int numAnchors;

    public static class BoundingBox {
        public String label;
        public float score;
        public RectF box;

        public BoundingBox(String label, float score, RectF box) {
            this.label = label;
            this.score = score;
            this.box = box;
        }
    }

    public YoloDetector(Context context, String modelPath) throws IOException {
        MappedByteBuffer model = FileUtil.loadMappedFile(context, modelPath);
        Interpreter.Options options = new Interpreter.Options();
        interpreter = new Interpreter(model, options);

        // 1. READ INPUT SHAPE
        int[] inputShape = interpreter.getInputTensor(0).shape();
        if (inputShape.length > 1) {
            this.inputSize = inputShape[1];
        }
        Log.d("YOLO_FIX", "Model Input Size: " + inputSize);

        // 2. READ OUTPUT SHAPE
        int[] outputShape = interpreter.getOutputTensor(0).shape();

        // Handle [1, Channels, Anchors] vs [1, Anchors, Channels]
        if (outputShape[1] > outputShape[2]) {
            this.numClasses = outputShape[2] - 4;
            this.numAnchors = outputShape[1];
        } else {
            this.numClasses = outputShape[1] - 4;
            this.numAnchors = outputShape[2];
        }

        Log.d("YOLO_FIX", "Classes: " + numClasses + ", Anchors: " + numAnchors);

        // 3. IMAGE PROCESSOR
        imageProcessor = new ImageProcessor.Builder()
                .add(new ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
                .add(new CastOp(DataType.FLOAT32))
                .add(new NormalizeOp(0f, 255f))
                .build();
    }

    public List<BoundingBox> detect(Bitmap bitmap) {
        if (interpreter == null) return new ArrayList<>();

        try {
            TensorImage tensorImage = new TensorImage(DataType.FLOAT32);
            tensorImage.load(bitmap);
            tensorImage = imageProcessor.process(tensorImage);

            // Output buffer
            int dim1 = interpreter.getOutputTensor(0).shape()[1];
            int dim2 = interpreter.getOutputTensor(0).shape()[2];
            float[][][] output = new float[1][dim1][dim2];

            interpreter.run(tensorImage.getBuffer(), output);

            return bestBox(output, dim1, dim2);

        } catch (Exception e) {
            Log.e("YOLO_CRASH", "Inference Failed: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<BoundingBox> bestBox(float[][][] output, int dim1, int dim2) {
        List<BoundingBox> detections = new ArrayList<>();
        boolean isStandard = dim2 > dim1; // [1, 7, 2100]
        int anchors = isStandard ? dim2 : dim1;

        for (int i = 0; i < anchors; i++) {
            float maxScore = 0;
            int classId = -1;

            // Find best class score
            for (int c = 0; c < numClasses; c++) {
                float score = isStandard ? output[0][4 + c][i] : output[0][i][4 + c];
                if (score > maxScore) {
                    maxScore = score;
                    classId = c;
                }
            }

            if (maxScore > CONFIDENCE_THRESHOLD) {
                // Get Raw Coordinates
                float cx = isStandard ? output[0][0][i] : output[0][i][0];
                float cy = isStandard ? output[0][1][i] : output[0][i][1];
                float w = isStandard ? output[0][2][i] : output[0][i][2];
                float h = isStandard ? output[0][3][i] : output[0][i][3];

                // --- AUTO-SCALING LOGIC (THE FIX) ---
                // If the model gives pixels (e.g., 160.0), divide by inputSize (320) -> 0.5
                // If the model gives normalized (e.g., 0.5), do NOT divide.

                if (cx > 1.0f || cy > 1.0f || w > 1.0f || h > 1.0f) {
                    cx /= inputSize;
                    cy /= inputSize;
                    w /= inputSize;
                    h /= inputSize;
                }

                // Convert center-x/y to left/top/right/bottom (Normalized 0..1)
                float left = cx - (w / 2);
                float top = cy - (h / 2);
                float right = cx + (w / 2);
                float bottom = cy + (h / 2);

                // Ensure they are within 0..1 bounds
                if (left < 0) left = 0;
                if (top < 0) top = 0;
                if (right > 1) right = 1;
                if (bottom > 1) bottom = 1;

                detections.add(new BoundingBox(getLabel(classId), maxScore, new RectF(left, top, right, bottom)));
            }
        }

        if (!detections.isEmpty()) {
            Collections.sort(detections, (a, b) -> Float.compare(b.score, a.score));
            List<BoundingBox> best = new ArrayList<>();
            best.add(detections.get(0));
            return best;
        }
        return detections;
    }

    private String getLabel(int id) {
        // Updated based on your previous test results
        // You mentioned: "on paper asking scissor and detect scissor and rock"
        // This suggests IDs are likely:
        if (id == 0) return "Rock";
        if (id == 1) return "Paper";
        if (id == 2) return "Scissors";
        return "ID " + id;
    }
}
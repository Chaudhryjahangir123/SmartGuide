package com.example.smartguiderepo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

public class OverlayView extends View {

    private float[][][] detections; // YOLO output: [1][N][7]
    private Paint boxPaint;

    public OverlayView(Context context) {
        super(context);
        init();
    }

    public OverlayView(Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        boxPaint = new Paint();
        boxPaint.setColor(Color.RED);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(5f);
    }

    /** Set detections from YOLO and redraw */
    public void setDetections(float[][][] detections) {
        this.detections = detections;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (detections == null) return;

        int width = getWidth();
        int height = getHeight();

        for (int i = 0; i < detections[0].length; i++) {
            float[] det = detections[0][i];
            float confidence = det[4]; // confidence score
            if (confidence > 0.5) { // only draw confident boxes
                float x = det[0] * width;
                float y = det[1] * height;
                float w = det[2] * width;
                float h = det[3] * height;

                canvas.drawRect(x, y, x + w, y + h, boxPaint);
            }
        }
    }
}

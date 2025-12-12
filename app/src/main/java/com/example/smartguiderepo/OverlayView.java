package com.example.smartguiderepo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import java.util.List;

public class OverlayView extends View {

    private List<YoloDetector.BoundingBox> boxes;
    private Paint boxPaint;
    private Paint textPaint;

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        boxPaint = new Paint();
        boxPaint.setColor(Color.GREEN);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(8f);

        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(50f);
        textPaint.setStyle(Paint.Style.FILL);
    }

    public void setDetections(List<YoloDetector.BoundingBox> boxes) {
        this.boxes = boxes;
        invalidate(); // Force redraw
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (boxes == null) return;

        int width = getWidth();
        int height = getHeight();

        for (YoloDetector.BoundingBox box : boxes) {
            // Convert normalized coordinates (0..1) to screen pixels
            float left = box.box.left * width;
            float top = box.box.top * height;
            float right = box.box.right * width;
            float bottom = box.box.bottom * height;

            // Draw Box
            canvas.drawRect(left, top, right, bottom, boxPaint);

            // Draw Label
            canvas.drawText(box.label + " " + (int)(box.score*100) + "%", left, top - 10, textPaint);
        }
    }
}
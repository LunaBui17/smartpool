package com.buiducluan23011767.ck;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public final class SystemStatusView extends View {
    enum State { ON, OFF, UNKNOWN, WAIT, ERROR }

    static final class Node {
        final String title;
        final String status;
        final String detail;
        final State state;

        Node(String title, String status, String detail, State state) {
            this.title = title;
            this.status = status;
            this.detail = detail;
            this.state = state;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Node> nodes = new ArrayList<>();

    public SystemStatusView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setNodes(List<Node> updated) {
        boolean resized = nodes.size() != updated.size();
        nodes.clear();
        nodes.addAll(updated);
        StringBuilder description = new StringBuilder();
        for (Node node : nodes) {
            description.append(node.title).append(": ").append(node.status)
                    .append(". ").append(node.detail).append(".\n");
        }
        setContentDescription(description.toString());
        if (resized) requestLayout();
        invalidate();
    }

    private float nodeHeight() {
        return Math.max(dp(90), sp(14) * 5 + dp(16));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int rows = nodes.isEmpty() ? 1 : 1 + nodes.size() / 2;
        int height = (int) Math.ceil(dp(16) + rows * (nodeHeight() + dp(28)));
        setMeasuredDimension(resolveSize((int) dp(360), widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    private RectF box(int index) {
        float margin = dp(8);
        float right = getWidth() - dp(22);
        float halfWidth = (right - margin - dp(16)) / 2;
        if (index == 0) {
            return new RectF(getWidth() * 0.18f, margin, getWidth() * 0.82f,
                    margin + nodeHeight());
        }
        int row = 1 + (index - 1) / 2;
        float left = margin + ((index - 1) % 2) * (halfWidth + dp(16));
        float top = margin + row * (nodeHeight() + dp(28));
        return new RectF(left, top, left + halfWidth, top + nodeHeight());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (nodes.isEmpty()) return;
        paint.setColor(getContext().getColor(R.color.text_muted));
        paint.setStrokeWidth(dp(1.5f));
        paint.setStyle(Paint.Style.STROKE);
        if (nodes.size() >= 5) {
            RectF app = box(0);
            float splitY = app.bottom + dp(12);
            canvas.drawLine(app.centerX(), app.bottom, app.centerX(), splitY, paint);
            canvas.drawLine(box(1).centerX(), splitY, box(2).centerX(), splitY, paint);
            arrow(canvas, box(1).centerX(), splitY, box(1).top);
            arrow(canvas, box(2).centerX(), splitY, box(2).top);
            arrow(canvas, box(1).centerX(), box(1).bottom, box(3).top);
            arrow(canvas, box(2).centerX(), box(2).bottom, box(4).top);
            if (nodes.size() > 5) {
                RectF esp = box(4);
                float trunk = getWidth() - dp(6);
                float startY = esp.bottom + dp(10);
                canvas.drawLine(esp.centerX(), esp.bottom, esp.centerX(), startY, paint);
                canvas.drawLine(esp.centerX(), startY, trunk, startY, paint);
                canvas.drawLine(trunk, startY, trunk, box(nodes.size() - 1).top - dp(12), paint);
                for (int i = 5; i < nodes.size(); i += 2) {
                    float branchY = box(i).top - dp(12);
                    canvas.drawLine(box(i).centerX(), branchY, trunk, branchY, paint);
                    arrow(canvas, box(i).centerX(), branchY, box(i).top);
                    if (i + 1 < nodes.size()) {
                        arrow(canvas, box(i + 1).centerX(), branchY, box(i + 1).top);
                    }
                }
            }
        }
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            RectF bounds = box(i);
            int color = stateColor(node.state);
            paint.setColor(getContext().getColor(R.color.surface_card));
            canvas.drawRoundRect(bounds, dp(12), dp(12), paint);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRoundRect(bounds, dp(12), dp(12), paint);
            paint.setStyle(Paint.Style.FILL);
            float x = bounds.left + dp(10);
            float width = bounds.width() - dp(20);
            float y = bounds.top + dp(12) - textAscent(13);
            paint.setColor(getContext().getColor(R.color.text_primary));
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            text(canvas, node.title, x, y, width, 13);
            y += paint.getFontSpacing() + dp(6);
            paint.setColor(color);
            text(canvas, node.status, x, y, width, 13);
            y += paint.getFontSpacing() + dp(6);
            paint.setTypeface(Typeface.DEFAULT);
            paint.setColor(getContext().getColor(R.color.text_secondary));
            text(canvas, node.detail, x, y, width, 11);
        }
    }

    private float textAscent(int size) {
        paint.setTextSize(sp(size));
        return paint.ascent();
    }

    private void text(Canvas canvas, String value, float x, float y, float width, int size) {
        paint.setTextSize(sp(size));
        canvas.drawText(TextUtils.ellipsize(value, new android.text.TextPaint(paint),
                Math.max(0, width), TextUtils.TruncateAt.END).toString(), x, y, paint);
    }

    private void arrow(Canvas canvas, float x, float startY, float endY) {
        canvas.drawLine(x, startY, x, endY, paint);
        Path head = new Path();
        head.moveTo(x - dp(4), endY - dp(5));
        head.lineTo(x, endY);
        head.lineTo(x + dp(4), endY - dp(5));
        canvas.drawPath(head, paint);
    }

    private int stateColor(State state) {
        int resource;
        switch (state) {
            case ON: resource = R.color.success; break;
            case WAIT: resource = R.color.warning; break;
            case ERROR: resource = R.color.danger; break;
            case OFF: resource = R.color.text_secondary; break;
            default: resource = R.color.text_muted;
        }
        return getContext().getColor(resource);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}

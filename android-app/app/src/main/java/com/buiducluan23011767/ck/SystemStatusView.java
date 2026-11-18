package com.buiducluan23011767.ck;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** A compact status board for the phone screen. No state is inferred from its colour alone. */
public final class SystemStatusView extends LinearLayout {
    enum State { ON, OFF, UNKNOWN, WAIT, ERROR }
    private String renderedSnapshot;

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

    public SystemStatusView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
    }

    void setSnapshot(Node summary, List<Node> connections, List<Node> device) {
        StringBuilder snapshot = new StringBuilder();
        appendSnapshot(snapshot, summary);
        for (Node node : connections) appendSnapshot(snapshot, node);
        for (Node node : device) appendSnapshot(snapshot, node);
        String nextSnapshot = snapshot.toString();
        if (nextSnapshot.equals(renderedSnapshot)) return;
        renderedSnapshot = nextSnapshot;
        removeAllViews();
        addSummary(summary);
        addSection("KẾT NỐI", connections);
        addSection("THIẾT BỊ", device);
    }

    private void appendSnapshot(StringBuilder snapshot, Node node) {
        snapshot.append(node.title.length()).append(':').append(node.title)
                .append(node.status.length()).append(':').append(node.status)
                .append(node.detail.length()).append(':').append(node.detail)
                .append(node.state.ordinal());
    }

    private void addSummary(Node node) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(background(softColor(node.state), stateColor(node.state), 16));
        LayoutParams cardParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(8);
        addView(card, cardParams);

        TextView title = label(node.title, 18, R.color.text_primary, true);
        card.addView(title);
        TextView status = label(symbol(node.state) + "  " + node.status, 15, stateColor(node.state), true);
        LayoutParams statusParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(5);
        card.addView(status, statusParams);
        if (!node.detail.isEmpty()) {
            TextView detail = label(node.detail, 13, R.color.text_secondary, false);
            LayoutParams detailParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            detailParams.topMargin = dp(5);
            card.addView(detail, detailParams);
        }
    }

    private void addSection(String heading, List<Node> items) {
        TextView section = label(heading, 13, R.color.text_secondary, true);
        LayoutParams headingParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        headingParams.topMargin = dp(16);
        headingParams.bottomMargin = dp(8);
        addView(section, headingParams);

        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL);
        card.setBackground(background(R.color.surface_card, R.color.card_stroke, 16));
        addView(card, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                View divider = new View(getContext());
                divider.setBackgroundColor(getContext().getColor(R.color.line));
                LayoutParams dividerParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(1));
                dividerParams.leftMargin = dp(16);
                dividerParams.rightMargin = dp(16);
                card.addView(divider, dividerParams);
            }
            addRow(card, items.get(i));
        }
    }

    private void addRow(LinearLayout parent, Node node) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setMinimumHeight(dp(62));
        row.setContentDescription(node.title + ". " + node.status
                + (node.detail.isEmpty() ? "" : ". " + node.detail));
        parent.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout top = new LinearLayout(getContext());
        top.setOrientation(HORIZONTAL);
        top.setBaselineAligned(false);
        row.addView(top, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        TextView title = label(node.title, 15, R.color.text_primary, true);
        top.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        TextView status = label(symbol(node.state) + " " + node.status, 13, stateColor(node.state), true);
        status.setTextAlignment(TEXT_ALIGNMENT_VIEW_END);
        LayoutParams statusParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        statusParams.leftMargin = dp(8);
        top.addView(status, statusParams);

        if (!node.detail.isEmpty()) {
            TextView detail = label(node.detail, 12, R.color.text_secondary, false);
            LayoutParams detailParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            detailParams.topMargin = dp(4);
            row.addView(detail, detailParams);
        }
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(getContext().getColor(color));
        text.setIncludeFontPadding(false);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private GradientDrawable background(int fill, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(getContext().getColor(fill));
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), getContext().getColor(stroke));
        return shape;
    }

    private String symbol(State state) {
        switch (state) {
            case ON: return "●";
            case WAIT: return "◐";
            case ERROR: return "!";
            case OFF: return "○";
            default: return "·";
        }
    }

    private int stateColor(State state) {
        switch (state) {
            case ON: return R.color.success;
            case WAIT: return R.color.warning;
            case ERROR: return R.color.danger;
            case OFF: return R.color.text_secondary;
            default: return R.color.text_muted;
        }
    }

    private int softColor(State state) {
        switch (state) {
            case ON: return R.color.success_soft;
            case WAIT: return R.color.warning_soft;
            case ERROR: return R.color.danger_soft;
            default: return R.color.surface_elevated;
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

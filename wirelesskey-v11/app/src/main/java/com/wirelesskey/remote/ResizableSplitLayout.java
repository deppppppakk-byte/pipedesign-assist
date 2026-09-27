package com.wirelesskey.remote;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

public final class ResizableSplitLayout extends LinearLayout {
    public interface OnRatioChangedListener {
        void onRatioChanged(float ratio);
    }

    private final View divider;
    private View first;
    private View second;
    private float ratio = 0.70f;
    private float downX;
    private float startRatio;
    private OnRatioChangedListener ratioListener;

    public ResizableSplitLayout(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        divider = new View(context);
        divider.setBackgroundColor(Color.rgb(38,44,54));
        divider.setOnTouchListener(this::onDividerTouch);
    }

    public void setOnRatioChangedListener(OnRatioChangedListener listener) {
        ratioListener = listener;
    }

    public void setPanels(View firstPanel, View secondPanel, float initialRatio) {
        removeAllViews();
        first = firstPanel;
        second = secondPanel;
        ratio = clamp(initialRatio);

        addView(first, new LayoutParams(0, LayoutParams.MATCH_PARENT, ratio));

        LayoutParams dividerLp = new LayoutParams(dp(8), LayoutParams.MATCH_PARENT);
        dividerLp.leftMargin = dp(3);
        dividerLp.rightMargin = dp(3);
        addView(divider, dividerLp);

        addView(second, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f-ratio));
    }

    public float getRatio() {
        return ratio;
    }

    private boolean onDividerTouch(View v, MotionEvent event) {
        if (first == null || second == null) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                startRatio = ratio;
                v.setBackgroundColor(Color.rgb(61,112,191));
                return true;

            case MotionEvent.ACTION_MOVE:
                float total = Math.max(1f, getWidth() - divider.getWidth());
                ratio = clamp(startRatio + (event.getRawX()-downX)/total);
                applyRatio();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                v.setBackgroundColor(Color.rgb(38,44,54));
                if (ratioListener != null) ratioListener.onRatioChanged(ratio);
                return true;
        }
        return false;
    }

    private void applyRatio() {
        LayoutParams a = (LayoutParams)first.getLayoutParams();
        LayoutParams b = (LayoutParams)second.getLayoutParams();
        a.weight = ratio;
        b.weight = 1f-ratio;
        first.setLayoutParams(a);
        second.setLayoutParams(b);
    }

    private float clamp(float v) {
        return Math.max(0.40f, Math.min(0.84f, v));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

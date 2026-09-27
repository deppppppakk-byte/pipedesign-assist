package com.wirelesskey.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

public final class ResizableSplitLayout extends LinearLayout {
    public interface OnRatioChangedListener {
        void onRatioChanged(float ratio);
    }

    private final DividerHandle divider;
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

        divider = new DividerHandle(context);
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

        LayoutParams dividerLp = new LayoutParams(dp(10), LayoutParams.MATCH_PARENT);
        dividerLp.leftMargin = dp(2);
        dividerLp.rightMargin = dp(2);
        addView(divider, dividerLp);

        addView(second, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f-ratio));
    }

    public float getRatio() {
        return ratio;
    }

    public void setRatio(float value) {
        ratio = clamp(value);
        if (first != null && second != null) applyRatio();
        if (ratioListener != null) ratioListener.onRatioChanged(ratio);
    }

    public void resetBalanced() {
        setRatio(0.68f);
    }

    private boolean onDividerTouch(View v, MotionEvent event) {
        if (first == null || second == null) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                startRatio = ratio;
                divider.setActive(true);
                return true;

            case MotionEvent.ACTION_MOVE:
                float total = Math.max(1f, getWidth() - divider.getWidth());
                ratio = clamp(startRatio + (event.getRawX()-downX)/total);
                applyRatio();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                divider.setActive(false);
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

    private static final class DividerHandle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private boolean active;

        DividerHandle(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            setClickable(true);
        }

        void setActive(boolean value) {
            active = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(active ? Color.rgb(91,145,230) : Color.rgb(48,56,67));
            float w = active ? 2.4f*density : 1.4f*density;
            float cx = getWidth()/2f;
            float top = getHeight()*0.35f;
            float bottom = getHeight()*0.65f;
            canvas.drawRoundRect(
                    cx-w/2f, top, cx+w/2f, bottom,
                    w, w, paint);
        }
    }
}

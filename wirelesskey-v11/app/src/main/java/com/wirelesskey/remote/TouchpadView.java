package com.wirelesskey.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public final class TouchpadView extends View {
    public interface Sender {
        void send(JSONObject event);
        void move(float dx, float dy, long eventNanos);
        void scroll(boolean horizontal, float delta, long eventNanos);
        void haptic();
    }

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subtle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, float[]> pointers = new HashMap<>();
    private Sender sender;

    private int primaryId = -1;
    private float startX, startY;
    private boolean moved;
    private boolean dragging;
    private long downAt;
    private long lastTap;
    private float pendingDx, pendingDy;
    private boolean framePosted;
    private float sensitivity = 1.18f;
    private float acceleration = 0.035f;
    private float scrollSpeed = 1.0f;
    private boolean precisionMode = false;
    private boolean naturalScroll = false;

    private int maxGestureCount;
    private float gestureStartX, gestureStartY;
    private float gestureLastX, gestureLastY;
    private float gestureStartDistance, gestureLastDistance;
    private String gestureMode = "none";
    private boolean suppressUntilClear;

    private final float density;

    public TouchpadView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);

        bg.setColor(0xff101b2d);
        border.setColor(0xff334a69);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(1.2f * density);
        text.setColor(0xff9fb5d0);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(12f * density);
        accent.setColor(0xff3b82f6);
        subtle.setColor(0xff1d2b40);
    }

    public void setSensitivity(float value) {
        sensitivity = Math.max(0.45f, Math.min(2.4f, value));
    }

    public float getSensitivity() {
        return sensitivity;
    }

    public void setPrecisionMode(boolean enabled) {
        precisionMode = enabled;
        invalidate();
    }

    public boolean isPrecisionMode() {
        return precisionMode;
    }

    public void setNaturalScroll(boolean enabled) {
        naturalScroll = enabled;
    }

    public boolean isNaturalScroll() {
        return naturalScroll;
    }

    public void setScrollSpeed(float value) {
        scrollSpeed = Math.max(0.35f, Math.min(2.5f, value));
    }

    private float pointerGain(float dx, float dy) {
        float speed = (float)Math.hypot(dx, dy);
        float base = sensitivity * (precisionMode ? 0.58f : 1f);
        float accel = precisionMode ? acceleration * 0.18f : acceleration;
        return base * (1f + Math.min(speed, 24f) * accel);
    }

    public void setSender(Sender sender) {
        this.sender = sender;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float r = 14f * density;
        RectF rect = new RectF(1, 1, getWidth() - 1, getHeight() - 1);
        canvas.drawRoundRect(rect, r, r, bg);
        canvas.drawRoundRect(rect, r, r, border);

        float headerY = 26f * density;
        canvas.drawRoundRect(new RectF(12f*density,12f*density,getWidth()-12f*density,42f*density),
                10f*density,10f*density,subtle);

        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(10f * density);
        text.setColor(0xffdbeafe);
        canvas.drawText("PRECISION TOUCHPAD", 22f*density, headerY+4f*density, text);

        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(0xff7dd3fc);
        String mode = dragging ? "DRAG" : (pointers.size()>1 ? pointers.size()+" FINGERS" : "READY");
        canvas.drawText(mode, getWidth()-22f*density, headerY+4f*density, text);

        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xff536b86);
        text.setTextSize(8.5f * density);
        if (pointers.isEmpty()) {
            canvas.drawText(precisionMode ? "PRECISION" : "READY",
                    getWidth()/2f, getHeight()-18f*density, text);
        }

        if(!pointers.isEmpty()){
            float[] cc=centroid();
            accent.setStyle(Paint.Style.FILL);
            accent.setAlpha(42);
            canvas.drawCircle(cc[0],cc[1],18f*density,accent);
            accent.setAlpha(180);
            canvas.drawCircle(cc[0],cc[1],4f*density,accent);
            accent.setAlpha(255);
        }
    }

    private void emit(JSONObject obj) {
        if (sender != null && obj != null) sender.send(obj);
    }

    private void emitMouse(String button, String action) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "mouse");
            o.put("button", button);
            o.put("action", action);
            emit(o);
        } catch (Exception ignored) {}
    }

    private void emitKey(String key, String... mods) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "key");
            o.put("key", key);
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String m : mods) arr.put(m);
            o.put("modifiers", arr);
            emit(o);
        } catch (Exception ignored) {}
    }

    private float[] centroid() {
        if (pointers.isEmpty()) return new float[]{0,0};
        float sx=0, sy=0;
        for (float[] p : pointers.values()) { sx += p[0]; sy += p[1]; }
        return new float[]{sx / pointers.size(), sy / pointers.size()};
    }

    private float distance2() {
        if (pointers.size() < 2) return 0;
        float[][] ps = pointers.values().toArray(new float[0][]);
        return (float)Math.hypot(ps[0][0]-ps[1][0], ps[0][1]-ps[1][1]);
    }

    private void beginGesture() {
        float[] c = centroid();
        maxGestureCount = pointers.size();
        gestureStartX = gestureLastX = c[0];
        gestureStartY = gestureLastY = c[1];
        gestureStartDistance = gestureLastDistance = distance2();
        gestureMode = "undecided";
    }

    private void schedulePointerFlush() {
        if (framePosted) return;
        framePosted = true;
        postOnAnimation(() -> {
            framePosted = false;
            float dx = pendingDx;
            float dy = pendingDy;
            pendingDx = pendingDy = 0f;
            if (Math.abs(dx) + Math.abs(dy) > 0.08f && sender != null) {
                sender.move(dx, dy, SystemClock.elapsedRealtimeNanos());
            }
        });
    }

    private void emitWheel(boolean horizontal, float delta) {
        if (sender == null || Math.abs(delta) < 0.1f) return;
        float direction = naturalScroll ? -1f : 1f;
        sender.scroll(horizontal, delta * scrollSpeed * direction, SystemClock.elapsedRealtimeNanos());
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        final int action = e.getActionMasked();
        final int index = e.getActionIndex();
        final int id = e.getPointerId(index);

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (sender != null) sender.haptic();
            pointers.put(id, new float[]{e.getX(index), e.getY(index)});
            invalidate();
            if (pointers.size() == 1) {
                suppressUntilClear = false;
                primaryId = id;
                startX = e.getX(index);
                startY = e.getY(index);
                moved = false;
                dragging = false;
                downAt = SystemClock.uptimeMillis();
                postDelayed(() -> {
                    if (!moved && pointers.size()==1 && primaryId==id && !suppressUntilClear) {
                        dragging = true;
                        emitMouse("left", "down");
                    }
                }, 480);
            } else if (!suppressUntilClear) {
                if (dragging) {
                    dragging = false;
                    emitMouse("left", "up");
                }
                primaryId = -1;
                moved = true;
                beginGesture();
            }
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            for (int i=0;i<e.getPointerCount();i++) {
                int pid = e.getPointerId(i);
                float[] old = pointers.get(pid);
                float nx=e.getX(i), ny=e.getY(i);
                if (old == null) {
                    pointers.put(pid,new float[]{nx,ny});
                    continue;
                }

                float dx = nx-old[0], dy = ny-old[1];
                pointers.put(pid,new float[]{nx,ny});
                if (pointers.size() > 1) invalidate();

                if (suppressUntilClear) continue;

                if (pointers.size() >= 2) {
                    if ("none".equals(gestureMode)) beginGesture();
                    maxGestureCount = Math.max(maxGestureCount, pointers.size());

                    float[] c = centroid();
                    float totalX = c[0]-gestureStartX;
                    float totalY = c[1]-gestureStartY;
                    float cx = c[0]-gestureLastX;
                    float cy = c[1]-gestureLastY;
                    gestureLastX=c[0];
                    gestureLastY=c[1];

                    if (pointers.size()==2) {
                        float dist=distance2();
                        float distTravel=Math.abs(dist-gestureStartDistance);
                        float centroidTravel=Math.abs(totalX)+Math.abs(totalY);
                        float distDelta=dist-gestureLastDistance;
                        gestureLastDistance=dist;

                        if ("undecided".equals(gestureMode)) {
                            if (distTravel > 12*density && distTravel > centroidTravel*0.55f) {
                                gestureMode="pinch";
                            } else if (centroidTravel > 7*density) {
                                gestureMode=Math.abs(totalX)>Math.abs(totalY)*1.15f ? "hscroll" : "vscroll";
                            }
                        }

                        if ("pinch".equals(gestureMode) && Math.abs(distDelta)>2*density) {
                            emitKey(distDelta>0 ? "+" : "-", "CTRL");
                        } else if ("hscroll".equals(gestureMode)) {
                            emitWheel(true, cx*4.2f);
                        } else if ("vscroll".equals(gestureMode)) {
                            emitWheel(false, -cy*4.2f);
                        }
                    }
                    continue;
                }

                if (pid == primaryId) {
                    if (Math.abs(nx-startX)+Math.abs(ny-startY) > 5*density) moved=true;
                    float gain = pointerGain(dx,dy);
                    pendingDx += dx*gain;
                    pendingDy += dy*gain;
                    schedulePointerFlush();
                }
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            final int before = pointers.size();
            final int gestureCount = maxGestureCount;
            final String modeAtEnd = gestureMode;
            final float totalX = gestureLastX-gestureStartX;
            final float totalY = gestureLastY-gestureStartY;
            final long elapsed = SystemClock.uptimeMillis()-downAt;
            pointers.remove(id);
            pendingDx = pendingDy = 0f;
            invalidate();

            if (suppressUntilClear) {
                if (pointers.isEmpty()) suppressUntilClear=false;
                return true;
            }

            if (before >= 2) {
                if (gestureCount == 2 && "undecided".equals(modeAtEnd)
                        && Math.abs(totalX)+Math.abs(totalY) < 10*density && elapsed < 500) {
                    emitMouse("right", "click");
                } else if (gestureCount == 3) {
                    if (Math.abs(totalX) > Math.abs(totalY) && Math.abs(totalX)>55*density) {
                        if (totalX>0) emitKey("TAB","ALT");
                        else emitKey("TAB","ALT","SHIFT");
                    } else if (Math.abs(totalY)>55*density) {
                        if (totalY<0) emitKey("TAB","WIN");
                        else emitKey("D","WIN");
                    }
                } else if (gestureCount >= 4 && Math.abs(totalX)>55*density) {
                    emitKey(totalX>0?"RIGHT":"LEFT","CTRL","WIN");
                }
                suppressUntilClear = !pointers.isEmpty();
                primaryId=-1;
                gestureMode="none";
                return true;
            }

            if (id == primaryId) {
                long now=SystemClock.uptimeMillis();
                if (dragging) {
                    emitMouse("left","up");
                } else if (!moved) {
                    if (now-lastTap<320) emitMouse("left","double");
                    else emitMouse("left","click");
                    lastTap=now;
                }
                primaryId=-1;
                dragging=false;
                moved=false;
            }
            if (pointers.isEmpty()) {
                gestureMode="none";
                suppressUntilClear=false;
            }
            return true;
        }

        return super.onTouchEvent(e);
    }
}

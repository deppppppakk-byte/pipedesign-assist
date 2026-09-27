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
        void haptic();
    }

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        text.setColor(0xff7690ad);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(12f * density);
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
        canvas.drawText("Precision Touchpad", getWidth() / 2f, getHeight() / 2f, text);
        text.setTextSize(9f * density);
        canvas.drawText("Tap · Hold drag · 2-finger scroll/right-click · 3/4-finger gestures",
                getWidth() / 2f, getHeight() - 14f * density, text);
        text.setTextSize(12f * density);
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
            if (Math.abs(pendingDx) + Math.abs(pendingDy) > 0.2f) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("type", "move");
                    o.put("dx", pendingDx);
                    o.put("dy", pendingDy);
                    emit(o);
                } catch (Exception ignored) {}
                pendingDx = pendingDy = 0;
            }
        });
    }

    private void emitWheel(String type, float delta) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("delta", Math.round(delta));
            emit(o);
        } catch (Exception ignored) {}
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        final int action = e.getActionMasked();
        final int index = e.getActionIndex();
        final int id = e.getPointerId(index);

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (sender != null) sender.haptic();
            pointers.put(id, new float[]{e.getX(index), e.getY(index)});
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
                            emitWheel("hwheel", cx*5f);
                        } else if ("vscroll".equals(gestureMode)) {
                            emitWheel("wheel", -cy*5f);
                        }
                    }
                    continue;
                }

                if (pid == primaryId) {
                    if (Math.abs(nx-startX)+Math.abs(ny-startY) > 5*density) moved=true;
                    pendingDx += dx*1.55f;
                    pendingDy += dy*1.55f;
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

package in.tamishra.wirelesskey;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class TouchpadView extends View {
    private ConnectionManager connection;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lastX,lastY,downX,downY,lastTwoY;
    private long downAt;
    private int pendingDx,pendingDy;
    private boolean twoFinger;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable flush = new Runnable() {
        @Override public void run() {
            int dx=pendingDx,dy=pendingDy;
            pendingDx=pendingDy=0;
            if(connection!=null && (dx!=0 || dy!=0)) connection.sendMove(dx,dy);
            h.postDelayed(this,12);
        }
    };

    public TouchpadView(Context c){super(c);init();}
    public TouchpadView(Context c, AttributeSet a){super(c,a);init();}

    private void init(){
        setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        bg.setColor(0xFF0B1D2F);
        grid.setColor(0xFF173A55);
        grid.setStrokeWidth(1);
        hint.setColor(0xFF7895AE);
        hint.setTextSize(34);
        hint.setTextAlign(Paint.Align.CENTER);
        h.post(flush);
    }

    public void setConnection(ConnectionManager c){connection=c;}

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        float r=24;
        c.drawRoundRect(new RectF(0,0,getWidth(),getHeight()),r,r,bg);
        for(int x=40;x<getWidth();x+=40)c.drawLine(x,0,x,getHeight(),grid);
        for(int y=40;y<getHeight();y+=40)c.drawLine(0,y,getWidth(),y,grid);
        c.drawText("Touchpad",getWidth()/2f,getHeight()/2f,hint);
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getPointerCount()>=2){
            twoFinger=true;
            float y=(e.getY(0)+e.getY(1))/2f;
            if(e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN || e.getActionMasked()==MotionEvent.ACTION_DOWN) lastTwoY=y;
            else if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                float d=y-lastTwoY;
                if(Math.abs(d)>=2 && connection!=null){
                    connection.scroll((int)(-d*4));
                    lastTwoY=y;
                }
            }
            if(e.getActionMasked()==MotionEvent.ACTION_UP || e.getActionMasked()==MotionEvent.ACTION_CANCEL) twoFinger=false;
            return true;
        }
        float x=e.getX(),y=e.getY();
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                downX=lastX=x; downY=lastY=y; downAt=System.currentTimeMillis(); twoFinger=false; return true;
            case MotionEvent.ACTION_MOVE:
                if(!twoFinger){
                    float dx=x-lastX,dy=y-lastY;
                    float speed=(float)Math.sqrt(dx*dx+dy*dy);
                    float gain=speed>20?1.8f:speed>8?1.35f:1.0f;
                    pendingDx+=(int)(dx*gain); pendingDy+=(int)(dy*gain);
                    lastX=x; lastY=y;
                }
                return true;
            case MotionEvent.ACTION_UP:
                long dt=System.currentTimeMillis()-downAt;
                float dist=(float)Math.hypot(x-downX,y-downY);
                if(!twoFinger && dt<220 && dist<18 && connection!=null) connection.click("left");
                twoFinger=false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                twoFinger=false; return true;
        }
        return true;
    }

    @Override protected void onDetachedFromWindow(){
        h.removeCallbacks(flush);
        super.onDetachedFromWindow();
    }
}

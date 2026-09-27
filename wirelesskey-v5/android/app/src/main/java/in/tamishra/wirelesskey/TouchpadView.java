package in.tamishra.wirelesskey;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

public class TouchpadView extends View {
    private ConnectionManager connection;
    private final Paint bg=new Paint(Paint.ANTI_ALIAS_FLAG), grid=new Paint(Paint.ANTI_ALIAS_FLAG), hint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private float lastX,lastY,downX,downY,lastTwoY,twoStartY;
    private long downAt,twoDownAt;
    private int pendingDx,pendingDy;
    private boolean twoFinger,dragging;
    private int gainMode=1;

    private final Runnable flush=new Runnable(){@Override public void run(){int dx=pendingDx,dy=pendingDy;pendingDx=pendingDy=0;if(connection!=null&&(dx!=0||dy!=0))connection.sendMove(dx,dy);handler.postDelayed(this,12);}};
    private final Runnable longPressDrag=()->{if(!twoFinger && !dragging && connection!=null){dragging=true;performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);connection.mouseButton("left",true);invalidate();}};

    public TouchpadView(Context c){super(c);init();}
    public TouchpadView(Context c,android.util.AttributeSet a){super(c,a);init();}
    private void init(){setLayerType(View.LAYER_TYPE_SOFTWARE,null);bg.setColor(0xFF0C1520);grid.setColor(0xFF182A3C);grid.setStrokeWidth(1);hint.setColor(0xFF6F859B);hint.setTextSize(30);hint.setTextAlign(Paint.Align.CENTER);handler.post(flush);}
    public void setConnection(ConnectionManager c){connection=c;}
    public void setGainMode(int mode){gainMode=Math.max(0,Math.min(2,mode));invalidate();}

    @Override protected void onDraw(Canvas c){super.onDraw(c);c.drawRoundRect(new RectF(0,0,getWidth(),getHeight()),22,22,bg);for(int x=42;x<getWidth();x+=42)c.drawLine(x,0,x,getHeight(),grid);for(int y=42;y<getHeight();y+=42)c.drawLine(0,y,getWidth(),y,grid);String mode=gainMode==0?"Precision":gainMode==2?"Fast":"Balanced";c.drawText(dragging?"Dragging":"Touchpad • "+mode,getWidth()/2f,getHeight()/2f,hint);}

    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getPointerCount()>=2){
            handler.removeCallbacks(longPressDrag);
            if(dragging&&connection!=null){connection.mouseButton("left",false);dragging=false;}
            twoFinger=true;
            float y=(e.getY(0)+e.getY(1))/2f;
            if(e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN||e.getActionMasked()==MotionEvent.ACTION_DOWN){lastTwoY=twoStartY=y;twoDownAt=System.currentTimeMillis();}
            else if(e.getActionMasked()==MotionEvent.ACTION_MOVE){float d=y-lastTwoY;if(Math.abs(d)>=2&&connection!=null){connection.scroll((int)(-d*4));lastTwoY=y;}}
            else if(e.getActionMasked()==MotionEvent.ACTION_POINTER_UP||e.getActionMasked()==MotionEvent.ACTION_UP){long dt=System.currentTimeMillis()-twoDownAt;if(dt<260&&Math.abs(y-twoStartY)<18&&connection!=null){performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);connection.click("right");}twoFinger=false;}
            if(e.getActionMasked()==MotionEvent.ACTION_CANCEL)twoFinger=false;
            return true;
        }
        float x=e.getX(),y=e.getY();
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                downX=lastX=x;downY=lastY=y;downAt=System.currentTimeMillis();twoFinger=false;dragging=false;handler.postDelayed(longPressDrag,420);return true;
            case MotionEvent.ACTION_MOVE:
                float total=(float)Math.hypot(x-downX,y-downY);if(total>12&&!dragging)handler.removeCallbacks(longPressDrag);
                float dx=x-lastX,dy=y-lastY;float speed=(float)Math.sqrt(dx*dx+dy*dy);float base=gainMode==0?.62f:gainMode==2?1.55f:1.0f;float accel=speed>20?1.65f:speed>8?1.28f:1.0f;pendingDx+=(int)(dx*base*accel);pendingDy+=(int)(dy*base*accel);lastX=x;lastY=y;return true;
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPressDrag);long dt=System.currentTimeMillis()-downAt;float dist=(float)Math.hypot(x-downX,y-downY);
                if(dragging){if(connection!=null)connection.mouseButton("left",false);dragging=false;invalidate();}
                else if(dt<240&&dist<18&&connection!=null){performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);connection.click("left");}
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressDrag);if(dragging&&connection!=null)connection.mouseButton("left",false);dragging=false;twoFinger=false;invalidate();return true;
        }
        return true;
    }
    @Override protected void onDetachedFromWindow(){handler.removeCallbacks(flush);handler.removeCallbacks(longPressDrag);if(dragging&&connection!=null)connection.mouseButton("left",false);super.onDetachedFromWindow();}
}

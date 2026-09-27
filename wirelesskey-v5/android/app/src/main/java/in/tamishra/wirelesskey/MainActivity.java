package in.tamishra.wirelesskey;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity implements ConnectionManager.Listener {
    private static final int BG=0xFF07111F,CARD=0xFF0C1C2E,BORDER=0xFF1C3956,ACCENT=0xFF5CD6FF,TEXT=0xFFEAF2FF,MUTED=0xFF93AAC2,GOOD=0xFF82EFC8;
    private ConnectionManager conn;
    private TextView status,pcInfo;
    private EditText code,manualHost,textEntry;
    private LinearLayout controlArea;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        conn=new ConnectionManager(this,this);
        setContentView(buildUi());
    }

    private View buildUi(){
        ScrollView root=new ScrollView(this);
        root.setBackgroundColor(BG);
        LinearLayout page=col();
        page.setPadding(dp(18),dp(18),dp(18),dp(28));
        root.addView(page,new ScrollView.LayoutParams(-1,-2));

        page.addView(txt("WirelessKey",28,TEXT,true));
        page.addView(txt("v5.0.1 • secure keyboard + precision touchpad",13,MUTED,false),lp(-1,dp(34)));

        LinearLayout connection=card();
        page.addView(connection,lp(-1,-2,0,10));
        status=txt("● Not connected",15,MUTED,true);
        connection.addView(status);
        pcInfo=txt("Start WirelessKey Receiver v5 on the PC, then scan or discover.",13,MUTED,false);
        pcInfo.setPadding(0,dp(8),0,dp(12));
        connection.addView(pcInfo);

        LinearLayout actions=row();
        Button scan=button("Scan QR",true);
        Button discover=button("Discover PC",false);
        actions.addView(scan,weight(1));
        actions.addView(discover,weight(1));
        connection.addView(actions);

        code=input("6-digit pairing code");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        connection.addView(code,lp(-1,dp(52),0,10));

        manualHost=input("Manual PC IP (fallback)");
        manualHost.setText(conn.lastHost());
        connection.addView(manualHost,lp(-1,dp(52),0,10));

        Button connect=button("Connect securely",true);
        connection.addView(connect,lp(-1,dp(50)));
        scan.setOnClickListener(v->scanQr());
        discover.setOnClickListener(v->conn.discover());
        connect.setOnClickListener(v->conn.connectManual(manualHost.getText().toString(),code.getText().toString()));

        controlArea=col();
        controlArea.setAlpha(.42f);
        page.addView(controlArea);

        LinearLayout touch=card();
        controlArea.addView(touch,lp(-1,-2,0,10));
        touch.addView(txt("Precision touchpad",18,TEXT,true));
        touch.addView(txt("Move • tap = left click • two-finger slide = scroll",12,MUTED,false),lp(-1,dp(34)));
        TouchpadView pad=new TouchpadView(this);
        pad.setConnection(conn);
        touch.addView(pad,lp(-1,dp(270)));

        LinearLayout mouse=row();
        Button left=button("Left click",false);
        Button right=button("Right click",false);
        mouse.addView(left,weight(1));
        mouse.addView(right,weight(1));
        touch.addView(mouse,lp(-1,dp(50),8,0));
        left.setOnClickListener(v->conn.click("left"));
        right.setOnClickListener(v->conn.click("right"));

        LinearLayout typing=card();
        controlArea.addView(typing,lp(-1,-2,0,10));
        typing.addView(txt("Send text",18,TEXT,true));
        LinearLayout tr=row();
        textEntry=input("Type text for the PC");
        Button send=button("Send",true);
        tr.addView(textEntry,new LinearLayout.LayoutParams(0,dp(52),1));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(82),dp(52));
        sp.setMargins(dp(8),0,0,0);
        tr.addView(send,sp);
        typing.addView(tr,lp(-1,dp(52),8,0));
        send.setOnClickListener(v->{
            String s=textEntry.getText().toString();
            if(!s.isEmpty()){conn.text(s);textEntry.setText("");}
        });

        LinearLayout keys=card();
        controlArea.addView(keys);
        keys.addView(txt("Keyboard",18,TEXT,true));
        keys.addView(txt("Hold Ctrl, Alt or Shift like a physical keyboard.",12,MUTED,false),lp(-1,dp(34)));
        String[][] rows={
            {"Esc","F1","F2","F3","F4","F5","F6","F7","F8","F9","F10","F11","F12"},
            {"1","2","3","4","5","6","7","8","9","0","Back"},
            {"Q","W","E","R","T","Y","U","I","O","P"},
            {"A","S","D","F","G","H","J","K","L","Enter"},
            {"Shift","Z","X","C","V","B","N","M","Ctrl","Alt"},
            {"←","↑","↓","→","Space","Del"}
        };
        for(String[] rr:rows){
            HorizontalScrollView hsv=new HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            LinearLayout r=row();
            for(String k:rr){
                Button kb=keyButton(k);
                LinearLayout.LayoutParams kp=new LinearLayout.LayoutParams(k.equals("Space")?dp(150):dp(k.length()>4?76:58),dp(48));
                kp.setMargins(dp(3),0,dp(3),0);
                r.addView(kb,kp);
            }
            hsv.addView(r);
            keys.addView(hsv,lp(-1,dp(54)));
        }
        return root;
    }

    private void scanQr(){
        GmsBarcodeScannerOptions options=new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build();
        GmsBarcodeScanner scanner=GmsBarcodeScanning.getClient(this,options);
        scanner.startScan()
                .addOnSuccessListener(barcode->{
                    String raw=barcode.getRawValue();
                    if(raw!=null && raw.startsWith("wirelesskey://pair") && raw.contains("v=5")){
                        toast("Receiver QR recognized. Discovering the PC…");
                        conn.discover();
                    }else{
                        toast("This QR is not from WirelessKey Receiver v5.");
                    }
                })
                .addOnCanceledListener(()->{})
                .addOnFailureListener(e->toast("QR scanner unavailable. Use Discover PC or enter the PC IP."));
    }

    private Button keyButton(String name){
        Button b=button(name,false);
        int vk=vk(name);
        b.setOnTouchListener((v,e)->{
            if(vk==0)return false;
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                conn.key(vk,true);v.setPressed(true);return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_UP || e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                conn.key(vk,false);v.setPressed(false);return true;
            }
            return true;
        });
        return b;
    }

    private int vk(String s){
        Map<String,Integer> m=new HashMap<>();
        m.put("Esc",27);m.put("Back",8);m.put("Enter",13);m.put("Shift",16);m.put("Ctrl",17);m.put("Alt",18);
        m.put("Space",32);m.put("Del",46);m.put("←",37);m.put("↑",38);m.put("→",39);m.put("↓",40);
        for(int i=1;i<=12;i++)m.put("F"+i,111+i);
        if(m.containsKey(s))return m.get(s);
        if(s.length()==1){
            char c=Character.toUpperCase(s.charAt(0));
            if((c>='A'&&c<='Z')||(c>='0'&&c<='9'))return c;
        }
        return 0;
    }

    @Override public void onState(String s,String d){
        runOnUiThread(()->{
            status.setText("● "+d);
            status.setTextColor("connecting".equals(s)||"discovering".equals(s)?ACCENT:MUTED);
            if("disconnected".equals(s))controlArea.setAlpha(.42f);
        });
    }

    @Override public void onDiscovered(String name,String host,int port){
        runOnUiThread(()->{
            manualHost.setText(host);
            pcInfo.setText(name+" • "+host+":"+port);
            status.setText("● Receiver found");
            status.setTextColor(ACCENT);
        });
    }

    @Override public void onPairCodeRequired(){
        runOnUiThread(()->{
            status.setText("● Enter the 6-digit code shown on the PC");
            status.setTextColor(ACCENT);
            code.requestFocus();
            toast("Enter the receiver pairing code, then tap Connect securely.");
        });
    }

    @Override public void onReady(String name,String host){
        runOnUiThread(()->{
            status.setText("● Connected securely");
            status.setTextColor(GOOD);
            pcInfo.setText(name+" • "+host+" • protocol v5");
            controlArea.setAlpha(1f);
            code.setText("");
        });
    }

    @Override public void onError(String msg){
        runOnUiThread(()->{
            status.setText("● Not connected");
            status.setTextColor(0xFFFFA3A3);
            controlArea.setAlpha(.42f);
            toast(msg);
        });
    }

    private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}

    private LinearLayout card(){
        LinearLayout l=col();
        l.setPadding(dp(16),dp(16),dp(16),dp(16));
        GradientDrawable g=new GradientDrawable();
        g.setColor(CARD);g.setCornerRadius(dp(20));g.setStroke(dp(1),BORDER);
        l.setBackground(g);
        return l;
    }

    private TextView txt(String s,int sp,int color,boolean bold){
        TextView t=new TextView(this);
        t.setText(s);t.setTextSize(sp);t.setTextColor(color);
        if(bold)t.setTypeface(t.getTypeface(),1);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private EditText input(String hint){
        EditText e=new EditText(this);
        e.setHint(hint);e.setHintTextColor(0xFF67839E);e.setTextColor(TEXT);e.setTextSize(14);e.setSingleLine(true);
        e.setPadding(dp(14),0,dp(14),0);
        GradientDrawable g=new GradientDrawable();
        g.setColor(0xFF0A1A2B);g.setCornerRadius(dp(12));g.setStroke(dp(1),BORDER);
        e.setBackground(g);
        return e;
    }

    private Button button(String s,boolean primary){
        Button b=new Button(this);
        b.setText(s);b.setAllCaps(false);b.setTextSize(13);b.setTextColor(primary?0xFF052031:TEXT);
        b.setPadding(dp(10),0,dp(10),0);
        GradientDrawable g=new GradientDrawable();
        g.setColor(primary?ACCENT:0xFF10283D);g.setCornerRadius(dp(12));g.setStroke(dp(1),primary?ACCENT:BORDER);
        b.setBackground(g);
        return b;
    }

    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    private LinearLayout.LayoutParams lp(int w,int h,int top,int bottom){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);
        p.setMargins(0,dp(top),0,dp(bottom));
        return p;
    }
    private LinearLayout.LayoutParams weight(float f){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),f);
        p.setMargins(dp(3),0,dp(3),0);
        return p;
    }

    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}

    @Override protected void onDestroy(){
        conn.close();
        super.onDestroy();
    }
}

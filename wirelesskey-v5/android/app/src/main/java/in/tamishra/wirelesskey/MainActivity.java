package in.tamishra.wirelesskey;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity implements ConnectionManager.Listener {
    private static final int BG=0xFF070B12, DECK=0xFF0D111A, CARD=0xFF121824, KEY=0xFF1A2230,
            KEY2=0xFF222D3D, BORDER=0xFF2A3749, ACCENT=0xFF58D5FF, TEXT=0xFFF2F6FB,
            MUTED=0xFF8799AD, GOOD=0xFF71E6B5, BAD=0xFFFF8F9A, ACTIVE=0xFF173B50;

    private ConnectionManager conn;
    private LinearLayout contentHost;
    private TextView statusDot, statusText, pcText, modeTitle;
    private String currentMode = "Deck";
    private final Map<Integer, Boolean> stickyModifiers = new HashMap<>();
    private final Map<Integer, Button> modifierButtons = new HashMap<>();
    private int touchpadMode = 1;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        conn = new ConnectionManager(this, this);
        setContentView(buildShell());
        if (!conn.lastHost().isEmpty() && conn.hasTrust()) {
            statusText.setText("Ready to reconnect");
            pcText.setText(conn.lastHost());
        }
    }

    private View buildShell() {
        LinearLayout root = col();
        root.setBackgroundColor(BG);
        root.setPadding(dp(10), dp(8), dp(10), dp(10));

        root.addView(buildTopBar(), lp(-1, dp(46)));
        root.addView(buildModeBar(), lp(-1, dp(46), 5, 6));

        contentHost = col();
        root.addView(contentHost, new LinearLayout.LayoutParams(-1, 0, 1f));
        showMode("Deck");
        return root;
    }

    private View buildTopBar() {
        LinearLayout row = row();
        TextView brand = text("WIRELESSKEY", 18, TEXT, true);
        brand.setLetterSpacing(.08f);
        row.addView(brand, new LinearLayout.LayoutParams(0, -1, 1f));

        statusDot = text("●", 13, MUTED, true);
        statusText = text("Not connected", 12, MUTED, true);
        pcText = text("", 11, MUTED, false);
        LinearLayout stat = col();
        LinearLayout sr = row();
        sr.addView(statusDot, lp(dp(18), -1));
        sr.addView(statusText, lp(-2, -1));
        stat.addView(sr, lp(-2, dp(22)));
        stat.addView(pcText, lp(-2, dp(18)));
        row.addView(stat, lp(-2, -1));

        Button connect = smallButton("Connect", true);
        LinearLayout.LayoutParams cp = lp(dp(88), dp(38));
        cp.setMargins(dp(8),0,0,0);
        row.addView(connect, cp);
        connect.setOnClickListener(v -> showConnectionPopup(connect));
        return row;
    }

    private View buildModeBar() {
        LinearLayout outer = row();
        modeTitle = text("Deck", 13, ACCENT, true);
        outer.addView(modeTitle, lp(dp(62), -1));

        HorizontalScrollView sc = new HorizontalScrollView(this);
        sc.setHorizontalScrollBarEnabled(false);
        LinearLayout modes = row();
        String[] labels = {"Deck","Pad","Work","Numpad","Media","CAD"};
        for (String label : labels) {
            Button b = chip(label);
            b.setOnClickListener(v -> showMode(label));
            modes.addView(b, lp(dp(label.equals("Numpad") ? 78 : 64), dp(36), 3, 3));
        }
        Button multi = chip("Multiwork");
        multi.setOnClickListener(v -> showMultiworkPopup(multi));
        modes.addView(multi, lp(dp(92), dp(36), 3, 3));
        sc.addView(modes);
        outer.addView(sc, new LinearLayout.LayoutParams(0,-1,1f));
        return outer;
    }

    private void showMode(String mode) {
        currentMode = mode;
        if (modeTitle != null) modeTitle.setText(mode);
        if (contentHost == null) return;
        contentHost.removeAllViews();
        View v;
        switch (mode) {
            case "Pad": v = buildPadMode(); break;
            case "Work": v = buildWorkMode(); break;
            case "Numpad": v = buildNumpadMode(); break;
            case "Media": v = buildMediaMode(); break;
            case "CAD": v = buildCadMode(); break;
            default: v = buildDeckMode();
        }
        contentHost.addView(v, new LinearLayout.LayoutParams(-1,-1));
    }

    private View buildDeckMode() {
        LinearLayout wrap = row();
        wrap.setPadding(0,0,0,0);
        LinearLayout keyboard = card(false);
        keyboard.setPadding(dp(7),dp(7),dp(7),dp(7));
        keyboard.addView(buildKeyboard(), new LinearLayout.LayoutParams(-1,-1));
        wrap.addView(keyboard, new LinearLayout.LayoutParams(0,-1,3.2f));

        LinearLayout side = card(false);
        side.setPadding(dp(8),dp(8),dp(8),dp(8));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0,-1,1f);
        sp.setMargins(dp(7),0,0,0);
        wrap.addView(side, sp);
        side.addView(text("PRECISION PAD",11,MUTED,true), lp(-1,dp(26)));
        TouchpadView pad = touchpad();
        side.addView(pad, new LinearLayout.LayoutParams(-1,0,1f));
        LinearLayout mouse = row();
        Button left = keyStyleButton("L", false), right = keyStyleButton("R", false);
        mouse.addView(left, new LinearLayout.LayoutParams(0,dp(42),1));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0,dp(42),1); rp.setMargins(dp(5),0,0,0);
        mouse.addView(right, rp);
        left.setOnClickListener(v->conn.click("left")); right.setOnClickListener(v->conn.click("right"));
        side.addView(mouse, lp(-1,dp(46),5,0));
        side.addView(buildPadSpeedRow(pad), lp(-1,dp(40),4,0));
        return wrap;
    }

    private View buildKeyboard() {
        LinearLayout kb = col();
        addKeyRow(kb, new String[]{"Esc","F1","F2","F3","F4","F5","F6","F7","F8","F9","F10","F11","F12","Del"},
                new float[]{1.1f,1,1,1,1,1,1,1,1,1,1,1,1,1.1f});
        addKeyRow(kb, new String[]{"`","1","2","3","4","5","6","7","8","9","0","-","=","Bksp"},
                new float[]{1,1,1,1,1,1,1,1,1,1,1,1,1,1.7f});
        addKeyRow(kb, new String[]{"Tab","Q","W","E","R","T","Y","U","I","O","P","[","]","\\"},
                new float[]{1.35f,1,1,1,1,1,1,1,1,1,1,1,1,1.1f});
        addKeyRow(kb, new String[]{"Caps","A","S","D","F","G","H","J","K","L",";","'","Enter"},
                new float[]{1.55f,1,1,1,1,1,1,1,1,1,1,1,2f});
        addKeyRow(kb, new String[]{"Shift","Z","X","C","V","B","N","M",",",".","/","Shift"},
                new float[]{2f,1,1,1,1,1,1,1,1,1,1,2.15f});
        addKeyRow(kb, new String[]{"Ctrl","Win","Alt","Space","Alt","Ctrl","Home","↑","End"},
                new float[]{1.25f,1.1f,1.1f,5.1f,1.1f,1.25f,1.25f,1.15f,1.25f});
        addKeyRow(kb, new String[]{"Ins","PgUp","PgDn","←","↓","→"},
                new float[]{1.1f,1.2f,1.2f,1.1f,1.1f,1.1f});
        return kb;
    }

    private void addKeyRow(LinearLayout kb, String[] labels, float[] weights) {
        LinearLayout row = row();
        for (int i=0;i<labels.length;i++) {
            Button b = keyboardButton(labels[i]);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,0,weights[i]);
            p.height = 0;
            p.setMargins(dp(2),dp(2),dp(2),dp(2));
            row.addView(b,p);
        }
        kb.addView(row,new LinearLayout.LayoutParams(-1,0,1f));
    }

    private Button keyboardButton(String label) {
        Button b = keyStyleButton(label, isModifierLabel(label));
        int vk = keyVk(label);
        if (isModifierVk(vk)) {
            modifierButtons.put(vk,b);
            b.setOnClickListener(v -> toggleModifier(vk,b));
        } else if (vk != 0) {
            b.setOnTouchListener((v,e)->{
                if (!conn.isReady()) { if (e.getActionMasked()==MotionEvent.ACTION_DOWN) toastShort("Connect to the receiver first"); return true; }
                if (e.getActionMasked()==MotionEvent.ACTION_DOWN) {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    conn.key(vk,true); v.setPressed(true); return true;
                }
                if (e.getActionMasked()==MotionEvent.ACTION_UP || e.getActionMasked()==MotionEvent.ACTION_CANCEL) {
                    conn.key(vk,false); v.setPressed(false); releaseStickyModifiers(); return true;
                }
                return true;
            });
        }
        return b;
    }

    private void toggleModifier(int vk, Button b) {
        if (!conn.isReady()) { toastShort("Connect to the receiver first"); return; }
        boolean on = !Boolean.TRUE.equals(stickyModifiers.get(vk));
        stickyModifiers.put(vk,on);
        conn.key(vk,on);
        setModifierVisual(b,on);
        b.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    private void releaseStickyModifiers() {
        for (Map.Entry<Integer,Boolean> e : stickyModifiers.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) {
                conn.key(e.getKey(),false);
                Button b=modifierButtons.get(e.getKey()); if(b!=null)setModifierVisual(b,false);
            }
        }
        stickyModifiers.clear();
    }

    private void setModifierVisual(Button b, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(on ? ACTIVE : KEY2); g.setCornerRadius(dp(7)); g.setStroke(dp(1), on ? ACCENT : BORDER);
        b.setBackground(g); b.setTextColor(on ? ACCENT : TEXT);
    }

    private View buildPadMode() {
        LinearLayout card = card(false);
        card.setPadding(dp(12),dp(10),dp(12),dp(10));
        LinearLayout title = row(); title.addView(text("Precision touchpad",18,TEXT,true), new LinearLayout.LayoutParams(0,-1,1));
        TextView tip = text("Tap • drag • two-finger scroll",11,MUTED,false); title.addView(tip);
        card.addView(title,lp(-1,dp(36)));
        TouchpadView pad=touchpad(); card.addView(pad,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout bottom=row();
        Button left=keyStyleButton("Left click",false), right=keyStyleButton("Right click",false), middle=keyStyleButton("Middle",false);
        bottom.addView(left,new LinearLayout.LayoutParams(0,dp(46),1));
        bottom.addView(right,new LinearLayout.LayoutParams(0,dp(46),1));
        bottom.addView(middle,new LinearLayout.LayoutParams(0,dp(46),1));
        left.setOnClickListener(v->conn.click("left"));right.setOnClickListener(v->conn.click("right"));middle.setOnClickListener(v->conn.click("middle"));
        card.addView(bottom,lp(-1,dp(50),5,0));
        card.addView(buildPadSpeedRow(pad),lp(-1,dp(42),3,0));
        return card;
    }

    private TouchpadView touchpad(){ TouchpadView p=new TouchpadView(this); p.setConnection(conn); p.setGainMode(touchpadMode); return p; }
    private View buildPadSpeedRow(TouchpadView pad) {
        LinearLayout r=row();
        TextView l=text("Pointer",11,MUTED,true);r.addView(l,lp(dp(54),-1));
        String[] modes={"Precision","Balanced","Fast"};
        for(int i=0;i<modes.length;i++){
            final int idx=i;Button b=chip(modes[i]);r.addView(b,new LinearLayout.LayoutParams(0,dp(34),1));
            b.setOnClickListener(v->{touchpadMode=idx;pad.setGainMode(idx);toastShort(modes[idx]+" pointer mode");});
        }
        return r;
    }

    private View buildWorkMode() {
        LinearLayout root=card(false); root.setPadding(dp(12),dp(10),dp(12),dp(10));
        root.addView(text("Work shortcuts & clipboard",18,TEXT,true),lp(-1,dp(34)));
        LinearLayout send=row(); EditText e=input("Type or paste text to send to PC"); Button sb=smallButton("Send",true);
        send.addView(e,new LinearLayout.LayoutParams(0,dp(48),1));LinearLayout.LayoutParams bp=lp(dp(82),dp(48));bp.setMargins(dp(6),0,0,0);send.addView(sb,bp);
        sb.setOnClickListener(v->{String s=e.getText().toString();if(!s.isEmpty()&&conn.isReady()){conn.text(s);e.setText("");}}); root.addView(send,lp(-1,dp(52),2,8));
        String[][] grid={{"Copy","Paste","Cut","Undo","Redo"},{"Save","Select all","Find","Print","Alt+Tab"},{"Desktop","Task View","Enter","Esc","Delete"}};
        int[][][] combos={{{17,67},{17,86},{17,88},{17,90},{17,89}},{{17,83},{17,65},{17,70},{17,80},{18,9}},{{91,68},{91,9},{13},{27},{46}}};
        for(int rr=0;rr<grid.length;rr++){LinearLayout line=row();for(int cc=0;cc<grid[rr].length;cc++){Button b=keyStyleButton(grid[rr][cc],false);int[] c=combos[rr][cc];b.setOnClickListener(v->sendCombo(c));line.addView(b,new LinearLayout.LayoutParams(0,dp(52),1));}root.addView(line,lp(-1,dp(56)));}
        return root;
    }

    private View buildNumpadMode() {
        LinearLayout root=card(false);root.setPadding(dp(10),dp(10),dp(10),dp(10));root.addView(text("Numeric keypad",18,TEXT,true),lp(-1,dp(36)));
        String[][] rows={{"Num","/","*","-"},{"7","8","9","+"},{"4","5","6","+"},{"1","2","3","Enter"},{"0","0",".","Enter"}};
        for(String[] row:rows){LinearLayout line=row();for(String s:row){Button b=keyStyleButton(s,false);int vk=numpadVk(s);b.setOnClickListener(v->tapKey(vk));line.addView(b,new LinearLayout.LayoutParams(0,dp(58),1));}root.addView(line,lp(-1,dp(62)));}
        return root;
    }

    private View buildMediaMode() {
        LinearLayout root=card(false);root.setPadding(dp(12),dp(10),dp(12),dp(10));root.addView(text("Media control",18,TEXT,true),lp(-1,dp(40)));
        String[][] rows={{"⏮ Prev","⏯ Play/Pause","⏭ Next"},{"🔇 Mute","Vol −","Vol +"},{"Stop","Space","Esc"}};
        int[][] vals={{177,179,176},{173,174,175},{178,32,27}};
        for(int r=0;r<rows.length;r++){LinearLayout line=row();for(int c=0;c<rows[r].length;c++){Button b=keyStyleButton(rows[r][c],false);int vk=vals[r][c];b.setOnClickListener(v->tapKey(vk));line.addView(b,new LinearLayout.LayoutParams(0,dp(72),1));}root.addView(line,lp(-1,dp(78)));}
        return root;
    }

    private View buildCadMode() {
        LinearLayout root=card(false);root.setPadding(dp(12),dp(10),dp(12),dp(10));root.addView(text("CAD / engineering quick keys",18,TEXT,true),lp(-1,dp(38)));
        String[][] labels={{"Esc","F3 Snap","F8 Ortho","F10 Polar","Enter"},{"Ctrl+1","Ctrl+S","Ctrl+Z","Ctrl+Y","Delete"},{"Home","←","↑","↓","→"},{"Space","Tab","Shift","Ctrl","Alt"}};
        int[][][] combos={{{27},{114},{119},{121},{13}},{{17,49},{17,83},{17,90},{17,89},{46}},{{36},{37},{38},{40},{39}},{{32},{9},{16},{17},{18}}};
        for(int r=0;r<labels.length;r++){LinearLayout line=row();for(int c=0;c<labels[r].length;c++){Button b=keyStyleButton(labels[r][c],false);int[] combo=combos[r][c];b.setOnClickListener(v->sendCombo(combo));line.addView(b,new LinearLayout.LayoutParams(0,dp(58),1));}root.addView(line,lp(-1,dp(63)));}
        return root;
    }

    private void showMultiworkPopup(View anchor) {
        LinearLayout box=card(true);box.setPadding(dp(10),dp(10),dp(10),dp(10));
        box.addView(text("Quick workspace",14,TEXT,true),lp(-1,dp(32)));
        String[] modes={"Deck","Pad","Work","Numpad","Media","CAD"};
        for(String m:modes){Button b=keyStyleButton(m,false);b.setOnClickListener(v->{showMode(m);Object tag=box.getTag();if(tag instanceof PopupWindow)((PopupWindow)tag).dismiss();});box.addView(b,lp(dp(180),dp(42),2,2));}
        PopupWindow pop=new PopupWindow(box,dp(205),-2,true);box.setTag(pop);pop.setBackgroundDrawable(bg(CARD,18,BORDER));pop.setElevation(dp(14));pop.showAsDropDown(anchor,-dp(100),dp(4));
    }

    private void showConnectionPopup(View anchor) {
        LinearLayout box=card(true); box.setPadding(dp(14),dp(12),dp(14),dp(14));
        box.addView(text("Connect WirelessKey",17,TEXT,true),lp(-1,dp(34)));
        TextView hint=text("Run Receiver v5 on the PC. Keep both devices on the same Wi-Fi.",11,MUTED,false);box.addView(hint,lp(-1,dp(42)));
        EditText code=input("6-digit pairing code");code.setInputType(InputType.TYPE_CLASS_NUMBER);box.addView(code,lp(-1,dp(46),4,4));
        EditText host=input("PC IP address (manual fallback)");host.setText(conn.lastHost());box.addView(host,lp(-1,dp(46),4,5));
        LinearLayout r1=row();Button scan=smallButton("Scan QR",false), discover=smallButton("Discover",false);r1.addView(scan,new LinearLayout.LayoutParams(0,dp(44),1));r1.addView(discover,new LinearLayout.LayoutParams(0,dp(44),1));box.addView(r1,lp(-1,dp(48)));
        Button connect=smallButton(conn.hasTrust()?"Connect / reconnect":"Pair & connect",true);box.addView(connect,lp(-1,dp(46),4,3));
        Button forget=smallButton("Forget trusted pairing",false);box.addView(forget,lp(-1,dp(42),3,0));
        PopupWindow pop=new PopupWindow(box,dp(330),-2,true);pop.setBackgroundDrawable(bg(CARD,20,BORDER));pop.setElevation(dp(16));
        scan.setOnClickListener(v->{scanQr();pop.dismiss();});
        discover.setOnClickListener(v->{conn.discover();pop.dismiss();});
        connect.setOnClickListener(v->{conn.connectManual(host.getText().toString(),code.getText().toString());pop.dismiss();});
        forget.setOnClickListener(v->{conn.forgetTrust();toastShort("Trusted pairing cleared");connect.setText("Pair & connect");});
        pop.showAsDropDown(anchor,-dp(240),dp(4));
    }

    private void scanQr() {
        try {
            GmsBarcodeScannerOptions options=new GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build();
            GmsBarcodeScanner scanner= GmsBarcodeScanning.getClient(this,options);
            scanner.startScan()
                    .addOnSuccessListener(barcode->{String raw=barcode.getRawValue();if(raw!=null&&raw.startsWith("wirelesskey://pair")&&raw.contains("v=5")){toastShort("Receiver QR recognized");conn.discover();}else toast("That QR is not from WirelessKey Receiver v5.");})
                    .addOnCanceledListener(()->{})
                    .addOnFailureListener(e->toast("QR scanner unavailable. Use Discover or manual PC IP."));
        } catch (Exception e) { toast("QR scanner unavailable. Use Discover or manual PC IP."); }
    }

    private void tapKey(int vk){if(vk==0)return;if(!conn.isReady()){toastShort("Connect to the receiver first");return;}conn.key(vk,true);conn.key(vk,false);}
    private void sendCombo(int[] keys){if(!conn.isReady()){toastShort("Connect to the receiver first");return;}if(keys.length==1){tapKey(keys[0]);return;}for(int i=0;i<keys.length-1;i++)conn.key(keys[i],true);int last=keys[keys.length-1];conn.key(last,true);conn.key(last,false);for(int i=keys.length-2;i>=0;i--)conn.key(keys[i],false);}

    private int keyVk(String s){
        Map<String,Integer> m=new HashMap<>();
        m.put("Esc",27);m.put("Tab",9);m.put("Caps",20);m.put("Enter",13);m.put("Bksp",8);m.put("Shift",16);m.put("Ctrl",17);m.put("Alt",18);m.put("Win",91);m.put("Space",32);m.put("Del",46);m.put("Ins",45);m.put("Home",36);m.put("End",35);m.put("PgUp",33);m.put("PgDn",34);m.put("←",37);m.put("↑",38);m.put("→",39);m.put("↓",40);
        m.put("`",192);m.put("-",189);m.put("=",187);m.put("[",219);m.put("]",221);m.put("\\",220);m.put(";",186);m.put("'",222);m.put(",",188);m.put(".",190);m.put("/",191);
        for(int i=1;i<=12;i++)m.put("F"+i,111+i);
        if(m.containsKey(s))return m.get(s);
        if(s.length()==1){char c=Character.toUpperCase(s.charAt(0));if((c>='A'&&c<='Z')||(c>='0'&&c<='9'))return c;}
        return 0;
    }
    private int numpadVk(String s){if("Num".equals(s))return 144;if("/".equals(s))return 111;if("*".equals(s))return 106;if("-".equals(s))return 109;if("+".equals(s))return 107;if(".".equals(s))return 110;if("Enter".equals(s))return 13;if(s.length()==1&&Character.isDigit(s.charAt(0)))return 96+(s.charAt(0)-'0');return 0;}
    private boolean isModifierLabel(String s){return "Shift".equals(s)||"Ctrl".equals(s)||"Alt".equals(s)||"Win".equals(s);}
    private boolean isModifierVk(int vk){return vk==16||vk==17||vk==18||vk==91;}

    @Override public void onState(String state,String detail){runOnUiThread(()->{statusDot.setTextColor("connecting".equals(state)||"discovering".equals(state)?ACCENT:MUTED);statusText.setText(detail);if("disconnected".equals(state)){statusDot.setTextColor(BAD);releaseStickyModifiers();}});}
    @Override public void onDiscovered(String name,String host,int port){runOnUiThread(()->{statusDot.setTextColor(ACCENT);statusText.setText("Receiver found");pcText.setText(name+" • "+host+":"+port);if(conn.hasTrust())conn.connect();});}
    @Override public void onPairCodeRequired(){runOnUiThread(()->{statusDot.setTextColor(ACCENT);statusText.setText("Pairing code required");toast("Enter the 6-digit code shown on the Windows receiver, then Connect.");});}
    @Override public void onReady(String name,String host){runOnUiThread(()->{statusDot.setTextColor(GOOD);statusText.setText("Connected securely");pcText.setText(name+" • "+host+" • v5");});}
    @Override public void onError(String message){runOnUiThread(()->{statusDot.setTextColor(BAD);statusText.setText("Not connected");toast(message);});}

    private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout card(boolean popup){LinearLayout l=col();l.setBackground(bg(popup?CARD:DECK,popup?20:14,BORDER));return l;}
    private GradientDrawable bg(int color,int radius,int border){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));g.setStroke(dp(1),border);return g;}
    private TextView text(String s,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(t.getTypeface(),1);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    private EditText input(String hint){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(0xFF60748B);e.setTextColor(TEXT);e.setTextSize(13);e.setSingleLine(true);e.setPadding(dp(12),0,dp(12),0);e.setBackground(bg(0xFF0A1019,11,BORDER));return e;}
    private Button smallButton(String s,boolean primary){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(12);b.setTextColor(primary?0xFF05202B:TEXT);b.setPadding(dp(8),0,dp(8),0);GradientDrawable g=new GradientDrawable();g.setColor(primary?ACCENT:KEY2);g.setCornerRadius(dp(10));g.setStroke(dp(1),primary?ACCENT:BORDER);b.setBackground(g);return b;}
    private Button chip(String s){Button b=smallButton(s,false);b.setTextSize(11);return b;}
    private Button keyStyleButton(String s,boolean modifier){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(s.length()>7?9.5f:11f);b.setTextColor(TEXT);b.setGravity(Gravity.CENTER);b.setPadding(dp(2),0,dp(2),0);b.setMinHeight(0);b.setMinWidth(0);b.setBackground(bg(modifier?KEY2:KEY,7,BORDER));return b;}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    private LinearLayout.LayoutParams lp(int w,int h,int top,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.setMargins(0,dp(top),0,dp(bottom));return p;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void toastShort(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}

    @Override protected void onDestroy(){releaseStickyModifiers();conn.close();super.onDestroy();}
}

package com.wirelesskey.remote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

public class MainActivity extends Activity implements SecureLink.Listener {
    private static final int BG = Color.rgb(8,17,31);
    private static final int PANEL = Color.rgb(15,26,43);
    private static final int KEY = Color.rgb(31,45,65);
    private static final int KEY_ACTIVE = Color.rgb(22,54,91);
    private static final int BORDER = Color.rgb(53,74,101);
    private static final int TEXT = Color.rgb(248,250,252);
    private static final int MUTED = Color.rgb(144,162,187);
    private static final int ACCENT = Color.rgb(37,99,235);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SecureLink link;

    private static final int CAMERA_REQUEST = 4101;
    private EditText hostInput, codeInput;
    private Button connectButton, hapticButton;
    private TextView statusText, appText, latencyText;
    private Spinner deviceSpinner;
    private ArrayAdapter<String> deviceAdapter;
    private final List<String> deviceHosts = new ArrayList<>();

    private FrameLayout sideContent;
    private Button mouseTab, numTab, mediaTab, shortcutTab, clipboardTab;
    private LinearLayout shortcutGrid, macroGrid;
    private EditText clipboardPreview;
    private MacroStore macroStore;
    private List<MacroStore.Macro> macros = new ArrayList<>();

    private boolean haptics = true;
    private boolean shiftOn = false;
    private boolean capsOn = false;
    private final Set<String> activeMods = new HashSet<>();
    private final List<KeyBinding> printableBindings = new ArrayList<>();
    private final Map<String, List<Button>> modifierButtons = new HashMap<>();
    private String currentProfile = "standard";

    private static final class KeySpec {
        final String key;
        final String normal;
        final String shifted;
        final String label;
        final float weight;
        final String type;

        KeySpec(String key, String normal, String shifted, String label, float weight, String type) {
            this.key=key; this.normal=normal; this.shifted=shifted; this.label=label; this.weight=weight; this.type=type;
        }
        static KeySpec special(String key,String label,float weight){ return new KeySpec(key,null,null,label,weight,"special"); }
        static KeySpec mod(String key,String label,float weight){ return new KeySpec(key,null,null,label,weight,"mod"); }
        static KeySpec shift(float weight){ return new KeySpec("SHIFT",null,null,"Shift",weight,"shift"); }
        static KeySpec caps(float weight){ return new KeySpec("CAPSLOCK",null,null,"Caps Lock",weight,"caps"); }
        static KeySpec ch(String normal,String shifted){ return new KeySpec(null,normal,shifted,normal.toUpperCase(),1f,"char"); }
    }

    private static final class KeyBinding {
        final Button button;
        final KeySpec spec;
        KeyBinding(Button b, KeySpec s){ button=b; spec=s; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersive();

        link = new SecureLink(this);
        link.setListener(this);
        haptics = link.loadHaptics();
        macroStore = new MacroStore(this);
        macros = macroStore.load();

        setContentView(buildUi());
        reloadKnownPeersIntoSpinner();
    }

    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable bg(int color, float radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) d.setStroke(dp(1), strokeColor);
        return d;
    }

    private StateListDrawable stateBg(int normalColor, int pressedColor, float radiusDp, int strokeColor) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, bg(pressedColor, radiusDp, strokeColor));
        states.addState(new int[]{}, bg(normalColor, radiusDp, strokeColor));
        return states;
    }

    private TextView textView(String text, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String label, float sp) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(sp);
        b.setTextColor(TEXT);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(3),0,dp(3),0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setBackground(stateBg(KEY, Color.rgb(43,61,84), 7, BORDER));
        b.setElevation(dp(1.2f));
        return b;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(5),dp(4),dp(5),dp(4));

        root.addView(buildTopBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));

        LinearLayout workspace = new LinearLayout(this);
        workspace.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams workLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        workLp.topMargin = dp(4);
        root.addView(workspace, workLp);

        LinearLayout keyboardPanel = buildKeyboard();
        LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3.2f);
        workspace.addView(keyboardPanel, keyLp);

        LinearLayout side = buildSidePanel();
        LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.15f);
        sideLp.leftMargin=dp(4);
        workspace.addView(side, sideLp);

        return root;
    }

    private View buildTopBar() {
        final boolean compact = getResources().getConfiguration().screenWidthDp < 780;

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6),dp(3),dp(6),dp(3));
        bar.setBackground(bg(PANEL,11,Color.rgb(29,43,64)));
        bar.setElevation(dp(1.5f));

        TextView brand = textView("WirelessKey 4.1.1", compact?10.5f:12f, TEXT, true);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(brand, new LinearLayout.LayoutParams(dp(compact?94:118), LinearLayout.LayoutParams.MATCH_PARENT));

        deviceSpinner = new Spinner(this);
        deviceAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<String>()) {
            private TextView style(TextView t, boolean dropdown) {
                t.setTextColor(TEXT);
                t.setTextSize(dropdown?10:9);
                t.setPadding(dp(8),0,dp(8),0);
                t.setGravity(Gravity.CENTER_VERTICAL);
                if(dropdown) t.setBackgroundColor(PANEL);
                return t;
            }
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                return style((TextView)super.getView(position,convertView,parent),false);
            }
            @Override
            public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                return style((TextView)super.getDropDownView(position,convertView,parent),true);
            }
        };
        deviceAdapter.add("Remembered PCs");
        deviceSpinner.setAdapter(deviceAdapter);
        deviceSpinner.setBackground(bg(Color.rgb(9,19,33),8,BORDER));
        deviceSpinner.setPopupBackgroundDrawable(bg(PANEL,7,BORDER));
        deviceSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position > 0 && position-1 < deviceHosts.size()) {
                    hostInput.setText(deviceHosts.get(position-1));
                    codeInput.setText("");
                }
            }
        });
        LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(dp(compact?118:160), dp(30));
        spinnerLp.leftMargin=dp(3);
        bar.addView(deviceSpinner, spinnerLp);

        Button find = button(compact?"Find":"Find PC", 8.5f);
        find.setOnClickListener(v -> {
            haptic();
            deviceHosts.clear();
            deviceAdapter.clear();
            deviceAdapter.add("Searching...");
            deviceAdapter.notifyDataSetChanged();
            link.discover();
            onStatus("discovering","Finding PCs...");
        });
        LinearLayout.LayoutParams findLp=new LinearLayout.LayoutParams(dp(compact?48:62),dp(30));
        findLp.leftMargin=dp(3);
        bar.addView(find,findLp);

        Button qr = button("QR", 9);
        qr.setBackground(stateBg(Color.rgb(6,95,70),Color.rgb(5,122,85),7,Color.rgb(16,185,129)));
        qr.setOnClickListener(v -> { haptic(); startQrScan(); });
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(dp(44), dp(30));
        qrLp.leftMargin = dp(3);
        bar.addView(qr, qrLp);

        hostInput = new EditText(this);
        hostInput.setText(link.loadHost());
        codeInput = new EditText(this);
        codeInput.setText(link.loadCode());

        Button manual = button(compact?"IP":"Manual",8);
        manual.setOnClickListener(v->{haptic();showManualPairDialog();});
        LinearLayout.LayoutParams manLp=new LinearLayout.LayoutParams(dp(compact?42:58),dp(30));
        manLp.leftMargin=dp(3);
        bar.addView(manual,manLp);

        connectButton = button("Connect",8.5f);
        connectButton.setBackground(stateBg(ACCENT,Color.rgb(29,78,216),7,Color.rgb(59,130,246)));
        connectButton.setOnClickListener(v -> {
            haptic();
            String host=hostInput.getText().toString().trim();
            String code=codeInput.getText().toString().trim();
            if (host.isEmpty()) {
                showManualPairDialog();
                return;
            }
            if (code.isEmpty() && !link.hasTrustedTokenForHost(host)) {
                onStatus("offline","Pairing code required for this PC");
                showManualPairDialog();
                return;
            }
            link.connect(host,code);
        });
        LinearLayout.LayoutParams conLp = new LinearLayout.LayoutParams(dp(compact?60:70),dp(30));
        conLp.leftMargin=dp(3);
        bar.addView(connectButton,conLp);

        hapticButton = button(compact?"Hap":"Haptic",8);
        updateHapticButtonStyle();
        hapticButton.setOnClickListener(v -> {
            haptics=!haptics;
            link.setHaptics(haptics);
            updateHapticButtonStyle();
            if (haptics) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        });
        LinearLayout.LayoutParams hapLp = new LinearLayout.LayoutParams(dp(compact?42:56),dp(30));
        hapLp.leftMargin=dp(3);
        bar.addView(hapticButton,hapLp);

        LinearLayout context = new LinearLayout(this);
        context.setOrientation(LinearLayout.HORIZONTAL);
        context.setGravity(Gravity.CENTER_VERTICAL);
        context.setPadding(dp(7),0,dp(7),0);
        context.setBackground(bg(Color.rgb(12,23,40),20,Color.rgb(43,65,94)));
        appText=textView("Desktop",8,Color.rgb(219,234,254),true);
        appText.setMaxLines(1);
        appText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        latencyText=textView("— ms",7.5f,Color.rgb(125,211,252),false);
        latencyText.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);
        context.addView(appText,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f));
        context.addView(latencyText,new LinearLayout.LayoutParams(dp(compact?36:42),LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams ctxLp = new LinearLayout.LayoutParams(dp(compact?104:132),dp(25));
        ctxLp.leftMargin=dp(4);
        bar.addView(context,ctxLp);

        statusText=textView("Offline",7.7f,MUTED,false);
        statusText.setGravity(Gravity.CENTER);
        statusText.setMaxLines(1);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f);
        stLp.leftMargin=dp(4);
        bar.addView(statusText,stLp);

        return bar;
    }

    private void updateHapticButtonStyle(){
        if(hapticButton==null)return;
        int normal=haptics?Color.rgb(15,78,92):KEY;
        int pressed=haptics?Color.rgb(14,116,144):Color.rgb(43,61,84);
        hapticButton.setBackground(stateBg(normal,pressed,7,BORDER));
    }

    private void showManualPairDialog(){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16),dp(6),dp(16),0);

        EditText host=new EditText(this);
        host.setHint("PC IP or IP:port");
        host.setSingleLine(true);
        host.setText(hostInput==null?"":hostInput.getText().toString());
        box.addView(host,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(50)));

        EditText code=new EditText(this);
        code.setHint("6-digit code (not needed for a trusted PC)");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setSingleLine(true);
        code.setText(codeInput==null?"":codeInput.getText().toString());
        box.addView(code,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(50)));

        new AlertDialog.Builder(this)
                .setTitle("Manual PC connection")
                .setMessage("QR pairing is recommended. Use this only when discovery/QR is unavailable.")
                .setView(box)
                .setPositiveButton("Connect",(d,w)->{
                    String h=host.getText().toString().trim();
                    String p=code.getText().toString().trim();
                    if(h.isEmpty()){
                        onStatus("error","Enter the PC address");
                        return;
                    }
                    hostInput.setText(h);
                    codeInput.setText(p);
                    link.connect(h,p);
                })
                .setNegativeButton("Cancel",null)
                .show();
    }

    private void startQrScan() {
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
            onStatus("discovering","Camera permission needed for QR pairing");
            return;
        }
        launchQrScanner();
    }

    private void launchQrScanner(){
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("Scan the WirelessKey pairing QR on your PC");
        integrator.setBeepEnabled(false);
        integrator.setBarcodeImageEnabled(false);
        integrator.setOrientationLocked(false);
        integrator.setCameraId(0);
        integrator.initiateScan();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==CAMERA_REQUEST){
            if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED){
                launchQrScanner();
            }else{
                onStatus("error","Camera permission denied · use Find PC or Manual");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            String contents = result.getContents();
            if (contents == null || contents.trim().isEmpty()) {
                onStatus("offline","QR scan cancelled");
                return;
            }

            onStatus("discovering","QR scanned · verifying PC identity...");
            link.connectPairingQr(contents.trim());
            handler.postDelayed(this::reloadKnownPeersIntoSpinner,450);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void reloadKnownPeersIntoSpinner() {
        if (deviceAdapter == null) return;
        deviceHosts.clear();
        deviceAdapter.clear();
        deviceAdapter.add("Remembered PCs");

        try {
            JSONArray arr = new JSONArray(link.loadKnownPeersJson());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject peer = arr.optJSONObject(i);
                if (peer == null) continue;
                String host = peer.optString("host", "");
                String name = peer.optString("name", "WirelessKey PC");
                if (host.isEmpty()) continue;
                deviceHosts.add(host);
                deviceAdapter.add(name + " · " + host);
            }
        } catch (Exception ignored) {
        }
        deviceAdapter.notifyDataSetChanged();
    }

    private LinearLayout buildKeyboard() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        LinearLayout fn = row();
        String[] fnLabels={"Esc","F1","F2","F3","F4","F5","F6","F7","F8","F9","F10","F11","F12","Ins","Del"};
        String[] fnKeys={"ESC","F1","F2","F3","F4","F5","F6","F7","F8","F9","F10","F11","F12","INSERT","DELETE"};
        for(int i=0;i<fnKeys.length;i++) addKey(fn,KeySpec.special(fnKeys[i],fnLabels[i],i==0?1.25f:1f),8.5f);
        panel.addView(fn,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,0.78f));

        List<KeySpec[]> rows=new ArrayList<>();
        rows.add(new KeySpec[]{
                KeySpec.ch("`","~"),KeySpec.ch("1","!"),KeySpec.ch("2","@"),KeySpec.ch("3","#"),
                KeySpec.ch("4","$"),KeySpec.ch("5","%"),KeySpec.ch("6","^"),KeySpec.ch("7","&"),
                KeySpec.ch("8","*"),KeySpec.ch("9","("),KeySpec.ch("0",")"),KeySpec.ch("-","_"),
                KeySpec.ch("=","+"),KeySpec.special("BACKSPACE","Backspace",1.9f)
        });
        rows.add(concat(new KeySpec[]{KeySpec.special("TAB","Tab",1.5f)}, chars("qwertyuiop"),
                new KeySpec[]{KeySpec.ch("[","{"),KeySpec.ch("]","}"),KeySpec.ch("\\","|")}));
        rows.add(concat(new KeySpec[]{KeySpec.caps(1.7f)}, chars("asdfghjkl"),
                new KeySpec[]{KeySpec.ch(";",":"),KeySpec.ch("'","\""),KeySpec.special("ENTER","Enter",2.2f)}));
        rows.add(concat(new KeySpec[]{KeySpec.shift(2.2f)}, chars("zxcvbnm"),
                new KeySpec[]{KeySpec.ch(",","<"),KeySpec.ch(".",">"),KeySpec.ch("/","?"),KeySpec.shift(2.2f)}));
        rows.add(new KeySpec[]{
                KeySpec.mod("CTRL","Ctrl",1.4f),KeySpec.mod("WIN","Win",1.2f),KeySpec.mod("ALT","Alt",1.2f),
                KeySpec.special("SPACE","Space",5.2f),KeySpec.mod("ALT","Alt",1.2f),KeySpec.special("MENU","Menu",1.2f),
                KeySpec.mod("CTRL","Ctrl",1.4f),KeySpec.special("LEFT","←",1f),KeySpec.special("UP","↑",1f),
                KeySpec.special("DOWN","↓",1f),KeySpec.special("RIGHT","→",1f)
        });

        for(KeySpec[] specs:rows){
            LinearLayout r=row();
            for(KeySpec s:specs) addKey(r,s,10.5f);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f);
            lp.topMargin=dp(3);
            panel.addView(r,lp);
        }
        return panel;
    }

    private LinearLayout row() {
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        return r;
    }

    private KeySpec[] chars(String s){
        KeySpec[] a=new KeySpec[s.length()];
        for(int i=0;i<s.length();i++) a[i]=KeySpec.ch(String.valueOf(s.charAt(i)),null);
        return a;
    }

    private KeySpec[] concat(KeySpec[]... arrays){
        int n=0;for(KeySpec[] a:arrays)n+=a.length;
        KeySpec[] out=new KeySpec[n];int p=0;
        for(KeySpec[] a:arrays){System.arraycopy(a,0,out,p,a.length);p+=a.length;}
        return out;
    }

    private void addKey(LinearLayout row, KeySpec spec, float sp) {
        Button b=button(displayFor(spec),sp);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,spec.weight);
        lp.rightMargin=dp(3);
        row.addView(b,lp);

        if ("char".equals(spec.type)) printableBindings.add(new KeyBinding(b,spec));
        if ("mod".equals(spec.type)||"shift".equals(spec.type)||"caps".equals(spec.type)) {
            List<Button> group = modifierButtons.get(spec.key);
            if (group == null) {
                group = new ArrayList<>();
                modifierButtons.put(spec.key, group);
            }
            group.add(b);
        }

        boolean repeat=Arrays.asList("BACKSPACE","DELETE","LEFT","RIGHT","UP","DOWN").contains(spec.key);
        if(repeat){
            b.setOnTouchListener(new View.OnTouchListener() {
                Runnable repeating;
                @Override public boolean onTouch(View v, MotionEvent e) {
                    if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                        haptic();pressSpec(spec);
                        repeating=new Runnable(){@Override public void run(){pressSpec(spec);handler.postDelayed(this,58);}};
                        handler.postDelayed(repeating,400);
                        return true;
                    }
                    if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                        if(repeating!=null)handler.removeCallbacks(repeating);
                        return true;
                    }
                    return true;
                }
            });
        }else{
            b.setOnClickListener(v->{haptic();pressSpec(spec);});
        }
    }

    private String displayFor(KeySpec s){
        if("char".equals(s.type)){
            if(s.normal.length()==1&&Character.isLetter(s.normal.charAt(0))){
                boolean upper=capsOn!=shiftOn;
                return upper?s.normal.toUpperCase():s.normal.toLowerCase();
            }
            return shiftOn&&s.shifted!=null?s.shifted:s.normal;
        }
        return s.label;
    }

    private void pressSpec(KeySpec s){
        switch(s.type){
            case "shift":
                shiftOn=!shiftOn;updateKeyStates();return;
            case "caps":
                capsOn=!capsOn;sendKey("CAPSLOCK",new ArrayList<>());updateKeyStates();return;
            case "mod":
                if(activeMods.contains(s.key))activeMods.remove(s.key);else activeMods.add(s.key);
                updateKeyStates();return;
            case "char":
                List<String> mods=new ArrayList<>(activeMods);
                if(shiftOn)mods.add("SHIFT");
                if(activeMods.isEmpty()){
                    sendText(displayFor(s));
                }else{
                    sendKey(s.normal,mods);
                }
                clearMomentary();return;
            default:
                List<String> smods=new ArrayList<>(activeMods);
                if(shiftOn&&!"SPACE".equals(s.key))smods.add("SHIFT");
                if("SPACE".equals(s.key)&&activeMods.isEmpty())sendText(" ");
                else sendKey(s.key,smods);
                clearMomentary();
        }
    }

    private void clearMomentary(){
        shiftOn=false;
        activeMods.remove("CTRL");
        activeMods.remove("ALT");
        activeMods.remove("WIN");
        updateKeyStates();
    }

    private void updateKeyStates(){
        for(KeyBinding kb:printableBindings)kb.button.setText(displayFor(kb.spec));
        for(Map.Entry<String,List<Button>> e:modifierButtons.entrySet()){
            boolean active=("SHIFT".equals(e.getKey())&&shiftOn)||
                    ("CAPSLOCK".equals(e.getKey())&&capsOn)||activeMods.contains(e.getKey());
            for(Button b:e.getValue())b.setBackground(bg(active?KEY_ACTIVE:KEY,7,active?Color.rgb(96,165,250):BORDER));
        }
    }

    private LinearLayout buildSidePanel(){
        LinearLayout side=new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);

        LinearLayout tabs=row();
        mouseTab=button("Mouse",8);numTab=button("Num",8);mediaTab=button("Media",8);shortcutTab=button("Shortcuts",8);clipboardTab=button("Clip",8);
        for(Button b:new Button[]{mouseTab,numTab,mediaTab,shortcutTab,clipboardTab})tabs.addView(b,new LinearLayout.LayoutParams(0,dp(28),1f));
        side.addView(tabs,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(28)));

        sideContent=new FrameLayout(this);
        LinearLayout.LayoutParams contentLp=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f);
        contentLp.topMargin=dp(4);
        side.addView(sideContent,contentLp);

        mouseTab.setOnClickListener(v->{haptic();showSide("mouse");});
        numTab.setOnClickListener(v->{haptic();showSide("num");});
        mediaTab.setOnClickListener(v->{haptic();showSide("media");});
        shortcutTab.setOnClickListener(v->{haptic();showSide("shortcuts");});
        clipboardTab.setOnClickListener(v->{haptic();showSide("clipboard");});
        showSide("mouse");
        return side;
    }

    private void setTabActive(Button active){
        for(Button b:new Button[]{mouseTab,numTab,mediaTab,shortcutTab,clipboardTab})
            b.setBackground(bg(b==active?ACCENT:KEY,7,b==active?Color.rgb(59,130,246):BORDER));
    }

    private void showSide(String which){
        sideContent.removeAllViews();
        if("mouse".equals(which)){setTabActive(mouseTab);sideContent.addView(buildMousePanel());}
        else if("num".equals(which)){setTabActive(numTab);sideContent.addView(buildNumpad());}
        else if("media".equals(which)){setTabActive(mediaTab);sideContent.addView(buildMediaPanel());}
        else if("clipboard".equals(which)){setTabActive(clipboardTab);sideContent.addView(buildClipboardPanel());}
        else{setTabActive(shortcutTab);sideContent.addView(buildShortcutPanel());}
    }

    private View buildMousePanel(){
        LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);
        TouchpadView pad=new TouchpadView(this);
        pad.setSender(new TouchpadView.Sender() {
            @Override public void send(JSONObject event){link.send(event);}
            @Override public void haptic(){MainActivity.this.haptic();}
        });
        p.addView(pad,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f));

        LinearLayout buttons=row();
        Button left=button("Left",9),right=button("Right",9),middle=button("Middle",9);
        left.setOnClickListener(v->{haptic();sendMouse("left","click");});
        right.setOnClickListener(v->{haptic();sendMouse("right","click");});
        middle.setOnClickListener(v->{haptic();sendMouse("middle","click");});
        buttons.addView(left,new LinearLayout.LayoutParams(0,dp(34),1f));
        buttons.addView(right,new LinearLayout.LayoutParams(0,dp(34),1f));
        buttons.addView(middle,new LinearLayout.LayoutParams(0,dp(34),1f));
        LinearLayout.LayoutParams blp=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(34));
        blp.topMargin=dp(4);p.addView(buttons,blp);
        return p;
    }

    private View buildNumpad(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        String[][] rows={{"7","8","9","/"},{"4","5","6","*"},{"1","2","3","-"},{"0",".","Enter","+"},{"Home","End","PgUp","PgDn"}};
        for(String[] rr:rows){
            LinearLayout row=row();
            for(String x:rr){
                Button b=button(x,10);
                b.setOnClickListener(v->{haptic();handleNumpad(x);});
                row.addView(b,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f));
            }
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f);
            lp.bottomMargin=dp(3);root.addView(row,lp);
        }
        return root;
    }

    private void handleNumpad(String x){
        if(x.length()==1)sendText(x);
        else{
            String k=x;
            if("Enter".equals(x))k="ENTER";else if("PgUp".equals(x))k="PAGEUP";else if("PgDn".equals(x))k="PAGEDOWN";
            else k=x.toUpperCase();
            sendKey(k,new ArrayList<>());
        }
    }

    private View buildMediaPanel(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        String[][] rows={{"Prev","Play/Pause","Next"},{"Mute","Vol −","Vol +"},{"Home","PgUp","PgDn"}};
        String[][] keys={{"MEDIA_PREV","MEDIA_PLAY","MEDIA_NEXT"},{"VOLUME_MUTE","VOLUME_DOWN","VOLUME_UP"},{"HOME","PAGEUP","PAGEDOWN"}};
        for(int r=0;r<rows.length;r++){
            LinearLayout row=row();
            for(int i=0;i<rows[r].length;i++){
                final String key=keys[r][i];
                Button b=button(rows[r][i],9);b.setOnClickListener(v->{haptic();sendKey(key,new ArrayList<>());});
                row.addView(b,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f));
            }
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f);
            lp.bottomMargin=dp(3);root.addView(row,lp);
        }
        return root;
    }

    private View buildClipboardPanel(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(4),dp(4),dp(4),dp(4));

        TextView title=textView("Secure Clipboard",11,TEXT,true);
        root.addView(title,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(28)));

        clipboardPreview=new EditText(this);
        clipboardPreview.setTextColor(TEXT);
        clipboardPreview.setHintTextColor(MUTED);
        clipboardPreview.setHint("Clipboard text preview");
        clipboardPreview.setTextSize(9);
        clipboardPreview.setGravity(Gravity.TOP|Gravity.START);
        clipboardPreview.setPadding(dp(8),dp(6),dp(8),dp(6));
        clipboardPreview.setBackground(bg(Color.rgb(9,19,33),8,BORDER));
        LinearLayout.LayoutParams previewLp=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f);
        previewLp.bottomMargin=dp(5);
        root.addView(clipboardPreview,previewLp);

        LinearLayout row1=row();
        Button phoneToPc=button("Phone → PC",9);
        Button pcToPhone=button("PC → Phone",9);
        row1.addView(phoneToPc,new LinearLayout.LayoutParams(0,dp(34),1f));
        row1.addView(pcToPhone,new LinearLayout.LayoutParams(0,dp(34),1f));
        root.addView(row1,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(34)));

        LinearLayout row2=row();
        Button pastePc=button("Send + Paste",9);
        Button clear=button("Clear",9);
        row2.addView(pastePc,new LinearLayout.LayoutParams(0,dp(34),1f));
        row2.addView(clear,new LinearLayout.LayoutParams(0,dp(34),1f));
        LinearLayout.LayoutParams row2Lp=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(34));
        row2Lp.topMargin=dp(4);
        root.addView(row2,row2Lp);

        phoneToPc.setOnClickListener(v->{
            haptic();
            String text=getPhoneClipboard();
            if(text.isEmpty()) text=clipboardPreview.getText().toString();
            clipboardPreview.setText(text);
            link.setRemoteClipboard(text);
            onStatus("connected","Clipboard sent to PC");
        });

        pcToPhone.setOnClickListener(v->{
            haptic();
            onStatus("connecting","Requesting PC clipboard...");
            link.requestClipboard();
        });

        pastePc.setOnClickListener(v->{
            haptic();
            String text=clipboardPreview.getText().toString();
            if(text.isEmpty()) text=getPhoneClipboard();
            link.setRemoteClipboard(text);
            handler.postDelayed(()->sendKey("v",new ArrayList<>(Arrays.asList("CTRL"))),80);
        });

        clear.setOnClickListener(v->{
            haptic();
            clipboardPreview.setText("");
            ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(cm!=null) cm.setPrimaryClip(ClipData.newPlainText("WirelessKey",""));
        });

        return root;
    }

    private String getPhoneClipboard(){
        try{
            ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(cm==null||!cm.hasPrimaryClip()||cm.getPrimaryClip()==null||cm.getPrimaryClip().getItemCount()==0)return "";
            CharSequence text=cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            return text==null?"":text.toString();
        }catch(Exception e){
            return "";
        }
    }

    private View buildShortcutPanel(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView profile=textView("Smart shortcuts · "+currentProfile,8,MUTED,true);
        profile.setPadding(dp(4),0,dp(4),0);
        root.addView(profile,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(22)));

        shortcutGrid=new LinearLayout(this);
        shortcutGrid.setOrientation(LinearLayout.VERTICAL);
        root.addView(shortcutGrid,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,2f));

        TextView macroTitle=textView("My Macros · tap to run · hold to edit",8,Color.rgb(125,211,252),true);
        macroTitle.setPadding(dp(4),0,dp(4),0);
        root.addView(macroTitle,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(22)));

        macroGrid=new LinearLayout(this);
        macroGrid.setOrientation(LinearLayout.VERTICAL);
        root.addView(macroGrid,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,0.8f));

        renderShortcuts();
        renderMacros();
        return root;
    }

    private static final class Shortcut {
        final String label,type,key;
        final String[] mods;
        Shortcut(String l,String t,String k,String...m){label=l;type=t;key=k;mods=m;}
    }

    private List<Shortcut> shortcutsFor(String p){
        switch(p){
            case "autocad": return Arrays.asList(
                    new Shortcut("Line","cmd","L"),new Shortcut("Polyline","cmd","PL"),new Shortcut("Move","cmd","M"),
                    new Shortcut("Copy","cmd","CO"),new Shortcut("Rotate","cmd","RO"),new Shortcut("Trim","cmd","TR"),
                    new Shortcut("Extend","cmd","EX"),new Shortcut("Offset","cmd","O"),new Shortcut("Zoom","cmd","Z"),
                    new Shortcut("Properties","key","1","CTRL"));
            case "revit": return Arrays.asList(
                    new Shortcut("Save","key","s","CTRL"),new Shortcut("Undo","key","z","CTRL"),
                    new Shortcut("Visibility","sequence","VG"),new Shortcut("Align","sequence","AL"),
                    new Shortcut("Trim/Extend","sequence","TR"),new Shortcut("Move","sequence","MV"),
                    new Shortcut("Copy","sequence","CO"),new Shortcut("Delete","key","DELETE"),
                    new Shortcut("Escape","key","ESC"),new Shortcut("Redo","key","y","CTRL"));
            case "excel": return Arrays.asList(
                    new Shortcut("Copy","key","c","CTRL"),new Shortcut("Paste","key","v","CTRL"),
                    new Shortcut("Save","key","s","CTRL"),new Shortcut("Find","key","f","CTRL"),
                    new Shortcut("Edit Cell","key","F2"),new Shortcut("Format","key","1","CTRL"),
                    new Shortcut("Top","key","UP","CTRL"),new Shortcut("Bottom","key","DOWN","CTRL"));
            case "powerpoint": return Arrays.asList(
                    new Shortcut("Start Show","key","F5"),new Shortcut("From Current","key","F5","SHIFT"),
                    new Shortcut("Next","key","RIGHT"),new Shortcut("Previous","key","LEFT"),
                    new Shortcut("Black Screen","text","b"),new Shortcut("White Screen","text","w"),
                    new Shortcut("End Show","key","ESC"),new Shortcut("Save","key","s","CTRL"));
            case "vscode": return Arrays.asList(
                    new Shortcut("Command Palette","key","p","CTRL","SHIFT"),new Shortcut("Quick Open","key","p","CTRL"),
                    new Shortcut("Save","key","s","CTRL"),new Shortcut("Find","key","f","CTRL"),
                    new Shortcut("Terminal","key","`","CTRL"),new Shortcut("Sidebar","key","b","CTRL"),
                    new Shortcut("Comment","key","/","CTRL"),new Shortcut("Format","key","i","CTRL","SHIFT"));
            case "browser": return Arrays.asList(
                    new Shortcut("Address Bar","key","l","CTRL"),new Shortcut("New Tab","key","t","CTRL"),
                    new Shortcut("Close Tab","key","w","CTRL"),new Shortcut("Reopen Tab","key","t","CTRL","SHIFT"),
                    new Shortcut("Refresh","key","r","CTRL"),new Shortcut("Back","key","LEFT","ALT"),
                    new Shortcut("Forward","key","RIGHT","ALT"),new Shortcut("Find","key","f","CTRL"));
            default: return Arrays.asList(
                    new Shortcut("Copy","key","c","CTRL"),new Shortcut("Paste","key","v","CTRL"),
                    new Shortcut("Undo","key","z","CTRL"),new Shortcut("Redo","key","y","CTRL"),
                    new Shortcut("Save","key","s","CTRL"),new Shortcut("Find","key","f","CTRL"),
                    new Shortcut("Alt + Tab","key","TAB","ALT"),new Shortcut("Desktop","key","d","WIN"),
                    new Shortcut("Explorer","key","e","WIN"),new Shortcut("Task Manager","key","ESC","CTRL","SHIFT"));
        }
    }

    private void renderShortcuts(){
        if(shortcutGrid==null)return;
        shortcutGrid.removeAllViews();
        List<Shortcut> list=shortcutsFor(currentProfile);
        int cols=2;
        for(int i=0;i<list.size();i+=cols){
            LinearLayout r=row();
            for(int j=0;j<cols;j++){
                if(i+j<list.size()){
                    Shortcut s=list.get(i+j);Button b=button(s.label,8.5f);
                    b.setOnClickListener(v->{haptic();fireShortcut(s);});
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f);
                    lp.rightMargin=dp(3);r.addView(b,lp);
                }else r.addView(new View(this),new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f));
            }
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f);
            rp.bottomMargin=dp(3);shortcutGrid.addView(r,rp);
        }
    }

    private void fireShortcut(Shortcut s){
        if("text".equals(s.type)){sendText(s.key);return;}
        if("sequence".equals(s.type)){
            char[] chars=s.key.toCharArray();
            for(int i=0;i<chars.length;i++){
                final String k=String.valueOf(Character.toLowerCase(chars[i]));
                handler.postDelayed(()->sendKey(k,new ArrayList<>()),i*42L);
            }
            return;
        }
        if("cmd".equals(s.type)){
            sendText(s.key);handler.postDelayed(()->sendKey("ENTER",new ArrayList<>()),24);return;
        }
        sendKey(s.key,new ArrayList<>(Arrays.asList(s.mods)));
    }

    private void renderMacros(){
        if(macroGrid==null)return;
        macroGrid.removeAllViews();
        for(int i=0;i<macros.size();i+=2){
            LinearLayout r=row();
            for(int j=0;j<2;j++){
                int index=i+j;
                if(index<macros.size()){
                    MacroStore.Macro macro=macros.get(index);
                    Button b=button(macro.label,8.5f);
                    b.setOnClickListener(v->{haptic();runMacro(macro.action);});
                    b.setOnLongClickListener(v->{haptic();editMacro(index);return true;});
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f);
                    lp.rightMargin=dp(3);
                    r.addView(b,lp);
                }else{
                    r.addView(new View(this),new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f));
                }
            }
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f);
            rp.bottomMargin=dp(3);
            macroGrid.addView(r,rp);
        }
    }

    private void editMacro(int index){
        if(index<0||index>=macros.size())return;
        MacroStore.Macro macro=macros.get(index);

        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16),dp(8),dp(16),0);

        EditText label=new EditText(this);
        label.setHint("Macro name");
        label.setText(macro.label);
        box.addView(label,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(48)));

        EditText action=new EditText(this);
        action.setHint("keys:CTRL+SHIFT+S  |  text:Hello  |  sequence:CTRL+C;ALT+TAB;CTRL+V");
        action.setText(macro.action);
        action.setSingleLine(false);
        box.addView(action,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(86)));

        new AlertDialog.Builder(this)
                .setTitle("Edit WirelessKey Macro")
                .setView(box)
                .setPositiveButton("Save",(d,w)->{
                    macro.label=label.getText().toString().trim();
                    if(macro.label.isEmpty())macro.label="Macro "+(index+1);
                    macro.action=action.getText().toString().trim();
                    macroStore.save(macros);
                    renderMacros();
                })
                .setNegativeButton("Cancel",null)
                .show();
    }

    private void runMacro(String action){
        if(action==null||action.trim().isEmpty())return;
        String raw=action.trim();
        if(raw.startsWith("text:")){
            sendText(raw.substring(5));
            return;
        }
        if(raw.startsWith("keys:")){
            runChord(raw.substring(5));
            return;
        }
        if(raw.startsWith("sequence:")){
            String[] steps=raw.substring(9).split(";");
            long delay=0;
            for(String step:steps){
                final String s=step.trim();
                if(s.isEmpty())continue;
                handler.postDelayed(()->{
                    if(s.startsWith("text="))sendText(s.substring(5));
                    else runChord(s);
                },delay);
                delay+=110;
            }
            return;
        }
        sendText(raw);
    }

    private void runChord(String chord){
        String[] parts=chord.trim().split("\\+");
        if(parts.length==0)return;
        List<String> mods=new ArrayList<>();
        for(int i=0;i<parts.length-1;i++){
            String p=parts[i].trim().toUpperCase();
            if("CTRL".equals(p)||"ALT".equals(p)||"SHIFT".equals(p)||"WIN".equals(p))mods.add(p);
        }
        String key=parts[parts.length-1].trim();
        if(key.isEmpty())return;
        sendKey(key,mods);
    }

    private void haptic(){
        if(haptics)getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    private void sendText(String s){
        try{JSONObject o=new JSONObject();o.put("type","text");o.put("text",s);link.send(o);}catch(Exception ignored){}
    }

    private void sendKey(String key,List<String> mods){
        try{
            JSONObject o=new JSONObject();o.put("type","key");o.put("key",key);
            JSONArray arr=new JSONArray();for(String m:mods)arr.put(m);o.put("modifiers",arr);link.send(o);
        }catch(Exception ignored){}
    }

    private void sendMouse(String button,String action){
        try{JSONObject o=new JSONObject();o.put("type","mouse");o.put("button",button);o.put("action",action);link.send(o);}catch(Exception ignored){}
    }

    @Override
    public void onStatus(String kind, String detail) {
        statusText.setText(detail);
        if("connected".equals(kind)){
            statusText.setTextColor(Color.rgb(52,211,153));
            connectButton.setText("Reconnect");
            handler.postDelayed(this::reloadKnownPeersIntoSpinner,120);
        }else if("error".equals(kind)){
            statusText.setTextColor(Color.rgb(251,113,133));
        }else if("connecting".equals(kind)||"authenticating".equals(kind)||"reconnecting".equals(kind)||"discovering".equals(kind)){
            statusText.setTextColor(Color.rgb(251,191,36));
        }else{
            statusText.setTextColor(MUTED);
        }
    }

    @Override
    public void onDiscovery(JSONObject d) {
        String ip=d.optString("ip","");
        int port=d.optInt("port",8765);
        String host=ip+(port==8765?"":":"+port);
        String label=d.optString("name",d.optString("pcName","WirelessKey PC"))+" · "+ip;
        if(!deviceHosts.contains(host)){
            deviceHosts.add(host);
            if(deviceAdapter.getCount()==1&&"Searching...".equals(String.valueOf(deviceAdapter.getItem(0))))deviceAdapter.clear();
            if(deviceAdapter.getCount()==0)deviceAdapter.add("Choose PC");
            deviceAdapter.add(label);
            deviceAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onDiscoveryDone() {
        if(deviceHosts.isEmpty()){
            deviceAdapter.clear();deviceAdapter.add("No PC found");deviceAdapter.notifyDataSetChanged();
            onStatus("offline","Enter PC IP manually");
        }else onStatus("offline","PC found · select it");
    }

    @Override
    public void onContext(JSONObject context) {
        currentProfile=context.optString("profile","standard");
        appText.setText(context.optString("activeApp","Desktop"));
        if(shortcutGrid!=null)renderShortcuts();
    }

    @Override
    public void onLatency(long ms) {
        latencyText.setText(ms+" ms");
    }

    @Override
    public void onClipboard(String text) {
        if (text == null) text = "";
        final String value = text;
        ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(cm!=null) cm.setPrimaryClip(ClipData.newPlainText("WirelessKey PC",value));
        if(clipboardPreview!=null) clipboardPreview.setText(value);
        onStatus("connected","PC clipboard copied to phone");
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if(link!=null)link.shutdown();
        super.onDestroy();
    }
}

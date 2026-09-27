package com.wirelesskey.remote;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

    private EditText hostInput, codeInput;
    private Button connectButton, hapticButton;
    private TextView statusText, appText, latencyText;
    private Spinner deviceSpinner;
    private ArrayAdapter<String> deviceAdapter;
    private final List<String> deviceHosts = new ArrayList<>();

    private FrameLayout sideContent;
    private Button mouseTab, numTab, mediaTab, shortcutTab;
    private LinearLayout shortcutGrid;

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

        setContentView(buildUi());
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
        b.setBackground(bg(KEY, 7, BORDER));
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
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(5),dp(3),dp(5),dp(3));
        bar.setBackground(bg(PANEL,10,Color.rgb(29,43,64)));

        TextView brand = textView("WirelessKey 4.0", 12, TEXT, true);
        bar.addView(brand, new LinearLayout.LayoutParams(dp(105), LinearLayout.LayoutParams.MATCH_PARENT));

        Button find = button("Find PC", 9);
        find.setOnClickListener(v -> {
            haptic();
            deviceHosts.clear();
            deviceAdapter.clear();
            deviceAdapter.add("Searching...");
            deviceAdapter.notifyDataSetChanged();
            link.discover();
            onStatus("discovering","Finding PCs...");
        });
        bar.addView(find, new LinearLayout.LayoutParams(dp(67), dp(29)));

        deviceSpinner = new Spinner(this);
        deviceAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());
        deviceAdapter.add("Discovered PCs");
        deviceSpinner.setAdapter(deviceAdapter);
        deviceSpinner.setBackground(bg(Color.rgb(9,19,33),7,BORDER));
        deviceSpinner.setPopupBackgroundDrawable(bg(PANEL,6,BORDER));
        deviceSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position > 0 && position-1 < deviceHosts.size()) {
                    hostInput.setText(deviceHosts.get(position-1));
                }
            }
        });
        LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(dp(130), dp(29));
        spinnerLp.leftMargin=dp(4);
        bar.addView(deviceSpinner, spinnerLp);

        hostInput = new EditText(this);
        hostInput.setText(link.loadHost().replace(":8765",""));
        hostInput.setHint("PC IP");
        hostInput.setHintTextColor(MUTED);
        hostInput.setTextColor(TEXT);
        hostInput.setTextSize(9);
        hostInput.setSingleLine(true);
        hostInput.setPadding(dp(7),0,dp(7),0);
        hostInput.setBackground(bg(Color.rgb(9,19,33),7,BORDER));
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(dp(115), dp(29));
        hostLp.leftMargin=dp(4);
        bar.addView(hostInput, hostLp);

        codeInput = new EditText(this);
        codeInput.setText(link.loadCode());
        codeInput.setHint("Code");
        codeInput.setHintTextColor(MUTED);
        codeInput.setTextColor(TEXT);
        codeInput.setTextSize(9);
        codeInput.setSingleLine(true);
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeInput.setGravity(Gravity.CENTER);
        codeInput.setPadding(dp(4),0,dp(4),0);
        codeInput.setBackground(bg(Color.rgb(9,19,33),7,BORDER));
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(dp(60), dp(29));
        codeLp.leftMargin=dp(4);
        bar.addView(codeInput, codeLp);

        connectButton = button("Connect",9);
        connectButton.setBackground(bg(ACCENT,7,Color.rgb(59,130,246)));
        connectButton.setOnClickListener(v -> {
            haptic();
            String host=hostInput.getText().toString().trim();
            String code=codeInput.getText().toString().trim();
            if (host.isEmpty()) { onStatus("error","Find or enter PC"); return; }
            if (code.length()!=6) { onStatus("error","Enter 6-digit code"); return; }
            link.connect(host,code);
        });
        LinearLayout.LayoutParams conLp = new LinearLayout.LayoutParams(dp(66),dp(29));
        conLp.leftMargin=dp(4);
        bar.addView(connectButton,conLp);

        hapticButton = button("Haptic",8);
        hapticButton.setBackground(bg(haptics?Color.rgb(22,78,99):KEY,7,BORDER));
        hapticButton.setOnClickListener(v -> {
            haptics=!haptics;
            link.setHaptics(haptics);
            hapticButton.setBackground(bg(haptics?Color.rgb(22,78,99):KEY,7,BORDER));
            if (haptics) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        });
        LinearLayout.LayoutParams hapLp = new LinearLayout.LayoutParams(dp(58),dp(29));
        hapLp.leftMargin=dp(4);
        bar.addView(hapticButton,hapLp);

        LinearLayout context = new LinearLayout(this);
        context.setOrientation(LinearLayout.HORIZONTAL);
        context.setGravity(Gravity.CENTER_VERTICAL);
        context.setPadding(dp(7),0,dp(7),0);
        context.setBackground(bg(Color.rgb(12,23,40),20,Color.rgb(43,65,94)));
        appText=textView("Desktop",8,Color.rgb(219,234,254),true);
        latencyText=textView("— ms",8,Color.rgb(125,211,252),false);
        context.addView(appText,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f));
        context.addView(latencyText,new LinearLayout.LayoutParams(dp(40),LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams ctxLp = new LinearLayout.LayoutParams(dp(128),dp(25));
        ctxLp.leftMargin=dp(4);
        bar.addView(context,ctxLp);

        statusText=textView("Offline",8,MUTED,false);
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f);
        stLp.leftMargin=dp(4);
        bar.addView(statusText,stLp);

        return bar;
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
        mouseTab=button("Mouse",8);numTab=button("Num",8);mediaTab=button("Media",8);shortcutTab=button("Shortcuts",8);
        for(Button b:new Button[]{mouseTab,numTab,mediaTab,shortcutTab})tabs.addView(b,new LinearLayout.LayoutParams(0,dp(28),1f));
        side.addView(tabs,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(28)));

        sideContent=new FrameLayout(this);
        LinearLayout.LayoutParams contentLp=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f);
        contentLp.topMargin=dp(4);
        side.addView(sideContent,contentLp);

        mouseTab.setOnClickListener(v->{haptic();showSide("mouse");});
        numTab.setOnClickListener(v->{haptic();showSide("num");});
        mediaTab.setOnClickListener(v->{haptic();showSide("media");});
        shortcutTab.setOnClickListener(v->{haptic();showSide("shortcuts");});
        showSide("mouse");
        return side;
    }

    private void setTabActive(Button active){
        for(Button b:new Button[]{mouseTab,numTab,mediaTab,shortcutTab})
            b.setBackground(bg(b==active?ACCENT:KEY,7,b==active?Color.rgb(59,130,246):BORDER));
    }

    private void showSide(String which){
        sideContent.removeAllViews();
        if("mouse".equals(which)){setTabActive(mouseTab);sideContent.addView(buildMousePanel());}
        else if("num".equals(which)){setTabActive(numTab);sideContent.addView(buildNumpad());}
        else if("media".equals(which)){setTabActive(mediaTab);sideContent.addView(buildMediaPanel());}
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

    private View buildShortcutPanel(){
        shortcutGrid=new LinearLayout(this);
        shortcutGrid.setOrientation(LinearLayout.VERTICAL);
        renderShortcuts();
        return shortcutGrid;
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
            LinearLayout row=row();
            for(int j=0;j<cols;j++){
                if(i+j<list.size()){
                    Shortcut s=list.get(i+j);Button b=button(s.label,9);
                    b.setOnClickListener(v->{haptic();fireShortcut(s);});
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f);
                    lp.rightMargin=dp(3);row.addView(b,lp);
                }else row.addView(new View(this),new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f));
            }
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f);
            rp.bottomMargin=dp(3);shortcutGrid.addView(row,rp);
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
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if(link!=null)link.shutdown();
        super.onDestroy();
    }
}

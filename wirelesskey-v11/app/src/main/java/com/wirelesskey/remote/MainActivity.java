package com.wirelesskey.remote;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.HorizontalScrollView;
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
    private static final int BG = Color.rgb(7,9,13);
    private static final int PANEL = Color.rgb(16,20,26);
    private static final int PANEL_2 = Color.rgb(22,27,34);
    private static final int KEY = Color.rgb(31,36,44);
    private static final int KEY_ALT = Color.rgb(25,29,36);
    private static final int KEY_ACTIVE = Color.rgb(30,58,91);
    private static final int BORDER = Color.rgb(42,48,57);
    private static final int TEXT = Color.rgb(246,248,251);
    private static final int MUTED = Color.rgb(136,146,160);
    private static final int ACCENT = Color.rgb(76,132,255);
    private static final int ACCENT_SOFT = Color.rgb(31,59,96);
    private static final int SUCCESS = Color.rgb(69,196,139);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SecureLink link;

    private static final int QR_SCAN_REQUEST = 4120;
    private EditText hostInput, codeInput;
    private Button connectButton, hapticButton;
    private TextView statusText, appText, latencyText;
    private Spinner deviceSpinner;
    private ArrayAdapter<String> deviceAdapter;
    private final List<String> deviceHosts = new ArrayList<>();

    private FrameLayout sideContent;
    private FrameLayout workspaceHost;
    private View appRoot;
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
    private final Map<String, Button> workspaceButtons = new HashMap<>();
    private String currentProfile = "standard";
    private String currentWorkspace = "deck";
    private SharedPreferences uiPrefs;
    private float pointerSensitivity = 1.18f;
    private float scrollSpeed = 1.0f;
    private boolean precisionMode = false;
    private boolean naturalScroll = false;
    private TouchpadView activeTouchpad;

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
        uiPrefs = getSharedPreferences("wirelesskey_ui", MODE_PRIVATE);
        pointerSensitivity = uiPrefs.getFloat("pointer_sensitivity", 1.18f);
        scrollSpeed = uiPrefs.getFloat("scroll_speed", 1.0f);
        precisionMode = uiPrefs.getBoolean("precision_mode", false);
        naturalScroll = uiPrefs.getBoolean("natural_scroll", false);
        currentWorkspace = uiPrefs.getString("workspace", "deck");

        appRoot = buildUi();
        setContentView(appRoot);
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

    private GradientDrawable gradient(int startColor, int endColor, float radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{startColor,endColor});
        d.setCornerRadius(dp(radiusDp));
        if(strokeColor!=0)d.setStroke(dp(1),strokeColor);
        return d;
    }

    private LinearLayout card(float radiusDp){
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(gradient(
                Color.rgb(19,23,29),
                Color.rgb(14,17,22),
                radiusDp,
                Color.rgb(35,41,50)));
        card.setPadding(dp(10),dp(8),dp(10),dp(8));
        card.setElevation(dp(0.6f));
        return card;
    }

    private TextView eyebrow(String text){
        TextView t=textView(text,7.6f,Color.rgb(112,151,208),true);
        t.setLetterSpacing(0.08f);
        return t;
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
        b.setBackground(stateBg(KEY, Color.rgb(45,52,63), 8, Color.rgb(39,45,54)));
        b.setElevation(dp(0.7f));
        installPressAnimator(b);
        return b;
    }

    private void installPressAnimator(View view){
        StateListAnimator animator=new StateListAnimator();
        ObjectAnimator pressed=ObjectAnimator.ofPropertyValuesHolder(
                view,
                PropertyValuesHolder.ofFloat(View.SCALE_X,0.965f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y,0.965f));
        pressed.setDuration(45);
        ObjectAnimator normal=ObjectAnimator.ofPropertyValuesHolder(
                view,
                PropertyValuesHolder.ofFloat(View.SCALE_X,1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y,1f));
        normal.setDuration(70);
        animator.addState(new int[]{android.R.attr.state_pressed},pressed);
        animator.addState(new int[]{},normal);
        view.setStateListAnimator(animator);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(4),dp(3),dp(4),dp(3));

        root.addView(buildTopBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        workspaceHost = new FrameLayout(this);
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        hostLp.topMargin = dp(4);
        root.addView(workspaceHost, hostLp);

        root.addView(buildWorkspaceRail(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(33)));

        handler.post(() -> showWorkspace(currentWorkspace));
        return root;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8),dp(2),dp(6),dp(2));
        bar.setBackground(bg(PANEL,11,Color.rgb(34,39,48)));
        bar.setElevation(dp(1));

        TextView brand = textView("WirelessKey", 11.5f, TEXT, true);
        bar.addView(brand,new LinearLayout.LayoutParams(dp(92),LinearLayout.LayoutParams.MATCH_PARENT));

        statusText=textView("Offline",8.2f,MUTED,true);
        statusText.setGravity(Gravity.CENTER);
        statusText.setMaxLines(1);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusText.setBackground(bg(Color.rgb(25,30,38),18,0));
        LinearLayout.LayoutParams statusLp=new LinearLayout.LayoutParams(0,dp(25),1f);
        statusLp.leftMargin=dp(4);
        bar.addView(statusText,statusLp);

        appText=textView("Desktop",8.2f,Color.rgb(200,210,224),true);
        appText.setGravity(Gravity.CENTER);
        appText.setMaxLines(1);
        appText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams appLp=new LinearLayout.LayoutParams(dp(88),dp(25));
        appLp.leftMargin=dp(4);
        bar.addView(appText,appLp);

        latencyText=textView("— ms",8,Color.rgb(115,193,255),true);
        latencyText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams latLp=new LinearLayout.LayoutParams(dp(50),dp(25));
        latLp.leftMargin=dp(2);
        bar.addView(latencyText,latLp);

        Button control=button("•••",10);
        control.setBackground(stateBg(Color.rgb(27,33,42),Color.rgb(43,51,63),9,0));
        control.setOnClickListener(v->{haptic();showControlCenter(v);});
        LinearLayout.LayoutParams controlLp=new LinearLayout.LayoutParams(dp(44),dp(27));
        controlLp.leftMargin=dp(4);
        bar.addView(control,controlLp);

        // Hidden connection state used by pairing dialogs and remembered PCs.
        hostInput = new EditText(this);
        hostInput.setText(link.loadHost());
        codeInput = new EditText(this);
        codeInput.setText(link.loadCode());

        deviceSpinner = new Spinner(this);
        deviceAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<String>());
        deviceAdapter.add("Remembered PCs");
        deviceSpinner.setAdapter(deviceAdapter);
        deviceSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if(position>0 && position-1<deviceHosts.size()){
                    hostInput.setText(deviceHosts.get(position-1));
                    codeInput.setText("");
                }
            }
        });

        connectButton = button("Connect",8);
        hapticButton = button("Haptic",8);
        return bar;
    }

    private View buildWorkspaceRail() {
        HorizontalScrollView scroll=new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.setBackground(bg(Color.rgb(14,17,22),10,0));

        LinearLayout rail=new LinearLayout(this);
        rail.setOrientation(LinearLayout.HORIZONTAL);
        rail.setGravity(Gravity.CENTER);
        rail.setPadding(dp(4),dp(3),dp(4),dp(3));

        String[][] items={{"Deck","deck"},{"Pad","pad"},{"Work","work"},{"CAD","cad"},{"Media","media"}};
        for(String[] item:items){
            Button b=button(item[0],8.2f);
            workspaceButtons.put(item[1],b);
            b.setOnClickListener(v->{haptic();showWorkspace(item[1]);});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(27),1f);
            lp.leftMargin=dp(2); lp.rightMargin=dp(2);
            rail.addView(b,lp);
        }

        Button more=button("Control",8.2f);
        more.setBackground(stateBg(Color.rgb(28,35,45),Color.rgb(42,51,64),8,0));
        more.setOnClickListener(v->{haptic();showControlCenter(v);});
        LinearLayout.LayoutParams moreLp=new LinearLayout.LayoutParams(0,dp(27),1f);
        moreLp.leftMargin=dp(4);
        rail.addView(more,moreLp);

        scroll.addView(rail,new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.MATCH_PARENT,
                HorizontalScrollView.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    private void updateWorkspaceButtons(){
        for(Map.Entry<String,Button> e:workspaceButtons.entrySet()){
            boolean active=e.getKey().equals(currentWorkspace);
            e.getValue().setBackground(stateBg(
                    active?Color.rgb(37,78,132):Color.rgb(24,29,36),
                    active?Color.rgb(45,92,154):Color.rgb(38,45,55),
                    8,0));
            e.getValue().setTextColor(active?Color.rgb(224,238,255):Color.rgb(158,168,181));
        }
    }

    private void showWorkspace(String name){
        if(workspaceHost==null)return;
        currentWorkspace=name==null?"deck":name;
        uiPrefs.edit().putString("workspace",currentWorkspace).apply();
        workspaceHost.removeAllViews();
        activeTouchpad=null;

        View content;
        switch(currentWorkspace){
            case "pad": content=buildMousePanel(); break;
            case "work": content=buildWorkWorkspace(); break;
            case "cad": content=buildCadWorkspace(); break;
            case "media": content=buildMediaWorkspace(); break;
            default: currentWorkspace="deck"; content=buildDeckWorkspace(); break;
        }
        workspaceHost.addView(content,new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        updateWorkspaceButtons();
    }

    private View buildDeckWorkspace(){
        ResizableSplitLayout split=new ResizableSplitLayout(this);
        float ratio=uiPrefs.getFloat("ratio_deck",0.72f);
        split.setPanels(buildKeyboard(),buildMousePanel(),ratio);
        split.setOnRatioChangedListener(r->uiPrefs.edit().putFloat("ratio_deck",r).apply());
        return split;
    }

    private View buildWorkWorkspace(){
        LinearLayout productivity=new LinearLayout(this);
        productivity.setOrientation(LinearLayout.VERTICAL);

        View shortcuts=buildShortcutPanel();
        View clipboard=buildClipboardPanel();
        productivity.addView(shortcuts,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,0,1.25f));
        LinearLayout.LayoutParams clipLp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,0,0.85f);
        clipLp.topMargin=dp(4);
        productivity.addView(clipboard,clipLp);

        ResizableSplitLayout split=new ResizableSplitLayout(this);
        float ratio=uiPrefs.getFloat("ratio_work",0.66f);
        split.setPanels(buildKeyboard(),productivity,ratio);
        split.setOnRatioChangedListener(r->uiPrefs.edit().putFloat("ratio_work",r).apply());
        return split;
    }

    private View buildCadWorkspace(){
        LinearLayout tools=new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);

        View pad=buildMousePanel(true);
        tools.addView(pad,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,0,1.3f));

        View shortcuts=buildShortcutPanel();
        LinearLayout.LayoutParams shortLp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,0,0.9f);
        shortLp.topMargin=dp(4);
        tools.addView(shortcuts,shortLp);

        ResizableSplitLayout split=new ResizableSplitLayout(this);
        float ratio=uiPrefs.getFloat("ratio_cad",0.62f);
        split.setPanels(buildKeyboard(),tools,ratio);
        split.setOnRatioChangedListener(r->uiPrefs.edit().putFloat("ratio_cad",r).apply());
        return split;
    }

    private View buildMediaWorkspace(){
        ResizableSplitLayout split=new ResizableSplitLayout(this);
        float ratio=uiPrefs.getFloat("ratio_media",0.72f);
        split.setPanels(buildMousePanel(),buildMediaPanel(),ratio);
        split.setOnRatioChangedListener(r->uiPrefs.edit().putFloat("ratio_media",r).apply());
        return split;
    }

    private void showControlCenter(View anchor){
        final PopupWindow popup=new PopupWindow(this);
        int screenWidth=getResources().getDisplayMetrics().widthPixels;
        int width=Math.min(dp(430),Math.round(screenWidth*0.58f));

        ScrollView scroll=new ScrollView(this);
        LinearLayout panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16),dp(14),dp(16),dp(16));
        panel.setBackground(bg(Color.rgb(18,22,29),18,Color.rgb(45,52,64)));
        scroll.addView(panel);

        TextView title=textView("Control Center",16,TEXT,true);
        panel.addView(title,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(34)));

        TextView connection=textView(
                "PC  "+(hostInput==null?"":hostInput.getText().toString())+
                        "    "+latencyText.getText().toString(),9,Color.rgb(151,171,194),false);
        panel.addView(connection,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(28)));

        TextView section=textView("CONNECTION",8,Color.rgb(103,158,231),true);
        panel.addView(section,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(24)));

        deviceSpinner = new Spinner(this);
        deviceSpinner.setAdapter(deviceAdapter);
        deviceSpinner.setBackground(bg(Color.rgb(27,32,41),9,Color.rgb(47,55,67)));
        panel.addView(deviceSpinner,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(38)));

        LinearLayout connectRow=row();
        Button connect=button("Connect",9);
        Button qr=button("QR Pair",9);
        Button find=button("Find PC",9);
        Button manual=button("Manual",9);
        Button[] connButtons={connect,qr,find,manual};
        for(Button b:connButtons){
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(36),1f);
            lp.leftMargin=dp(2);lp.rightMargin=dp(2);
            connectRow.addView(b,lp);
        }
        LinearLayout.LayoutParams crLp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(36));
        crLp.topMargin=dp(6);
        panel.addView(connectRow,crLp);

        connect.setOnClickListener(v->{
            String host=hostInput.getText().toString().trim();
            String code=codeInput.getText().toString().trim();
            if(host.isEmpty()){showManualPairDialog();return;}
            if(code.isEmpty()&&!link.hasTrustedTokenForHost(host)){showManualPairDialog();return;}
            link.connect(host,code);popup.dismiss();
        });
        qr.setOnClickListener(v->{popup.dismiss();startQrScan();});
        find.setOnClickListener(v->{
            deviceHosts.clear();deviceAdapter.clear();deviceAdapter.add("Searching...");
            deviceAdapter.notifyDataSetChanged();link.discover();
            onStatus("discovering","Finding PCs...");
        });
        manual.setOnClickListener(v->{popup.dismiss();showManualPairDialog();});

        TextView pointerSec=textView("POINTER",8,Color.rgb(103,158,231),true);
        LinearLayout.LayoutParams psLp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(26));psLp.topMargin=dp(12);
        panel.addView(pointerSec,psLp);

        LinearLayout toggles=row();
        Button precision=button(precisionMode?"Precision ON":"Precision OFF",8.5f);
        Button natural=button(naturalScroll?"Natural ON":"Natural OFF",8.5f);
        Button haptic=button(haptics?"Haptic ON":"Haptic OFF",8.5f);
        for(Button b:new Button[]{precision,natural,haptic}){
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(35),1f);
            lp.leftMargin=dp(2);lp.rightMargin=dp(2);toggles.addView(b,lp);
        }
        panel.addView(toggles,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(35)));

        precision.setOnClickListener(v->{
            precisionMode=!precisionMode;
            uiPrefs.edit().putBoolean("precision_mode",precisionMode).apply();
            precision.setText(precisionMode?"Precision ON":"Precision OFF");
            applyTouchpadSettings();
        });
        natural.setOnClickListener(v->{
            naturalScroll=!naturalScroll;
            uiPrefs.edit().putBoolean("natural_scroll",naturalScroll).apply();
            natural.setText(naturalScroll?"Natural ON":"Natural OFF");
            applyTouchpadSettings();
        });
        haptic.setOnClickListener(v->{
            haptics=!haptics;link.setHaptics(haptics);
            haptic.setText(haptics?"Haptic ON":"Haptic OFF");
        });

        TextView sensLabel=textView("Pointer sensitivity",9,MUTED,false);
        panel.addView(sensLabel,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(24)));
        SeekBar sens=new SeekBar(this);
        sens.setMax(195);
        sens.setProgress(Math.round((pointerSensitivity-0.45f)*100f));
        panel.addView(sens,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(34)));
        sens.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
            public void onProgressChanged(SeekBar s,int p,boolean user){
                if(!user)return;
                pointerSensitivity=0.45f+p/100f;
                uiPrefs.edit().putFloat("pointer_sensitivity",pointerSensitivity).apply();
                applyTouchpadSettings();
            }
        });

        TextView workspaceSec=textView("WORKSPACES",8,Color.rgb(103,158,231),true);
        panel.addView(workspaceSec,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(28)));
        LinearLayout wsRow=row();
        for(String ws:new String[]{"deck","pad","work","cad","media"}){
            Button b=button(ws.toUpperCase(),7.8f);
            b.setOnClickListener(v->{showWorkspace(ws);popup.dismiss();});
            wsRow.addView(b,new LinearLayout.LayoutParams(0,dp(34),1f));
        }
        panel.addView(wsRow,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(34)));

        TextView diag=textView(
                "Pointer coalesced  "+link.getCoalescedPointerEvents()+
                        "    dropped  "+link.getDroppedPointerEvents(),8,MUTED,false);
        LinearLayout.LayoutParams dgLp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(30));dgLp.topMargin=dp(12);
        panel.addView(diag,dgLp);

        Button disconnect=button("Disconnect",9);
        disconnect.setBackground(stateBg(Color.rgb(80,33,40),Color.rgb(111,41,50),9,0));
        disconnect.setOnClickListener(v->{link.disconnect();popup.dismiss();});
        LinearLayout.LayoutParams disLp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(38));disLp.topMargin=dp(8);
        panel.addView(disconnect,disLp);

        popup.setContentView(scroll);
        popup.setWidth(width);
        popup.setHeight(getResources().getDisplayMetrics().heightPixels-dp(18));
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(bg(Color.rgb(18,22,29),18,Color.rgb(45,52,64)));
        popup.setElevation(dp(12));
        popup.setAnimationStyle(android.R.style.Animation_Dialog);
        popup.showAtLocation(appRoot,Gravity.RIGHT|Gravity.CENTER_VERTICAL,dp(9),0);
    }

    private void applyTouchpadSettings(){
        if(activeTouchpad==null)return;
        activeTouchpad.setSensitivity(pointerSensitivity);
        activeTouchpad.setScrollSpeed(scrollSpeed);
        activeTouchpad.setPrecisionMode(precisionMode);
        activeTouchpad.setNaturalScroll(naturalScroll);
    }

    private void showManualPairDialog(){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16),dp(6),dp(16),0);

        EditText host=new EditText(this);
        host.setHint("PC IP or IP:port");
        host.setSingleLine(true);
        host.setText(hostInput==null?"":hostInput.getText().toString());
        box.addView(host,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(50)));

        EditText code=new EditText(this);
        code.setHint("6-digit code (optional for trusted PC)");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setSingleLine(true);
        code.setText(codeInput==null?"":codeInput.getText().toString());
        box.addView(code,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(50)));

        new AlertDialog.Builder(this)
                .setTitle("Manual PC connection")
                .setView(box)
                .setPositiveButton("Connect",(d,w)->{
                    String h=host.getText().toString().trim();
                    String p=code.getText().toString().trim();
                    if(h.isEmpty()){onStatus("error","Enter the PC address");return;}
                    hostInput.setText(h);codeInput.setText(p);link.connect(h,p);
                })
                .setNegativeButton("Cancel",null)
                .show();
    }

    private void startQrScan() {
        onStatus("discovering","Opening WirelessKey scanner...");
        try {
            Intent intent = new Intent(this, QRScannerActivity.class);
            startActivityForResult(intent, QR_SCAN_REQUEST);
        } catch (Exception error) {
            onStatus("error","Scanner could not open · use Find PC or Manual");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == QR_SCAN_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                String contents = data.getStringExtra(QRScannerActivity.EXTRA_QR_VALUE);
                if (contents != null && !contents.trim().isEmpty()) {
                    onStatus("discovering","QR scanned · verifying PC identity...");
                    link.connectPairingQr(contents.trim());
                    handler.postDelayed(this::reloadKnownPeersIntoSpinner,450);
                    return;
                }
            }
            onStatus("offline","QR scan cancelled");
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
        printableBindings.clear();
        modifierButtons.clear();

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
        if(sp<=9f){
            b.setTextColor(Color.rgb(180,190,204));
            b.setBackground(stateBg(
                    Color.rgb(24,28,35),
                    Color.rgb(39,45,55),
                    7,0));
            b.setElevation(dp(0.3f));
        }
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
        return buildMousePanel(false);
    }

    private View buildMousePanel(boolean forcePrecision){
        LinearLayout p=new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(1),dp(1),dp(1),dp(1));

        TouchpadView pad=new TouchpadView(this);
        activeTouchpad=pad;
        pad.setSensitivity(pointerSensitivity);
        pad.setScrollSpeed(scrollSpeed);
        pad.setNaturalScroll(naturalScroll);
        pad.setPrecisionMode(forcePrecision||precisionMode);
        pad.setSender(new TouchpadView.Sender() {
            @Override public void send(JSONObject event){link.send(event);}
            @Override public void move(float dx,float dy,long eventNanos){
                link.sendPointerMove(dx,dy,eventNanos);
            }
            @Override public void scroll(boolean horizontal,float delta,long eventNanos){
                link.sendPointerScroll(horizontal,delta,eventNanos);
            }
            @Override public void haptic(){MainActivity.this.haptic();}
        });
        p.addView(pad,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,0,1f));

        LinearLayout buttons=row();
        Button left=button("Left click",8.4f),right=button("Right click",8.4f);
        left.setBackground(stateBg(Color.rgb(24,29,36),Color.rgb(42,49,59),8,0));
        right.setBackground(stateBg(Color.rgb(24,29,36),Color.rgb(42,49,59),8,0));
        left.setOnClickListener(v->{haptic();sendMouse("left","click");});
        right.setOnClickListener(v->{haptic();sendMouse("right","click");});
        buttons.addView(left,new LinearLayout.LayoutParams(0,dp(29),1f));
        LinearLayout.LayoutParams rightLp=new LinearLayout.LayoutParams(0,dp(29),1f);
        rightLp.leftMargin=dp(4);
        buttons.addView(right,rightLp);
        LinearLayout.LayoutParams blp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(29));
        blp.topMargin=dp(4);
        p.addView(buttons,blp);
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

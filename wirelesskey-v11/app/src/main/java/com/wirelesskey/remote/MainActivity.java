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
import java.util.Collections;
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
    private View statusDot;
    private Spinner deviceSpinner;
    private ArrayAdapter<String> deviceAdapter;
    private final List<String> deviceHosts = new ArrayList<>();
    private final List<String> deviceFingerprints = new ArrayList<>();
    private final List<String> deviceNames = new ArrayList<>();
    private final List<Long> deviceLastSeen = new ArrayList<>();

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
    private float pointerAcceleration = 0.032f;
    private boolean precisionMode = false;
    private boolean naturalScroll = false;
    private String pointerPreset = "custom";
    private TouchpadView activeTouchpad;
    private ResizableSplitLayout activeSplit;

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
        pointerAcceleration = uiPrefs.getFloat("pointer_acceleration", 0.032f);
        precisionMode = uiPrefs.getBoolean("precision_mode", false);
        naturalScroll = uiPrefs.getBoolean("natural_scroll", false);
        pointerPreset = uiPrefs.getString("pointer_preset", "custom");
        currentWorkspace = uiPrefs.getString("workspace", "deck");

        appRoot = buildUi();
        setContentView(appRoot);
        reloadKnownPeersIntoSpinner();
        handler.postDelayed(this::autoConnectPreferredPc, 650);
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
        root.setBackground(gradient(
                Color.rgb(8,10,14),
                Color.rgb(5,7,10),
                0,
                0));
        root.setPadding(dp(6),dp(5),dp(6),dp(5));

        root.addView(buildTopBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36)));

        workspaceHost = new FrameLayout(this);
        workspaceHost.setClipToPadding(false);
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        hostLp.topMargin = dp(6);
        root.addView(workspaceHost, hostLp);

        LinearLayout.LayoutParams railLp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        railLp.topMargin=dp(6);
        root.addView(buildWorkspaceRail(), railLp);

        handler.post(() -> showWorkspace(currentWorkspace));
        return root;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8),dp(3),dp(5),dp(3));
        bar.setBackground(gradient(
                Color.rgb(22,27,35),
                Color.rgb(15,19,25),
                12,
                Color.rgb(36,42,51)));
        bar.setElevation(dp(1.4f));

        LinearLayout brandBlock=new LinearLayout(this);
        brandBlock.setOrientation(LinearLayout.VERTICAL);
        brandBlock.setGravity(Gravity.CENTER_VERTICAL);

        TextView brand = textView("WirelessKey", 11.4f, TEXT, true);
        TextView edition = textView("PRECISION", 6.3f, Color.rgb(103,151,220), true);
        edition.setLetterSpacing(0.10f);
        brandBlock.addView(brand,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,0,1.15f));
        brandBlock.addView(edition,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,0,0.85f));
        bar.addView(brandBlock,new LinearLayout.LayoutParams(dp(92),LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout statusPill=new LinearLayout(this);
        statusPill.setOrientation(LinearLayout.HORIZONTAL);
        statusPill.setGravity(Gravity.CENTER_VERTICAL);
        statusPill.setPadding(dp(9),0,dp(9),0);
        statusPill.setBackground(gradient(
                Color.rgb(28,33,41),
                Color.rgb(22,26,33),
                16,
                0));

        statusDot=new View(this);
        statusDot.setBackground(bg(Color.rgb(94,105,120),20,0));
        LinearLayout.LayoutParams dotLp=new LinearLayout.LayoutParams(dp(7),dp(7));
        dotLp.rightMargin=dp(7);
        statusPill.addView(statusDot,dotLp);

        statusText=textView("Offline",8.1f,Color.rgb(171,181,195),true);
        statusText.setMaxLines(1);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusPill.addView(statusText,new LinearLayout.LayoutParams(
                0,LinearLayout.LayoutParams.MATCH_PARENT,1f));

        LinearLayout.LayoutParams statusLp=new LinearLayout.LayoutParams(0,dp(26),1f);
        statusLp.leftMargin=dp(7);
        bar.addView(statusPill,statusLp);

        appText=textView("Desktop",8f,Color.rgb(190,201,215),true);
        appText.setGravity(Gravity.CENTER);
        appText.setMaxLines(1);
        appText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        appText.setBackground(bg(Color.rgb(25,29,36),14,0));
        LinearLayout.LayoutParams appLp=new LinearLayout.LayoutParams(dp(82),dp(25));
        appLp.leftMargin=dp(5);
        bar.addView(appText,appLp);

        latencyText=textView("— ms",7.8f,Color.rgb(129,190,255),true);
        latencyText.setGravity(Gravity.CENTER);
        latencyText.setBackground(bg(Color.rgb(20,38,58),14,0));
        LinearLayout.LayoutParams latLp=new LinearLayout.LayoutParams(dp(52),dp(25));
        latLp.leftMargin=dp(4);
        bar.addView(latencyText,latLp);

        Button control=button("•••",10);
        control.setBackground(stateBg(
                Color.rgb(27,32,40),
                Color.rgb(43,50,61),
                12,0));
        control.setElevation(0);
        control.setOnClickListener(v->{haptic();showControlCenter(v);});
        LinearLayout.LayoutParams controlLp=new LinearLayout.LayoutParams(dp(42),dp(27));
        controlLp.leftMargin=dp(4);
        bar.addView(control,controlLp);

        hostInput = new EditText(this);
        hostInput.setText(link.loadHost());
        codeInput = new EditText(this);
        codeInput.setText(link.loadCode());

        deviceSpinner = new Spinner(this);
        deviceAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<String>()) {
            private TextView style(TextView t, boolean drop){
                t.setTextColor(drop?TEXT:Color.rgb(211,219,230));
                t.setTextSize(drop?10:9);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setPadding(dp(10),0,dp(10),0);
                if(drop)t.setBackgroundColor(Color.rgb(22,27,34));
                return t;
            }
            @Override public View getView(int position,View convertView,android.view.ViewGroup parent){
                return style((TextView)super.getView(position,convertView,parent),false);
            }
            @Override public View getDropDownView(int position,View convertView,android.view.ViewGroup parent){
                return style((TextView)super.getDropDownView(position,convertView,parent),true);
            }
        };
        deviceAdapter.add("Choose a remembered PC");
        bindDeviceSpinner(deviceSpinner);

        connectButton = button("Connect",8);
        hapticButton = button("Haptic",8);
        return bar;
    }

    private void bindDeviceSpinner(Spinner spinner) {
        if (spinner == null) return;
        spinner.setAdapter(deviceAdapter);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}

            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                int index = position - 1;
                if (index >= 0 && index < deviceHosts.size()) {
                    hostInput.setText(deviceHosts.get(index));
                    codeInput.setText("");
                }
            }
        });

        String selectedHost = hostInput == null ? "" : hostInput.getText().toString().trim();
        for (int i = 0; i < deviceHosts.size(); i++) {
            if (deviceHosts.get(i).equalsIgnoreCase(selectedHost)) {
                spinner.setSelection(i + 1, false);
                break;
            }
        }
    }

    private View buildWorkspaceRail() {
        LinearLayout shell=new LinearLayout(this);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        shell.setGravity(Gravity.CENTER_VERTICAL);
        shell.setPadding(dp(4),0,dp(4),0);

        LinearLayout dock=new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(dp(4),dp(3),dp(4),dp(3));
        dock.setBackground(gradient(
                Color.rgb(20,24,30),
                Color.rgb(14,17,22),
                13,
                Color.rgb(33,39,47)));
        dock.setElevation(dp(1));

        String[][] items={{"Deck","deck"},{"Pad","pad"},{"Work","work"},{"CAD","cad"},{"Media","media"}};
        for(String[] item:items){
            Button b=button(item[0],8.1f);
            b.setElevation(0);
            b.setTextColor(Color.rgb(144,154,168));
            workspaceButtons.put(item[1],b);
            b.setOnClickListener(v->{haptic();showWorkspace(item[1]);});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(27),1f);
            lp.leftMargin=dp(1);lp.rightMargin=dp(1);
            dock.addView(b,lp);
        }

        shell.addView(dock,new LinearLayout.LayoutParams(0,dp(33),1f));

        Button more=button("•••",10);
        more.setElevation(0);
        more.setBackground(stateBg(
                Color.rgb(25,30,38),
                Color.rgb(42,49,60),
                13,0));
        more.setOnClickListener(v->{haptic();showControlCenter(v);});
        LinearLayout.LayoutParams moreLp=new LinearLayout.LayoutParams(dp(44),dp(33));
        moreLp.leftMargin=dp(6);
        shell.addView(more,moreLp);

        return shell;
    }

    private void updateWorkspaceButtons(){
        for(Map.Entry<String,Button> e:workspaceButtons.entrySet()){
            boolean active=e.getKey().equals(currentWorkspace);
            e.getValue().setBackground(stateBg(
                    active?ACCENT_SOFT:Color.TRANSPARENT,
                    active?Color.rgb(39,74,119):Color.rgb(28,33,40),
                    10,0));
            e.getValue().setTextColor(
                    active?Color.rgb(229,239,255):Color.rgb(137,148,163));
            e.getValue().setTypeface(
                    Typeface.DEFAULT,
                    active?Typeface.BOLD:Typeface.NORMAL);
        }
    }

    private void showWorkspace(String name){
        if(workspaceHost==null)return;
        currentWorkspace=name==null?"deck":name;
        uiPrefs.edit().putString("workspace",currentWorkspace).apply();
        workspaceHost.removeAllViews();
        activeTouchpad=null;
        activeSplit=null;

        View content;
        switch(currentWorkspace){
            case "pad": content=buildMousePanel(); break;
            case "work": content=buildWorkWorkspace(); break;
            case "cad": content=buildCadWorkspace(); break;
            case "media": content=buildMediaWorkspace(); break;
            default: currentWorkspace="deck"; content=buildDeckWorkspace(); break;
        }
        content.setAlpha(0f);
        content.setTranslationY(dp(8));
        workspaceHost.addView(content,new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        content.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(150)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
        updateWorkspaceButtons();
    }

    private View buildDeckWorkspace(){
        ResizableSplitLayout split=new ResizableSplitLayout(this);
        activeSplit=split;
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
        activeSplit=split;
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
        activeSplit=split;
        float ratio=uiPrefs.getFloat("ratio_cad",0.62f);
        split.setPanels(buildKeyboard(),tools,ratio);
        split.setOnRatioChangedListener(r->uiPrefs.edit().putFloat("ratio_cad",r).apply());
        return split;
    }

    private View buildMediaWorkspace(){
        ResizableSplitLayout split=new ResizableSplitLayout(this);
        activeSplit=split;
        float ratio=uiPrefs.getFloat("ratio_media",0.72f);
        split.setPanels(buildMousePanel(),buildMediaPanel(),ratio);
        split.setOnRatioChangedListener(r->uiPrefs.edit().putFloat("ratio_media",r).apply());
        return split;
    }

    private void styleControlToggle(Button b, boolean on){
        b.setTextColor(on?Color.rgb(228,239,255):Color.rgb(161,171,185));
        b.setBackground(stateBg(
                on?ACCENT_SOFT:Color.rgb(27,32,40),
                on?Color.rgb(43,79,126):Color.rgb(42,49,59),
                11,0));
        b.setElevation(0);
    }

    private void showControlCenter(View anchor){
        final PopupWindow popup=new PopupWindow(this);
        int screenWidth=getResources().getDisplayMetrics().widthPixels;
        int screenHeight=getResources().getDisplayMetrics().heightPixels;
        int width=Math.min(dp(420),Math.round(screenWidth*0.55f));

        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14),dp(12),dp(14),dp(14));
        panel.setBackground(gradient(
                Color.rgb(20,24,31),
                Color.rgb(12,15,20),
                20,
                Color.rgb(43,49,59)));
        scroll.addView(panel);

        LinearLayout header=row();
        LinearLayout heading=new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title=textView("Control Center",15.5f,TEXT,true);
        TextView subtitle=textView("WirelessKey 4.3 · Precision Edition",7.4f,MUTED,false);
        heading.addView(title,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,0,1.15f));
        heading.addView(subtitle,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,0,0.85f));
        header.addView(heading,new LinearLayout.LayoutParams(0,dp(42),1f));

        Button close=button("Close",8.3f);
        close.setElevation(0);
        close.setBackground(stateBg(
                Color.rgb(26,31,39),
                Color.rgb(43,50,61),
                11,0));
        close.setOnClickListener(v->popup.dismiss());
        header.addView(close,new LinearLayout.LayoutParams(dp(60),dp(32)));
        panel.addView(header,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(42)));

        LinearLayout connectionCard=card(15);
        connectionCard.addView(eyebrow("CONNECTION"),new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(20)));

        LinearLayout connStatus=row();
        TextView pc=textView(
                selectedPcName(),
                9.2f,TEXT,true);
        TextView ping=textView(latencyText==null?"— ms":latencyText.getText().toString(),
                8.2f,Color.rgb(126,191,255),true);
        ping.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        connStatus.addView(pc,new LinearLayout.LayoutParams(0,dp(28),1f));
        connStatus.addView(ping,new LinearLayout.LayoutParams(dp(62),dp(28)));
        connectionCard.addView(connStatus,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(28)));

        deviceSpinner = new Spinner(this);
        bindDeviceSpinner(deviceSpinner);
        deviceSpinner.setBackground(gradient(
                Color.rgb(29,34,42),
                Color.rgb(24,28,35),
                10,
                Color.rgb(45,52,62)));
        connectionCard.addView(deviceSpinner,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(40)));

        LinearLayout connectRow=row();
        Button connect=button("Connect / Switch",8.4f);
        Button qr=button("QR Pair",8.7f);
        Button find=button("Find PC",8.7f);
        Button manual=button("Manual",8.7f);
        connect.setBackground(stateBg(ACCENT_SOFT,Color.rgb(43,79,126),10,0));
        qr.setBackground(stateBg(Color.rgb(24,73,61),Color.rgb(31,94,77),10,0));
        for(Button b:new Button[]{connect,qr,find,manual}){
            b.setElevation(0);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(36),1f);
            lp.leftMargin=dp(2);lp.rightMargin=dp(2);
            connectRow.addView(b,lp);
        }
        LinearLayout.LayoutParams crLp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(36));
        crLp.topMargin=dp(7);
        connectionCard.addView(connectRow,crLp);

        LinearLayout manageRow=row();
        Button favorite=button("★ Favorite",8f);
        Button renamePc=button("Rename",8f);
        Button forgetPc=button("Forget",8f);
        for(Button b:new Button[]{favorite,renamePc,forgetPc}){
            b.setElevation(0);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(32),1f);
            lp.leftMargin=dp(2); lp.rightMargin=dp(2);
            manageRow.addView(b,lp);
        }
        favorite.setBackground(stateBg(
                Color.rgb(48,45,29),
                Color.rgb(65,60,35),
                9,0));
        forgetPc.setTextColor(Color.rgb(255,192,198));
        forgetPc.setBackground(stateBg(
                Color.rgb(55,29,35),
                Color.rgb(78,37,45),
                9,0));

        LinearLayout.LayoutParams manageLp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(32));
        manageLp.topMargin=dp(6);
        connectionCard.addView(manageRow,manageLp);

        favorite.setOnClickListener(v->{favoriteSelectedPeer();popup.dismiss();});
        renamePc.setOnClickListener(v->{popup.dismiss();renameSelectedPeer();});
        forgetPc.setOnClickListener(v->{popup.dismiss();forgetSelectedPeer();});

        LinearLayout.LayoutParams cardLp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin=dp(10);
        panel.addView(connectionCard,cardLp);

        connect.setOnClickListener(v->{
            String host=hostInput.getText().toString().trim();
            String code=codeInput.getText().toString().trim();
            if(host.isEmpty()){popup.dismiss();showManualPairDialog();return;}
            if(code.isEmpty()&&!link.hasTrustedTokenForHost(host)){
                popup.dismiss();showManualPairDialog();return;
            }
            link.connect(host,code);popup.dismiss();
        });
        qr.setOnClickListener(v->{popup.dismiss();startQrScan();});
        find.setOnClickListener(v->{
            deviceHosts.clear();deviceAdapter.clear();deviceAdapter.add("Searching...");
            deviceAdapter.notifyDataSetChanged();link.discover();
            onStatus("discovering","Finding PCs...");
        });
        manual.setOnClickListener(v->{popup.dismiss();showManualPairDialog();});

        LinearLayout pointerCard=card(15);
        pointerCard.addView(eyebrow("POINTER"),new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(20)));

        LinearLayout presetRow=row();
        Button presetPrecision=button("Precision",8.2f);
        Button presetBalanced=button("Balanced",8.2f);
        Button presetFast=button("Fast",8.2f);
        for(Button b:new Button[]{presetPrecision,presetBalanced,presetFast}){
            b.setElevation(0);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(34),1f);
            lp.leftMargin=dp(2);lp.rightMargin=dp(2);
            presetRow.addView(b,lp);
        }
        styleControlToggle(presetPrecision,"precision".equals(pointerPreset));
        styleControlToggle(presetBalanced,"balanced".equals(pointerPreset));
        styleControlToggle(presetFast,"fast".equals(pointerPreset));
        pointerCard.addView(presetRow,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(34)));

        LinearLayout toggles=row();
        Button precision=button(precisionMode?"Precision · On":"Precision",8.3f);
        Button natural=button(naturalScroll?"Natural · On":"Natural",8.3f);
        Button haptic=button(haptics?"Haptic · On":"Haptic",8.3f);
        styleControlToggle(precision,precisionMode);
        styleControlToggle(natural,naturalScroll);
        styleControlToggle(haptic,haptics);
        for(Button b:new Button[]{precision,natural,haptic}){
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(34),1f);
            lp.leftMargin=dp(2);lp.rightMargin=dp(2);
            toggles.addView(b,lp);
        }
        pointerCard.addView(toggles,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(34)));

        LinearLayout sensHeader=row();
        TextView sensLabel=textView("Pointer sensitivity",8.7f,Color.rgb(184,194,207),false);
        TextView sensValue=textView(String.format(java.util.Locale.US,"%.2fx",pointerSensitivity),
                8.2f,Color.rgb(129,190,255),true);
        sensValue.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        sensHeader.addView(sensLabel,new LinearLayout.LayoutParams(0,dp(25),1f));
        sensHeader.addView(sensValue,new LinearLayout.LayoutParams(dp(54),dp(25)));
        LinearLayout.LayoutParams shLp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(25));
        shLp.topMargin=dp(8);
        pointerCard.addView(sensHeader,shLp);

        SeekBar sens=new SeekBar(this);
        sens.setMax(195);
        sens.setProgress(Math.round((pointerSensitivity-0.45f)*100f));
        pointerCard.addView(sens,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(30)));

        presetPrecision.setOnClickListener(v->{
            applyPointerPreset("precision");
            popup.dismiss();
        });
        presetBalanced.setOnClickListener(v->{
            applyPointerPreset("balanced");
            popup.dismiss();
        });
        presetFast.setOnClickListener(v->{
            applyPointerPreset("fast");
            popup.dismiss();
        });

        precision.setOnClickListener(v->{
            precisionMode=!precisionMode;
            pointerPreset="custom";
            uiPrefs.edit()
                    .putString("pointer_preset","custom")
                    .putBoolean("precision_mode",precisionMode)
                    .apply();
            precision.setText(precisionMode?"Precision · On":"Precision");
            styleControlToggle(precision,precisionMode);
            applyTouchpadSettings();
        });
        natural.setOnClickListener(v->{
            naturalScroll=!naturalScroll;
            uiPrefs.edit().putBoolean("natural_scroll",naturalScroll).apply();
            natural.setText(naturalScroll?"Natural · On":"Natural");
            styleControlToggle(natural,naturalScroll);
            applyTouchpadSettings();
        });
        haptic.setOnClickListener(v->{
            haptics=!haptics;link.setHaptics(haptics);
            haptic.setText(haptics?"Haptic · On":"Haptic");
            styleControlToggle(haptic,haptics);
        });
        sens.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
            public void onProgressChanged(SeekBar s,int p,boolean user){
                if(!user)return;
                pointerSensitivity=0.45f+p/100f;
                pointerPreset="custom";
                sensValue.setText(String.format(java.util.Locale.US,"%.2fx",pointerSensitivity));
                uiPrefs.edit()
                        .putString("pointer_preset","custom")
                        .putFloat("pointer_sensitivity",pointerSensitivity)
                        .apply();
                applyTouchpadSettings();
            }
        });
        LinearLayout.LayoutParams pointerLp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT);
        pointerLp.topMargin=dp(9);
        panel.addView(pointerCard,pointerLp);

        LinearLayout workspaceCard=card(15);
        workspaceCard.addView(eyebrow("WORKSPACES"),new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(20)));
        LinearLayout wsRow=row();
        String[][] workspaces={{"Deck","deck"},{"Pad","pad"},{"Work","work"},{"CAD","cad"},{"Media","media"}};
        for(String[] ws:workspaces){
            Button b=button(ws[0],8f);
            boolean active=ws[1].equals(currentWorkspace);
            styleControlToggle(b,active);
            b.setOnClickListener(v->{showWorkspace(ws[1]);popup.dismiss();});
            wsRow.addView(b,new LinearLayout.LayoutParams(0,dp(34),1f));
        }
        workspaceCard.addView(wsRow,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(34)));

        if(activeSplit!=null){
            TextView layoutLabel=eyebrow("MULTI-PANE LAYOUT");
            LinearLayout.LayoutParams labelLp=new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,dp(22));
            labelLp.topMargin=dp(7);
            workspaceCard.addView(layoutLabel,labelLp);

            LinearLayout layoutRow=row();
            Button keyboardFocus=button("Keyboard Focus",7.9f);
            Button balanced=button("Balanced",7.9f);
            Button padFocus=button("Pad Focus",7.9f);
            for(Button b:new Button[]{keyboardFocus,balanced,padFocus}){
                b.setElevation(0);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(33),1f);
                lp.leftMargin=dp(2);lp.rightMargin=dp(2);
                layoutRow.addView(b,lp);
            }
            keyboardFocus.setOnClickListener(v->{applyLayoutPreset(0.78f);popup.dismiss();});
            balanced.setOnClickListener(v->{applyLayoutPreset(0.66f);popup.dismiss();});
            padFocus.setOnClickListener(v->{applyLayoutPreset(0.52f);popup.dismiss();});
            workspaceCard.addView(layoutRow,new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,dp(33)));
        }

        LinearLayout.LayoutParams wsLp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT);
        wsLp.topMargin=dp(9);
        panel.addView(workspaceCard,wsLp);

        LinearLayout diagnosticsCard=card(15);
        diagnosticsCard.addView(eyebrow("INPUT HEALTH"),new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(20)));
        TextView diag=textView(
                "Coalesced  "+link.getCoalescedPointerEvents()+
                        "   ·   Dropped  "+link.getDroppedPointerEvents(),
                8.3f,Color.rgb(156,167,181),false);
        diagnosticsCard.addView(diag,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(25)));

        Button disconnect=button("Disconnect PC",8.7f);
        disconnect.setElevation(0);
        disconnect.setTextColor(Color.rgb(255,196,202));
        disconnect.setBackground(stateBg(
                Color.rgb(62,31,37),
                Color.rgb(86,39,47),
                10,0));
        disconnect.setOnClickListener(v->{link.disconnect();popup.dismiss();});
        LinearLayout.LayoutParams disconnectLp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(36));
        disconnectLp.topMargin=dp(5);
        diagnosticsCard.addView(disconnect,disconnectLp);
        LinearLayout.LayoutParams dgLp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT);
        dgLp.topMargin=dp(9);
        panel.addView(diagnosticsCard,dgLp);

        popup.setContentView(scroll);
        popup.setWidth(width);
        popup.setHeight(screenHeight-dp(18));
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(bg(Color.TRANSPARENT,20,0));
        popup.setElevation(dp(16));
        popup.setAnimationStyle(android.R.style.Animation_Dialog);
        popup.setOnDismissListener(()->{
            if(appRoot!=null)appRoot.animate().alpha(1f).setDuration(120).start();
        });

        if(appRoot!=null)appRoot.animate().alpha(0.78f).setDuration(120).start();
        scroll.setAlpha(0.55f);
        scroll.setTranslationX(dp(34));
        popup.showAtLocation(appRoot,Gravity.RIGHT|Gravity.CENTER_VERTICAL,dp(9),0);
        scroll.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    private void applyTouchpadSettings(){
        if(activeTouchpad==null)return;
        activeTouchpad.setSensitivity(pointerSensitivity);
        activeTouchpad.setScrollSpeed(scrollSpeed);
        activeTouchpad.setAcceleration(pointerAcceleration);
        activeTouchpad.setPrecisionMode(precisionMode);
        activeTouchpad.setNaturalScroll(naturalScroll);
    }

    private void applyPointerPreset(String preset){
        String p=preset==null?"balanced":preset.toLowerCase(java.util.Locale.US);
        pointerPreset=p;

        if("precision".equals(p)){
            pointerSensitivity=0.82f;
            pointerAcceleration=0.010f;
            scrollSpeed=0.82f;
            precisionMode=true;
        }else if("fast".equals(p)){
            pointerSensitivity=1.55f;
            pointerAcceleration=0.052f;
            scrollSpeed=1.25f;
            precisionMode=false;
        }else{
            pointerPreset="balanced";
            pointerSensitivity=1.18f;
            pointerAcceleration=0.032f;
            scrollSpeed=1.0f;
            precisionMode=false;
        }

        uiPrefs.edit()
                .putString("pointer_preset",pointerPreset)
                .putFloat("pointer_sensitivity",pointerSensitivity)
                .putFloat("pointer_acceleration",pointerAcceleration)
                .putFloat("scroll_speed",scrollSpeed)
                .putBoolean("precision_mode",precisionMode)
                .apply();
        applyTouchpadSettings();
    }

    private void applyLayoutPreset(float ratio){
        if(activeSplit==null)return;
        activeSplit.setRatio(ratio);
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

        String currentHost = hostInput == null ? "" : hostInput.getText().toString().trim();
        String favoriteFp = link.loadFavoritePeerFingerprint();

        deviceHosts.clear();
        deviceFingerprints.clear();
        deviceNames.clear();
        deviceLastSeen.clear();
        deviceAdapter.clear();
        deviceAdapter.add("Choose a remembered PC");

        try {
            JSONArray arr = new JSONArray(link.loadKnownPeersJson());
            List<JSONObject> peers = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject peer = arr.optJSONObject(i);
                if (peer != null && !peer.optString("host", "").trim().isEmpty()) peers.add(peer);
            }

            Collections.sort(peers, (a,b) -> {
                boolean af = a.optString("fingerprint", "").equalsIgnoreCase(favoriteFp);
                boolean bf = b.optString("fingerprint", "").equalsIgnoreCase(favoriteFp);
                if (af != bf) return af ? -1 : 1;
                return Long.compare(b.optLong("lastSeen", 0L), a.optLong("lastSeen", 0L));
            });

            int selectPosition = 0;
            for (JSONObject peer : peers) {
                String host = peer.optString("host", "").trim();
                String name = peer.optString("name", "WirelessKey PC").trim();
                String fp = peer.optString("fingerprint", "").trim().toLowerCase(java.util.Locale.US);
                long lastSeen = peer.optLong("lastSeen", 0L);

                deviceHosts.add(host);
                deviceFingerprints.add(fp);
                deviceNames.add(name.isEmpty() ? "WirelessKey PC" : name);
                deviceLastSeen.add(lastSeen);

                boolean favorite = !favoriteFp.isEmpty() && fp.equalsIgnoreCase(favoriteFp);
                String label = (favorite ? "★  " : "") +
                        (name.isEmpty() ? "WirelessKey PC" : name) +
                        "  ·  " + relativeLastSeen(lastSeen);
                deviceAdapter.add(label);

                if (host.equalsIgnoreCase(currentHost)) selectPosition = deviceHosts.size();
            }

            deviceSpinner.setSelection(selectPosition, false);
        } catch (Exception ignored) {
        }
        deviceAdapter.notifyDataSetChanged();
    }

    private String relativeLastSeen(long millis) {
        if (millis <= 0L) return "remembered";
        long age = Math.max(0L, System.currentTimeMillis() - millis);
        if (age < 60_000L) return "just now";
        if (age < 3_600_000L) return Math.max(1L, age / 60_000L) + "m ago";
        if (age < 86_400_000L) return Math.max(1L, age / 3_600_000L) + "h ago";
        if (age < 604_800_000L) return Math.max(1L, age / 86_400_000L) + "d ago";
        return "trusted";
    }

    private int selectedPeerIndex() {
        if (deviceSpinner == null) return -1;
        int index = deviceSpinner.getSelectedItemPosition() - 1;
        return index >= 0 && index < deviceHosts.size() ? index : -1;
    }

    private String selectedPcName() {
        int index = selectedPeerIndex();
        if (index >= 0 && index < deviceNames.size()) return deviceNames.get(index);
        return "No PC selected";
    }

    private void autoConnectPreferredPc() {
        String favorite = link.loadFavoritePeerFingerprint();
        if (!favorite.isEmpty()) {
            for (int i = 0; i < deviceFingerprints.size(); i++) {
                if (favorite.equalsIgnoreCase(deviceFingerprints.get(i))) {
                    String host = deviceHosts.get(i);
                    if (link.hasTrustedTokenForHost(host)) {
                        hostInput.setText(host);
                        codeInput.setText("");
                        if (deviceSpinner != null) deviceSpinner.setSelection(i + 1, false);
                        link.connect(host, "");
                        return;
                    }
                }
            }
        }

        String lastHost = link.loadHost();
        if (lastHost != null && !lastHost.trim().isEmpty() && link.hasTrustedTokenForHost(lastHost)) {
            hostInput.setText(lastHost);
            codeInput.setText("");
            link.connect(lastHost, "");
        }
    }

    private void favoriteSelectedPeer() {
        int index = selectedPeerIndex();
        if (index < 0) return;
        link.setFavoritePeer(deviceFingerprints.get(index));
        reloadKnownPeersIntoSpinner();
        onStatus("info", deviceNames.get(index) + " set as favorite");
    }

    private void renameSelectedPeer() {
        int index = selectedPeerIndex();
        if (index < 0) return;

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(deviceNames.get(index));
        input.selectAll();

        new AlertDialog.Builder(this)
                .setTitle("Rename remembered PC")
                .setView(input)
                .setPositiveButton("Save", (d,w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty() && link.renameKnownPeer(deviceFingerprints.get(index), name)) {
                        reloadKnownPeersIntoSpinner();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void forgetSelectedPeer() {
        int index = selectedPeerIndex();
        if (index < 0) return;

        String host = deviceHosts.get(index);
        String fp = deviceFingerprints.get(index);
        String name = deviceNames.get(index);

        new AlertDialog.Builder(this)
                .setTitle("Forget " + name + "?")
                .setMessage("WirelessKey will remove the saved certificate and trusted token for this PC.")
                .setPositiveButton("Forget", (d,w) -> {
                    if (link.forgetKnownPeer(host, fp)) {
                        if (hostInput != null && host.equalsIgnoreCase(hostInput.getText().toString().trim())) {
                            link.disconnect();
                            link.save("", "");
                            hostInput.setText("");
                            codeInput.setText("");
                        }
                        reloadKnownPeersIntoSpinner();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private LinearLayout buildKeyboard() {
        printableBindings.clear();
        modifierButtons.clear();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(5),dp(5),dp(3),dp(5));
        panel.setBackground(gradient(
                Color.rgb(17,20,25),
                Color.rgb(11,14,18),
                14,
                Color.rgb(31,36,44)));
        panel.setElevation(dp(0.8f));

        LinearLayout fn = row();
        String[] fnLabels={"Esc","F1","F2","F3","F4","F5","F6","F7","F8","F9","F10","F11","F12","Ins","Del"};
        String[] fnKeys={"ESC","F1","F2","F3","F4","F5","F6","F7","F8","F9","F10","F11","F12","INSERT","DELETE"};
        for(int i=0;i<fnKeys.length;i++) addKey(fn,KeySpec.special(fnKeys[i],fnLabels[i],i==0?1.25f:1f),8.5f);
        panel.addView(fn,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,0.70f));

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
        for(KeySpec[] specs:rows){
            LinearLayout r=row();
            for(KeySpec s:specs) addKey(r,s,10.5f);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f);
            lp.topMargin=dp(2);
            panel.addView(r,lp);
        }

        LinearLayout bottom=row();
        addKey(bottom,KeySpec.mod("CTRL","Ctrl",1.35f),9.6f);
        addKey(bottom,KeySpec.mod("WIN","Win",1.15f),9.6f);
        addKey(bottom,KeySpec.mod("ALT","Alt",1.15f),9.6f);
        addKey(bottom,KeySpec.special("SPACE","Space",5.6f),9.8f);
        addKey(bottom,KeySpec.mod("ALT","Alt",1.15f),9.6f);
        addKey(bottom,KeySpec.special("MENU","Menu",1.1f),9.2f);
        addKey(bottom,KeySpec.mod("CTRL","Ctrl",1.35f),9.6f);

        LinearLayout arrows=new LinearLayout(this);
        arrows.setOrientation(LinearLayout.VERTICAL);
        arrows.setPadding(dp(2),0,0,0);

        LinearLayout arrowTop=row();
        View upLeft=new View(this);
        View upRight=new View(this);
        arrowTop.addView(upLeft,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f));
        addKey(arrowTop,KeySpec.special("UP","↑",1f),8.7f);
        arrowTop.addView(upRight,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f));

        LinearLayout arrowBottom=row();
        addKey(arrowBottom,KeySpec.special("LEFT","←",1f),8.7f);
        addKey(arrowBottom,KeySpec.special("DOWN","↓",1f),8.7f);
        addKey(arrowBottom,KeySpec.special("RIGHT","→",1f),8.7f);

        arrows.addView(arrowTop,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,0,1f));
        LinearLayout.LayoutParams arrowBottomLp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,0,1f);
        arrowBottomLp.topMargin=dp(1);
        arrows.addView(arrowBottom,arrowBottomLp);

        bottom.addView(arrows,new LinearLayout.LayoutParams(
                0,LinearLayout.LayoutParams.MATCH_PARENT,3.1f));

        LinearLayout.LayoutParams bottomLp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,0,1f);
        bottomLp.topMargin=dp(2);
        panel.addView(bottom,bottomLp);

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
        b.setElevation(dp(0.55f));

        boolean functionKey=sp<=9f;
        boolean modifier="mod".equals(spec.type)||"shift".equals(spec.type)||"caps".equals(spec.type);
        boolean special="special".equals(spec.type);

        if("char".equals(spec.type)){
            b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            b.setTextColor(Color.rgb(240,243,248));
            b.setBackground(stateBg(
                    KEY,
                    Color.rgb(49,56,68),
                    8,0));
        }else if(functionKey){
            b.setTypeface(Typeface.DEFAULT,Typeface.NORMAL);
            b.setTextColor(Color.rgb(155,166,180));
            b.setBackground(stateBg(
                    Color.rgb(21,25,31),
                    Color.rgb(36,42,51),
                    7,0));
            b.setElevation(dp(0.2f));
        }else if(modifier){
            b.setTextColor(Color.rgb(188,198,211));
            b.setBackground(stateBg(
                    KEY_ALT,
                    Color.rgb(42,48,58),
                    8,0));
        }else if(special){
            boolean emphasized="ENTER".equals(spec.key)||"SPACE".equals(spec.key);
            b.setTextColor(emphasized?Color.rgb(225,235,249):Color.rgb(184,194,207));
            b.setBackground(stateBg(
                    emphasized?Color.rgb(34,43,55):KEY_ALT,
                    emphasized?Color.rgb(47,61,78):Color.rgb(42,48,58),
                    8,0));
        }

        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(
                0,LinearLayout.LayoutParams.MATCH_PARENT,spec.weight);
        lp.rightMargin=dp(2);
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
                        v.animate().scaleX(0.965f).scaleY(0.965f).setDuration(45).start();
                        haptic();pressSpec(spec);
                        repeating=new Runnable(){@Override public void run(){pressSpec(spec);handler.postDelayed(this,58);}};
                        handler.postDelayed(repeating,400);
                        return true;
                    }
                    if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                        v.animate().scaleX(1f).scaleY(1f).setDuration(70).start();
                        if(repeating!=null)handler.removeCallbacks(repeating);
                        return true;
                    }
                    return true;
                }
            });
        }else{
            b.setOnClickListener(v->{haptic();pressSpec(spec);});
            if("mod".equals(spec.type)
                    && ("CTRL".equals(spec.key)||"WIN".equals(spec.key)||"ALT".equals(spec.key))){
                b.setOnLongClickListener(v->{
                    haptic();
                    showModifierQuickPopup(v,spec.key);
                    return true;
                });
            }
        }
    }

    private void showModifierQuickPopup(View anchor,String modifier){
        final PopupWindow popup=new PopupWindow(this);

        LinearLayout popupCard=card(13);
        popupCard.setPadding(dp(8),dp(7),dp(8),dp(8));
        popupCard.addView(eyebrow(modifier+" QUICK ACTIONS"),new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(20)));

        LinearLayout actionRow=row();
        List<String[]> actions=new ArrayList<>();
        if("CTRL".equals(modifier)){
            actions.add(new String[]{"Copy","c","CTRL"});
            actions.add(new String[]{"Paste","v","CTRL"});
            actions.add(new String[]{"Undo","z","CTRL"});
            actions.add(new String[]{"Task Mgr","ESC","CTRL","SHIFT"});
        }else if("WIN".equals(modifier)){
            actions.add(new String[]{"Desktop","d","WIN"});
            actions.add(new String[]{"Explorer","e","WIN"});
            actions.add(new String[]{"Task View","TAB","WIN"});
            actions.add(new String[]{"Settings","i","WIN"});
        }else{
            actions.add(new String[]{"Next App","TAB","ALT"});
            actions.add(new String[]{"Prev App","TAB","ALT","SHIFT"});
            actions.add(new String[]{"Close","F4","ALT"});
            actions.add(new String[]{"Menu","SPACE","ALT"});
        }

        for(String[] action:actions){
            Button quick=button(action[0],7.8f);
            quick.setElevation(0);
            quick.setBackground(stateBg(
                    Color.rgb(28,34,43),
                    Color.rgb(43,51,63),
                    9,0));
            quick.setOnClickListener(v->{
                List<String> mods=new ArrayList<>();
                for(int i=2;i<action.length;i++)mods.add(action[i]);
                sendKey(action[1],mods);
                popup.dismiss();
            });
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(34),1f);
            lp.leftMargin=dp(2);lp.rightMargin=dp(2);
            actionRow.addView(quick,lp);
        }
        popupCard.addView(actionRow,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(34)));

        int width=dp(300);
        int height=dp(72);
        popup.setContentView(popupCard);
        popup.setWidth(width);
        popup.setHeight(height);
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(bg(Color.TRANSPARENT,13,0));
        popup.setElevation(dp(12));

        int[] loc=new int[2];
        anchor.getLocationOnScreen(loc);
        int screenW=getResources().getDisplayMetrics().widthPixels;
        int x=Math.max(dp(8),Math.min(screenW-width-dp(8),loc[0]+anchor.getWidth()/2-width/2));
        int y=Math.max(dp(8),loc[1]-height-dp(8));

        popupCard.setAlpha(0f);
        popupCard.setScaleX(0.96f);
        popupCard.setScaleY(0.96f);
        popup.showAtLocation(appRoot,Gravity.TOP|Gravity.LEFT,x,y);
        popupCard.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).start();
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
            for(Button b:e.getValue()){
                b.setTextColor(active?Color.rgb(225,238,255):Color.rgb(188,198,211));
                b.setBackground(stateBg(
                        active?ACCENT_SOFT:KEY_ALT,
                        active?Color.rgb(43,80,127):Color.rgb(42,48,58),
                        8,0));
            }
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
        pad.setAcceleration(pointerAcceleration);
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
            @Override public void cancelMotion(){link.cancelPointerMotion();}
            @Override public void haptic(){MainActivity.this.haptic();}
        });
        p.addView(pad,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,0,1f));

        LinearLayout buttons=row();
        Button left=button("Primary",8f),right=button("Secondary",8f);
        for(Button b:new Button[]{left,right}){
            b.setElevation(0);
            b.setTextColor(Color.rgb(149,160,174));
            b.setBackground(stateBg(
                    Color.rgb(20,24,30),
                    Color.rgb(34,40,49),
                    10,0));
        }
        left.setOnClickListener(v->{haptic();sendMouse("left","click");});
        right.setOnClickListener(v->{haptic();sendMouse("right","click");});
        buttons.addView(left,new LinearLayout.LayoutParams(0,dp(27),1f));
        LinearLayout.LayoutParams rightLp=new LinearLayout.LayoutParams(0,dp(27),1f);
        rightLp.leftMargin=dp(5);
        buttons.addView(right,rightLp);
        LinearLayout.LayoutParams blp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(27));
        blp.topMargin=dp(5);
        p.addView(buttons,blp);
        return p;
    }

    private View buildNumpad(){
        LinearLayout root=card(14);
        root.addView(eyebrow("NUMPAD"),new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(18)));

        String[][] rows={{"7","8","9","/"},{"4","5","6","*"},{"1","2","3","-"},{"0",".","Enter","+"},{"Home","End","PgUp","PgDn"}};
        for(String[] rr:rows){
            LinearLayout row=row();
            for(String x:rr){
                Button b=button(x,9.4f);
                b.setElevation(0);
                if("Enter".equals(x)||"+".equals(x)){
                    b.setBackground(stateBg(
                            Color.rgb(31,48,68),
                            Color.rgb(43,66,91),
                            9,0));
                }
                b.setOnClickListener(v->{haptic();handleNumpad(x);});
                LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(
                        0,LinearLayout.LayoutParams.MATCH_PARENT,1f);
                bp.rightMargin=dp(2);
                row.addView(b,bp);
            }
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,0,1f);
            lp.bottomMargin=dp(3);
            root.addView(row,lp);
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
        LinearLayout root=card(14);
        root.addView(eyebrow("MEDIA & PRESENTATION"),new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(18)));

        String[][] rows={{"Previous","Play / Pause","Next"},{"Mute","Volume −","Volume +"},{"Home","Page Up","Page Down"}};
        String[][] keys={{"MEDIA_PREV","MEDIA_PLAY","MEDIA_NEXT"},{"VOLUME_MUTE","VOLUME_DOWN","VOLUME_UP"},{"HOME","PAGEUP","PAGEDOWN"}};
        for(int r=0;r<rows.length;r++){
            LinearLayout row=row();
            for(int i=0;i<rows[r].length;i++){
                final String key=keys[r][i];
                Button b=button(rows[r][i],8.4f);
                b.setElevation(0);
                if("MEDIA_PLAY".equals(key)){
                    b.setBackground(stateBg(
                            ACCENT_SOFT,
                            Color.rgb(43,79,126),
                            9,0));
                }
                b.setOnClickListener(v->{haptic();sendKey(key,new ArrayList<>());});
                LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(
                        0,LinearLayout.LayoutParams.MATCH_PARENT,1f);
                bp.rightMargin=dp(3);
                row.addView(b,bp);
            }
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,0,1f);
            lp.bottomMargin=dp(3);
            root.addView(row,lp);
        }
        return root;
    }

    private View buildClipboardPanel(){
        LinearLayout root=card(14);

        LinearLayout header=row();
        TextView title=textView("Clipboard",10.5f,TEXT,true);
        TextView secure=textView("ENCRYPTED",6.8f,Color.rgb(117,194,161),true);
        secure.setLetterSpacing(0.08f);
        secure.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        header.addView(title,new LinearLayout.LayoutParams(0,dp(27),1f));
        header.addView(secure,new LinearLayout.LayoutParams(dp(72),dp(27)));
        root.addView(header,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(27)));

        clipboardPreview=new EditText(this);
        clipboardPreview.setTextColor(Color.rgb(224,230,239));
        clipboardPreview.setHintTextColor(Color.rgb(101,111,126));
        clipboardPreview.setHint("Clipboard text");
        clipboardPreview.setTextSize(8.8f);
        clipboardPreview.setGravity(Gravity.TOP|Gravity.START);
        clipboardPreview.setPadding(dp(10),dp(8),dp(10),dp(8));
        clipboardPreview.setBackground(gradient(
                Color.rgb(22,27,34),
                Color.rgb(17,21,27),
                10,
                Color.rgb(38,45,55)));
        LinearLayout.LayoutParams previewLp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,0,1f);
        previewLp.bottomMargin=dp(7);
        root.addView(clipboardPreview,previewLp);

        LinearLayout actions=row();
        Button phoneToPc=button("Send to PC",8.3f);
        Button pcToPhone=button("Get from PC",8.3f);
        Button pastePc=button("Send + Paste",8.3f);
        Button clear=button("Clear",8.3f);
        phoneToPc.setBackground(stateBg(ACCENT_SOFT,Color.rgb(43,79,126),9,0));
        pastePc.setBackground(stateBg(Color.rgb(28,62,55),Color.rgb(35,82,70),9,0));
        for(Button b:new Button[]{phoneToPc,pcToPhone,pastePc,clear}){
            b.setElevation(0);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(33),1f);
            lp.leftMargin=dp(2);lp.rightMargin=dp(2);
            actions.addView(b,lp);
        }
        root.addView(actions,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(33)));

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
        LinearLayout root=card(14);

        LinearLayout header=row();
        TextView title=textView("Shortcuts",10.5f,TEXT,true);
        TextView profile=textView(currentProfile.toUpperCase(),6.9f,Color.rgb(147,190,246),true);
        profile.setLetterSpacing(0.08f);
        profile.setGravity(Gravity.CENTER);
        profile.setBackground(bg(Color.rgb(28,48,75),12,0));
        header.addView(title,new LinearLayout.LayoutParams(0,dp(28),1f));
        header.addView(profile,new LinearLayout.LayoutParams(dp(82),dp(24)));
        root.addView(header,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(28)));

        shortcutGrid=new LinearLayout(this);
        shortcutGrid.setOrientation(LinearLayout.VERTICAL);
        root.addView(shortcutGrid,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,0,2f));

        LinearLayout macroHeader=row();
        TextView macroTitle=textView("Macros",9f,Color.rgb(188,199,212),true);
        TextView hint=textView("tap run · hold edit",6.9f,MUTED,false);
        hint.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        macroHeader.addView(macroTitle,new LinearLayout.LayoutParams(0,dp(22),1f));
        macroHeader.addView(hint,new LinearLayout.LayoutParams(dp(105),dp(22)));
        LinearLayout.LayoutParams mhLp=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,dp(22));
        mhLp.topMargin=dp(5);
        root.addView(macroHeader,mhLp);

        macroGrid=new LinearLayout(this);
        macroGrid.setOrientation(LinearLayout.VERTICAL);
        root.addView(macroGrid,new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,0,0.9f));

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
                    Shortcut s=list.get(i+j);Button b=button(s.label,8.2f);
                    b.setElevation(0);
                    b.setBackground(stateBg(
                            Color.rgb(28,33,41),
                            Color.rgb(43,51,62),
                            9,0));
                    b.setTextColor(Color.rgb(205,214,225));
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
                    Button b=button(macro.label,8.2f);
                    b.setElevation(0);
                    b.setTextColor(Color.rgb(205,219,238));
                    b.setBackground(stateBg(
                            Color.rgb(24,42,58),
                            Color.rgb(33,57,77),
                            9,0));
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
        if(statusText!=null)statusText.setText(detail);
        int textColor=Color.rgb(171,181,195);
        int dotColor=Color.rgb(94,105,120);

        if("connected".equals(kind)){
            textColor=Color.rgb(186,236,216);
            dotColor=SUCCESS;
            connectButton.setText("Reconnect");
            handler.postDelayed(this::reloadKnownPeersIntoSpinner,120);
        }else if("error".equals(kind)){
            textColor=Color.rgb(255,175,184);
            dotColor=Color.rgb(235,91,110);
        }else if("connecting".equals(kind)||"authenticating".equals(kind)
                ||"reconnecting".equals(kind)||"discovering".equals(kind)){
            textColor=Color.rgb(255,217,151);
            dotColor=Color.rgb(240,174,72);
        }

        if(statusText!=null)statusText.setTextColor(textColor);
        if(statusDot!=null)statusDot.setBackground(bg(dotColor,20,0));
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

package com.wirelesskey.remote;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class MacroStore {
    public static final class Macro {
        public String label;
        public String action;

        public Macro(String label, String action) {
            this.label = label;
            this.action = action;
        }
    }

    private final SharedPreferences prefs;

    public MacroStore(Context context) {
        prefs = context.getSharedPreferences("wirelesskey_macros", Context.MODE_PRIVATE);
    }

    public List<Macro> load() {
        List<Macro> out = new ArrayList<>();
        String raw = prefs.getString("macros", "");
        if (raw == null || raw.isEmpty()) {
            out.add(new Macro("Macro 1", "keys:CTRL+C"));
            out.add(new Macro("Macro 2", "keys:CTRL+V"));
            out.add(new Macro("Macro 3", "keys:ALT+TAB"));
            out.add(new Macro("Macro 4", "text:WirelessKey"));
            return out;
        }

        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length() && i < 8; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new Macro(
                        o.optString("label", "Macro " + (i + 1)),
                        o.optString("action", "")
                ));
            }
        } catch (Exception ignored) {
        }

        while (out.size() < 4) {
            out.add(new Macro("Macro " + (out.size() + 1), ""));
        }
        return out;
    }

    public void save(List<Macro> macros) {
        JSONArray arr = new JSONArray();
        try {
            for (int i = 0; i < macros.size() && i < 8; i++) {
                Macro m = macros.get(i);
                JSONObject o = new JSONObject();
                o.put("label", m.label == null ? "" : m.label.trim());
                o.put("action", m.action == null ? "" : m.action.trim());
                arr.put(o);
            }
            prefs.edit().putString("macros", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}

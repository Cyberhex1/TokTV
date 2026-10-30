package com.toktv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** D-pad friendly settings: OK or Left/Right changes a value. */
public class SettingsActivity extends Activity {
    private Prefs prefs;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#101010"));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(80), dp(40), dp(80), dp(40));
        sv.addView(list);
        setContentView(sv);

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextColor(Color.WHITE);
        title.setTextSize(32);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(16), 0, 0, dp(24));
        list.addView(title);

        option("LIVE player", "Website player has chat, gifts and comments. Native player is smoother on older Fire TV sticks.",
                "live_player", Prefs.PLAYER_WEB, Prefs.PLAYER_OPTIONS);
        option("Native player quality", "Menu (≡) in the native player also changes this.",
                "quality", "auto", Prefs.QUALITY_OPTIONS);
        option("Chat in native player", "Press ▶ while watching to show chat beside the video.",
                "native_chat", "1", Prefs.ONOFF);
        option("Text and layout size", "How big tiktok.com looks on the TV.",
                "ui_scale", "150", Prefs.SIZE_OPTIONS);
        option("Turn sound on automatically", "TikTok starts some videos muted. This unmutes them.",
                "auto_unmute", "1", Prefs.ONOFF);
        option("Hide \"Get the app\" banners", "", "hide_promos", "1", Prefs.ONOFF);

        action("Log out and clear data", "Removes your TikTok login and cookies from this app.", new Runnable() {
            @Override
            public void run() { confirmLogout(); }
        });

        TextView about = new TextView(this);
        about.setText("TokTV 1.0 · sideload build");
        about.setTextColor(Color.GRAY);
        about.setTextSize(14);
        about.setPadding(dp(16), dp(24), 0, 0);
        list.addView(about);

        if (list.getChildCount() > 1) list.getChildAt(1).requestFocus();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private LinearLayout row(String name, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setBackgroundResource(R.drawable.menu_item_bg);
        row.setPadding(dp(20), dp(14), dp(20), dp(14));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(this);
        n.setText(name);
        n.setTextColor(Color.WHITE);
        n.setTextSize(20);
        texts.addView(n);
        if (desc.length() > 0) {
            TextView d = new TextView(this);
            d.setText(desc);
            d.setTextColor(Color.parseColor("#BBBBBB"));
            d.setTextSize(14);
            texts.addView(d);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        list.addView(row, lp);
        return row;
    }

    private void option(String name, String desc, final String key, final String def, final String[][] opts) {
        final LinearLayout row = row(name, desc);
        final TextView value = new TextView(this);
        value.setTextColor(Color.parseColor("#25F4EE"));
        value.setTextSize(18);
        value.setPadding(dp(24), 0, 0, 0);
        value.setText("◀  " + Prefs.labelFor(opts, prefs.get(key, def)) + "  ▶");
        row.addView(value);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { cycle(key, def, opts, value, 1); }
        });
        row.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int code, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { cycle(key, def, opts, value, 1); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) { cycle(key, def, opts, value, -1); return true; }
                return false;
            }
        });
    }

    private void cycle(String key, String def, String[][] opts, TextView value, int dir) {
        String cur = prefs.get(key, def);
        int i = 0;
        for (int k = 0; k < opts.length; k++) if (opts[k][0].equals(cur)) i = k;
        String[] next = opts[(i + dir + opts.length) % opts.length];
        prefs.put(key, next[0]);
        value.setText("◀  " + next[1] + "  ▶");
    }

    private void action(String name, String desc, final Runnable r) {
        LinearLayout row = row(name, desc);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { r.run(); }
        });
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Log out?")
                .setMessage("This removes your TikTok login from TokTV.")
                .setPositiveButton("Log out", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        CookieManager.getInstance().removeAllCookies(null);
                        CookieManager.getInstance().flush();
                        WebStorage.getInstance().deleteAllData();
                        prefs.put("needs_reload", "1");
                        Toast.makeText(SettingsActivity.this, "Logged out", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_OK);
        super.onBackPressed();
    }
}

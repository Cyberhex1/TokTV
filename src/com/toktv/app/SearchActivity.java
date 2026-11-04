package com.toktv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Native search. Typing filters the LIVE lists already loaded (instant); OK / Search asks
 * TikTok for LIVEs and accounts (through SearchLoader) and shows them as cards here.
 * Offline accounts can be pinned so they jump to the top when they go LIVE.
 */
public class SearchActivity extends Activity {
    private Prefs prefs;
    private Picks picks;
    private FrameLayout root;
    private EditText input;
    private LinearLayout list;
    private ProgressBar spinner;
    private TextView status;
    private Pointer pointer;
    private SearchLoader loader;
    private List<Feed.Room> liveResults, accountResults;
    private String searched;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        picks = new Picks(prefs);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B0B0B"));
        setContentView(root);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(48), dp(24), dp(48), dp(12));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("🔍 Search", 26, Color.WHITE, true);
        bar.addView(title);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Name, @username or topic");
        input.setHintTextColor(Color.parseColor("#777777"));
        input.setTextColor(Color.WHITE);
        input.setTextSize(22);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable ib = new GradientDrawable();
        ib.setColor(Color.parseColor("#1F1F1F"));
        ib.setCornerRadius(dp(12));
        ib.setStroke(dp(2), Color.parseColor("#FE2C55"));
        input.setBackground(ib);
        input.setPadding(dp(18), dp(10), dp(18), dp(10));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, -2, 1f);
        ilp.leftMargin = dp(20);
        bar.addView(input, ilp);
        spinner = new ProgressBar(this);
        spinner.setVisibility(View.GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(30), dp(30));
        slp.leftMargin = dp(14);
        bar.addView(spinner, slp);
        page.addView(bar);

        status = text("Type and press OK to search TikTok. Tip: the Fire TV app on your phone has a keyboard.", 15,
                Color.parseColor("#999999"), false);
        status.setPadding(dp(4), dp(10), 0, dp(6));
        page.addView(status);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        pointer = new Pointer(this, root);
        root.addView(pointer, new FrameLayout.LayoutParams(-1, -1));

        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { render(); }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                search();
                return true;
            }
        });
        String q = getIntent().getStringExtra("q");
        if (q != null) {
            input.setText(q);
            search();
        }
        input.requestFocus();
        input.postDelayed(new Runnable() {
            @Override
            public void run() {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 300);
        render();
    }

    // ---------------------------------------------------------------- Searching

    private void search() {
        final String q = input.getText().toString().trim().replaceFirst("^@", "");
        if (q.isEmpty()) return;
        hideKeyboard();
        if (loader != null) loader.cancel();
        searched = q;
        liveResults = null;
        accountResults = null;
        spinner.setVisibility(View.VISIBLE);
        status.setText("Searching TikTok for \"" + q + "\"…");
        render();
        loader = new SearchLoader(this, root, new SearchLoader.Callback() {
            @Override
            public void onLive(List<Feed.Room> rooms) {
                liveResults = rooms;
                Feed.remember(rooms);
                render();
            }

            @Override
            public void onAccounts(List<Feed.Room> accounts) {
                accountResults = accounts;
                render();
            }

            @Override
            public void onDone() {
                loader = null;
                spinner.setVisibility(View.GONE);
                boolean none = (liveResults == null || liveResults.isEmpty()) && (accountResults == null || accountResults.isEmpty());
                status.setText(none ? "Nothing found for \"" + q + "\" (or TikTok didn't answer). Try another word."
                        : "Results for \"" + q + "\"");
                render();
            }
        });
        loader.start(q);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    // ---------------------------------------------------------------- Results

    private List<String> order = new ArrayList<>();

    private void render() {
        View f = getCurrentFocus();
        String focusTag = f != null && f.getTag() instanceof String ? (String) f.getTag() : null;
        list.removeAllViews();
        order.clear();
        String q = input.getText().toString().trim().replaceFirst("^@", "").toLowerCase();

        // Instant: matches in the lists already loaded.
        if (q.length() > 0) {
            List<Feed.Room> local = new ArrayList<>();
            List<Feed.Room> all = new ArrayList<>(Feed.following);
            all.addAll(Feed.suggested);
            for (Feed.Room r : all) {
                if (r.user.toLowerCase().contains(q) || r.nick.toLowerCase().contains(q)
                        || (r.title != null && r.title.toLowerCase().contains(q))) local.add(r);
            }
            if (!local.isEmpty()) {
                section("In your LIVE list");
                grid(local);
            }
        }
        if (liveResults != null) {
            section("LIVE now on TikTok" + (liveResults.isEmpty() ? " — none" : ""));
            grid(liveResults);
        }
        if (accountResults != null) {
            section("Accounts" + (accountResults.isEmpty() ? " — none" : ""));
            grid(accountResults);
        }
        if (focusTag != null) {
            View v = list.findViewWithTag(focusTag);
            if (v != null) v.requestFocus();
        }
    }

    private void section(String t) {
        TextView v = text(t, 21, Color.WHITE, true);
        v.setPadding(dp(4), dp(16), 0, dp(8));
        list.addView(v);
    }

    private void grid(List<Feed.Room> rooms) {
        LinearLayout row = null;
        for (int i = 0; i < rooms.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                list.addView(row);
            }
            View c = card(rooms.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(108), 1f);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            row.addView(c, lp);
            if (rooms.get(i).live && !order.contains(rooms.get(i).user)) order.add(rooms.get(i).user);
        }
        for (int k = rooms.size() % 3; row != null && k > 0 && k < 3; k++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(108), 1f);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            row.addView(new View(this), lp);
        }
    }

    private View card(final Feed.Room r) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackgroundResource(R.drawable.live_card_bg);
        c.setPadding(dp(12), dp(8), dp(12), dp(8));
        c.setFocusable(true);
        c.setTag(r.user + (r.live ? "" : "#off"));
        if (!r.live) c.setAlpha(0.6f);

        ImageView av = new ImageView(this);
        av.setScaleType(ImageView.ScaleType.CENTER_CROP);
        av.setBackgroundColor(Color.parseColor("#333333"));
        c.addView(av, new LinearLayout.LayoutParams(dp(72), dp(72)));
        ImageLoader.load(av, r.avatar, dp(72));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, 0, 0);
        TextView name = text((picks.isPinned(r.user) ? "★ " : "") + r.nick, 18, Color.WHITE, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        String meta = "@" + r.user + (r.live ? (r.viewers > 0 ? "  ·  " + count(r.viewers) + " watching" : "  ·  LIVE")
                : (r.followers > 0 ? "  ·  " + count(r.followers) + " followers" : "  ·  not LIVE"));
        TextView m = text(meta, 14, r.live ? Color.parseColor("#25F4EE") : Color.parseColor("#AAAAAA"), false);
        m.setSingleLine(true);
        m.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(m);
        if (r.title != null && r.title.length() > 0) {
            TextView t = text(r.title, 14, Color.parseColor("#CCCCCC"), false);
            t.setMaxLines(2);
            t.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(t);
        }
        c.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (r.live) watch(r.user);
                else options(r);
            }
        });
        c.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                options(r);
                return true;
            }
        });
        return c;
    }

    private void options(final Feed.Room r) {
        final boolean pinned = picks.isPinned(r.user);
        String[] items = r.live
                ? new String[]{"Watch", pinned ? "Unpin" : "Pin to top of the LIVE list"}
                : new String[]{pinned ? "Unpin" : "Pin (shows at the top when they go LIVE)", "Try to watch anyway"};
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("@" + r.user + (r.live ? " · LIVE" : " · not LIVE right now"))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if ((r.live && which == 0) || (!r.live && which == 1)) watch(r.user);
                        else {
                            boolean now = picks.togglePin(r.user);
                            Toast.makeText(SearchActivity.this, now ? "Pinned @" + r.user : "Unpinned @" + r.user,
                                    Toast.LENGTH_SHORT).show();
                            render();
                        }
                    }
                })
                .show();
    }

    private void watch(String user) {
        if (loader != null) { loader.cancel(); loader = null; spinner.setVisibility(View.GONE); }
        Intent i = new Intent(this, LiveActivity.class);
        i.putExtra("user", user);
        JSONArray a = new JSONArray();
        for (String u : order) a.put(u);
        i.putExtra("lives", a.toString());
        startActivity(i);
    }

    // ---------------------------------------------------------------- Keys / lifecycle

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                pointer.toggle();
                prefs.put("mouse", pointer.isOn() ? "1" : "0");
            }
            return true;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_SEARCH || e.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                input.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
            return true;
        }
        if (pointer.handleKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onResume() {
        super.onResume();
        pointer.post(new Runnable() {
            @Override
            public void run() { pointer.setOn("1".equals(prefs.get("mouse", "0"))); }
        });
    }

    @Override
    protected void onDestroy() {
        if (loader != null) loader.cancel();
        super.onDestroy();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static String count(long n) {
        if (n >= 1000000) return String.format("%.1fM", n / 1e6);
        if (n >= 1000) return String.format("%.1fK", n / 1e3);
        return String.valueOf(n);
    }
}

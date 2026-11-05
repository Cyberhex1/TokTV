package com.toktv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Start screen: a native list of LIVEs (people you follow first, then suggestions).
 * No TikTok website is shown here, which keeps it fast on older Fire TV sticks.
 */
public class HomeActivity extends Activity {
    private static final long STALE_MS = 90 * 1000;
    private static final long AUTO_REFRESH_MS = 3 * 60 * 1000;
    private static final int REQ_LOGIN = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout list;
    private TextView status;
    private ProgressBar spinner;
    private Pointer pointer;
    private Picks picks;
    private org.json.JSONArray order;
    private FeedLoader loader;
    private String focusUser;
    private boolean resumed;
    private long lastBackAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        picks = new Picks(prefs);
        if (!"1".equals(prefs.get("defaults_v6", ""))) {
            // New defaults: no "joined" lines, no profanity masking.
            prefs.put("chat_joins", "0");
            prefs.put("chat_filter", "0");
            prefs.put("defaults_v6", "1");
        }
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B0B0B"));
        setContentView(root);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.parseColor("#0B0B0B"));   // hides the list loader's page behind it
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(48), dp(26), dp(48), dp(10));
        TextView logo = text("TokTV", 30, Color.WHITE, true);
        header.addView(logo);
        TextView badge = text("LIVE", 16, Color.WHITE, true);
        badge.setBackgroundResource(R.drawable.live_badge);
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.leftMargin = dp(10);
        header.addView(badge, blp);
        status = text("", 16, Color.parseColor("#AAAAAA"), false);
        status.setGravity(Gravity.END);
        header.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
        spinner = new ProgressBar(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(28), dp(28));
        slp.leftMargin = dp(14);
        header.addView(spinner, slp);
        page.addView(header);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(40), 0, dp(40), dp(40));
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView hint = text("OK  Watch      Hold OK or ≡  Pin / Sort / Menu      ⏯  Refresh      Back  Exit", 15, Color.parseColor("#888888"), false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(6), 0, dp(14));
        page.addView(hint);

        pointer = new Pointer(this, root);
        root.addView(pointer, new FrameLayout.LayoutParams(-1, -1));

        // TokTV Remote keeps running once it has been set up (only while TokTV is open).
        if ("1".equals(prefs.get("remote_on", "0"))) RemoteServer.get(this).start();

        render();
        if (!FeedLoader.loggedIn()) {
            spinner.setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------------- Loading

    private void refresh() {
        if (loader != null) return;
        spinner.setVisibility(View.VISIBLE);
        status.setText(Feed.updatedAt == 0 ? "Finding LIVEs…" : "Refreshing…");
        loader = new FeedLoader(this, root, new FeedLoader.Callback() {
            @Override
            public void onFeed(List<Feed.Room> following, List<Feed.Room> suggested, List<Feed.Room> gaming, boolean loggedIn) {
                loader = null;
                spinner.setVisibility(View.GONE);
                if (following == null && suggested == null) {
                    status.setText("Couldn't reach TikTok. ≡ → Refresh to try again.");
                    if (Feed.updatedAt == 0) render();
                    return;
                }
                // Which pinned creators were offline before this refresh?
                boolean first = Feed.updatedAt == 0;
                List<String> wasOff = new ArrayList<>();
                for (String u : picks.pinned()) if (Feed.findListed(u) == null) wasOff.add(u);
                Feed.set(following, suggested, gaming);
                List<String> liveNow = new ArrayList<>();
                for (Feed.Room r : Feed.following) liveNow.add(r.user);
                for (String u : picks.pinned()) if (Feed.findListed(u) != null) liveNow.add(u);
                picks.markSeenLive(liveNow);
                if (!first) {
                    List<String> wentLive = new ArrayList<>();
                    for (String u : wasOff) if (Feed.findListed(u) != null) wentLive.add(u);
                    if (!wentLive.isEmpty() && !"0".equals(prefs.get("golive_alerts", "1"))) banner(wentLive);
                }
                render();
            }
        });
        loader.start();
    }

    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            if (resumed) refresh();
            ui.postDelayed(this, AUTO_REFRESH_MS);
        }
    };

    // ---------------------------------------------------------------- Rendering

    private void render() {
        View f = getCurrentFocus();
        if (f != null && f.getTag() instanceof String) focusUser = (String) f.getTag();
        list.removeAllViews();
        View firstCard = null;

        boolean loggedIn = FeedLoader.loggedIn();
        if (Feed.updatedAt > 0) {
            int mins = (int) ((System.currentTimeMillis() - Feed.updatedAt) / 60000);
            status.setText(Feed.following.size() + " following LIVE · updated " + (mins == 0 ? "just now" : mins + " min ago"));
        } else if (loggedIn) {
            status.setText("Finding LIVEs…");
        } else {
            status.setText("");
        }

        View sb = button("🔍  Search LIVEs and accounts", new Runnable() {
            public void run() { openSearch(); }
        });
        sb.setTag("#search");

        if (!loggedIn) {
            section("Log in to see who you follow that's LIVE");
            View b = button("Log in with QR code", new Runnable() {
                public void run() { openLogin(); }
            });
            firstCard = b;
        }

        // Pinned first (live ones, then offline ones dimmed), then Following and Suggested, sorted.
        order = new org.json.JSONArray();
        List<String> pins = picks.pinned();
        List<Feed.Room> pinLive = new ArrayList<>(), pinOff = new ArrayList<>();
        for (String u : pins) {
            Feed.Room r = Feed.findListed(u);
            if (r != null) pinLive.add(r);
            else {
                Feed.Room off = new Feed.Room();
                off.user = u;
                off.nick = u;
                off.live = false;
                long seen = picks.lastSeenLive(u);
                off.title = seen > 0 ? "Not LIVE · last seen LIVE " + Picks.ago(seen) : "Not LIVE right now";
                pinOff.add(off);
            }
        }
        pinLive = picks.sorted(pinLive);
        String sortLabel = Prefs.labelFor(Picks.SORT_OPTIONS, picks.sort());
        if (!pins.isEmpty()) {
            section("★ Pinned");
            List<Feed.Room> all = new ArrayList<>(pinLive);
            all.addAll(pinOff);
            View v = grid(all, pinLive.size());
            if (firstCard == null && !pinLive.isEmpty()) firstCard = v;
        }
        if (loggedIn) {
            List<Feed.Room> follow = picks.sorted(without(Feed.following, pins));
            section("Following · LIVE now" + (Feed.updatedAt > 0 ? "  (" + follow.size() + ")" : "") + "   ·  " + sortLabel);
            if (Feed.updatedAt > 0 && follow.isEmpty()) {
                note(Feed.following.isEmpty() ? "Nobody you follow is LIVE right now." : "Everyone LIVE is in Pinned.");
            }
            View v = grid(follow, follow.size());
            if (firstCard == null && v != null) firstCard = v;
        }

        List<Feed.Room> sugg = picks.sorted(without(Feed.suggested, pins));
        if (!sugg.isEmpty()) {
            section("Recommended LIVE");
            View v = grid(sugg, sugg.size());
            if (firstCard == null) firstCard = v;
        }
        List<Feed.Room> games = picks.sorted(without(Feed.gaming, pins));
        if (!games.isEmpty()) {
            section("🎮 Gaming LIVE");
            View v = grid(games, games.size());
            if (firstCard == null) firstCard = v;
        }

        View restore = focusUser == null ? null : list.findViewWithTag(focusUser);
        if (restore != null) restore.requestFocus();
        else if (firstCard != null) firstCard.requestFocus();
    }

    private TextView bannerView;

    /** "★ @name just went LIVE" across the top for a few seconds. */
    private void banner(List<String> users) {
        if (bannerView == null) {
            bannerView = text("", 22, Color.WHITE, true);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(Color.parseColor("#F0FE2C55"));
            bg.setCornerRadius(dp(16));
            bannerView.setBackground(bg);
            bannerView.setPadding(dp(28), dp(14), dp(28), dp(14));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = dp(20);
            root.addView(bannerView, lp);
        }
        StringBuilder sb = new StringBuilder("★ ");
        for (int i = 0; i < users.size() && i < 3; i++) sb.append(i == 0 ? "@" : ", @").append(users.get(i));
        if (users.size() > 3) sb.append(" and ").append(users.size() - 3).append(" more");
        sb.append(users.size() == 1 ? " just went LIVE" : " just went LIVE").append("  —  they're at the top");
        bannerView.setText(sb.toString());
        bannerView.setVisibility(View.VISIBLE);
        bannerView.bringToFront();
        pointer.bringToFront();
        ui.removeCallbacks(hideBanner);
        ui.postDelayed(hideBanner, 10000);
    }

    private final Runnable hideBanner = new Runnable() {
        @Override
        public void run() { if (bannerView != null) bannerView.setVisibility(View.GONE); }
    };

    private void section(String title) {
        TextView t = text(title, 22, Color.WHITE, true);
        t.setPadding(dp(8), dp(18), 0, dp(10));
        list.addView(t);
    }

    private void note(String s) {
        TextView t = text(s, 17, Color.parseColor("#AAAAAA"), false);
        t.setPadding(dp(8), 0, 0, dp(8));
        list.addView(t);
    }

    private View button(String label, final Runnable r) {
        TextView b = text(label, 20, Color.WHITE, true);
        b.setBackgroundResource(R.drawable.live_card_bg);
        b.setPadding(dp(28), dp(16), dp(28), dp(16));
        b.setFocusable(true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { r.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(8);
        lp.topMargin = dp(6);
        list.addView(b, lp);
        return b;
    }

    private static List<Feed.Room> without(List<Feed.Room> rooms, List<String> users) {
        List<Feed.Room> out = new ArrayList<>();
        outer:
        for (Feed.Room r : rooms) {
            for (String u : users) if (u.equalsIgnoreCase(r.user)) continue outer;
            out.add(r);
        }
        return out;
    }

    /** Three cards per row; the first liveCount rooms are LIVE, the rest are dimmed. Returns the first card. */
    private View grid(List<Feed.Room> rooms, int liveCount) {
        View first = null;
        LinearLayout row = null;
        for (int i = 0; i < rooms.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                list.addView(row);
            }
            View c = card(rooms.get(i));
            if (i >= liveCount) c.setAlpha(0.45f);
            else order.put(rooms.get(i).user);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(118), 1f);
            lp.setMargins(dp(8), dp(8), dp(8), dp(8));
            row.addView(c, lp);
            if (first == null) first = c;
        }
        if (row != null) {
            for (int k = rooms.size() % 3; k > 0 && k < 3; k++) {
                View spacer = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(118), 1f);
                lp.setMargins(dp(8), dp(8), dp(8), dp(8));
                row.addView(spacer, lp);
            }
        }
        return first;
    }

    private View card(final Feed.Room r) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackgroundResource(R.drawable.live_card_bg);
        c.setPadding(dp(14), dp(10), dp(14), dp(10));
        c.setFocusable(true);
        c.setTag(r.user);

        ImageView av = new ImageView(this);
        av.setScaleType(ImageView.ScaleType.CENTER_CROP);
        av.setBackgroundColor(Color.parseColor("#333333"));
        c.addView(av, new LinearLayout.LayoutParams(dp(78), dp(78)));
        // The stream's cover picture when there is one, else the avatar.
        ImageLoader.load(av, r.cover != null ? r.cover : r.avatar, dp(78));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, 0, 0);
        TextView name = text((picks.isPinned(r.user) ? "★ " : "") + r.nick, 19, Color.WHITE, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        TextView meta = text("@" + r.user + (r.viewers > 0 ? "  ·  " + count(r.viewers) + " watching" : ""), 14,
                Color.parseColor("#25F4EE"), false);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(meta);
        if (r.title != null && r.title.length() > 0) {
            TextView title = text(r.title, 14, Color.parseColor("#CCCCCC"), false);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(0, dp(3), 0, 0);
            texts.addView(title);
        }
        c.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { watch(r.user); }
        });
        c.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                cardOptions(r.user);
                return true;
            }
        });
        return c;
    }

    private static String count(long n) {
        if (n >= 1000000) return String.format("%.1fM", n / 1e6);
        if (n >= 1000) return String.format("%.1fK", n / 1e3);
        return String.valueOf(n);
    }

    // ---------------------------------------------------------------- Actions

    private void watch(String user) {
        if (loader != null) { loader.cancel(); loader = null; spinner.setVisibility(View.GONE); }
        focusUser = user;
        Intent i = new Intent(this, LiveActivity.class);
        i.putExtra("user", user);
        i.putExtra("lives", (order != null && order.length() > 0 ? order : Feed.allUsers()).toString());
        startActivity(i);
    }

    private String focusedUser() {
        View f = getCurrentFocus();
        if (f == null || !(f.getTag() instanceof String)) return null;
        String t = (String) f.getTag();
        return t.startsWith("#") ? null : t;   // "#search" etc. are UI tags, not users
    }

    private void togglePin(String user) {
        boolean now = picks.togglePin(user);
        focusUser = user;
        toast(now ? "Pinned @" + user : "Unpinned @" + user);
        render();
    }

    private void cardOptions(final String user) {
        final boolean pinned = picks.isPinned(user);
        String[] items = {"Watch", pinned ? "Unpin" : "Pin to top", "Sort list…"};
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("@" + user)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) watch(user);
                        else if (which == 1) togglePin(user);
                        else chooseSort();
                    }
                })
                .show();
    }

    private void chooseSort() {
        String[] labels = new String[Picks.SORT_OPTIONS.length];
        int cur = 0;
        for (int k = 0; k < labels.length; k++) {
            labels[k] = Picks.SORT_OPTIONS[k][1];
            if (Picks.SORT_OPTIONS[k][0].equals(picks.sort())) cur = k;
        }
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Sort LIVEs by")
                .setSingleChoiceItems(labels, cur, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        picks.setSort(Picks.SORT_OPTIONS[which][0]);
                        d.dismiss();
                        focusUser = null;
                        render();
                    }
                })
                .show();
    }

    private void openSearch() {
        if (loader != null) { loader.cancel(); loader = null; spinner.setVisibility(View.GONE); }
        startActivity(new Intent(this, SearchActivity.class));
    }

    private void openLogin() {
        startActivityForResult(new Intent(this, LoginActivity.class), REQ_LOGIN);
    }

    private void openWebsite(String url) {
        Intent i = new Intent(this, MainActivity.class);
        i.setData(Uri.parse(url));
        startActivity(i);
    }

    private void showMenu() {
        final boolean loggedIn = FeedLoader.loggedIn();
        final String fu = focusedUser();
        if (fu != null) {
            // Card focused: its own options first.
            final String[] items = {
                    picks.isPinned(fu) ? "Unpin @" + fu : "Pin @" + fu + " to top",
                    "Sort: " + Prefs.labelFor(Picks.SORT_OPTIONS, picks.sort()),
                    "More…",
            };
            new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle("TokTV")
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            if (which == 0) togglePin(fu);
                            else if (which == 1) chooseSort();
                            else showMainMenu(loggedIn);
                        }
                    })
                    .show();
            return;
        }
        showMainMenu(loggedIn);
    }

    private void showMainMenu(boolean loggedIn) {
        final String[] items = {
                "Refresh",
                "📱 Phone remote (TokTV Remote)",
                "Free mouse: " + (pointer.isOn() ? "On" : "Off"),
                "Search",
                "LIVE page (website)",
                "TikTok videos (website)",
                loggedIn ? "Log in again (QR code)" : "Log in (QR code)",
                "Settings",
                "Exit",
        };
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("TokTV")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0: refresh(); break;
                            case 1: showRemote(); break;
                            case 2: setMouse(!pointer.isOn()); break;
                            case 3: openSearch(); break;
                            case 4: openWebsite("https://www.tiktok.com/live"); break;
                            case 5: openWebsite(Prefs.HOME_URL); break;
                            case 6: openLogin(); break;
                            case 7: startActivity(new Intent(HomeActivity.this, SettingsActivity.class)); break;
                            case 8: finish(); break;
                        }
                    }
                })
                .show();
    }

    private void setMouse(boolean on) {
        prefs.put("mouse", on ? "1" : "0");
        pointer.setOn(on);
        toast(on ? "Free mouse on: arrows move, OK clicks. ≡ to turn off." : "Free mouse off");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_LOGIN) {
            Feed.updatedAt = 0;
            render();
            if (FeedLoader.loggedIn()) refresh();
        }
    }

    // ---------------------------------------------------------------- Keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_MENU) {
            if (!down) showMenu();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down) {
                long now = System.currentTimeMillis();
                if (now - lastBackAt < 2500) finish();
                else {
                    lastBackAt = now;
                    toast("Press Back again to exit");
                }
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_SEARCH) {
            if (!down) openSearch();
            return true;
        }
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && down && e.getRepeatCount() == 0) {
            refresh();
            return true;
        }
        if (pointer.handleKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- Lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        Tv.top = this;
        resumed = true;
        pointer.post(new Runnable() {
            @Override
            public void run() { pointer.setOn("1".equals(prefs.get("mouse", "0"))); }
        });
        if (FeedLoader.loggedIn() || Feed.updatedAt == 0) {
            if (System.currentTimeMillis() - Feed.updatedAt > STALE_MS) refresh();
            else render();
        }
        ui.removeCallbacks(autoRefresh);
        ui.postDelayed(autoRefresh, AUTO_REFRESH_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(autoRefresh);
    }

    @Override
    protected void onDestroy() {
        if (loader != null) loader.cancel();
        ui.removeCallbacksAndMessages(null);
        if (isFinishing()) RemoteServer.get(this).stop();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- TokTV Remote (phone)

    /** Starts the phone remote and shows the QR code to pair a phone. */
    private void showRemote() {
        final RemoteServer srv = RemoteServer.get(this);
        prefs.put("remote_on", "1");
        srv.start();
        final String url = srv.url();
        if (url == null) {
            toast("The Fire TV isn't on a network right now.");
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(12), dp(24), dp(8));
        final ImageView qr = new ImageView(this);
        qr.setImageBitmap(qrBitmap(url, dp(220)));
        box.addView(qr, new LinearLayout.LayoutParams(dp(220), dp(220)));
        TextView how = text("Scan with your phone's camera (same Wi-Fi as the TV).\nThen \"Add to Home Screen\" to open it like an app.", 15,
                Color.parseColor("#DDDDDD"), false);
        how.setGravity(Gravity.CENTER);
        how.setPadding(0, dp(12), 0, dp(4));
        box.addView(how);
        final TextView link = text("Or open " + srv.baseUrl() + " and enter", 14, Color.parseColor("#AAAAAA"), false);
        link.setGravity(Gravity.CENTER);
        box.addView(link);
        String c = srv.pairCode();
        final TextView codeView = text(c.substring(0, 3) + " " + c.substring(3), 34, Color.WHITE, true);
        codeView.setGravity(Gravity.CENTER);
        codeView.setLetterSpacing(0.15f);
        box.addView(codeView);
        TextView once = text("One-time code: works for one phone, for 10 minutes.", 13, Color.parseColor("#888888"), false);
        once.setGravity(Gravity.CENTER);
        box.addView(once);
        final AlertDialog dlg = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("📱 TokTV Remote")
                .setView(box)
                .setPositiveButton("Done", null)
                .setNeutralButton("Unpair all phones", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        srv.newKey();
                        toast("Phones paired before are disconnected.");
                        showRemote();
                    }
                })
                .setNegativeButton("Turn off", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        prefs.put("remote_on", "0");
                        srv.stop();
                        toast("TokTV Remote is off");
                    }
                })
                .show();
        srv.onPaired = new Runnable() {
            @Override
            public void run() {
                srv.onPaired = null;
                if (dlg.isShowing()) dlg.dismiss();
                Toast.makeText(getApplicationContext(), "📱 Phone paired with TokTV Remote", Toast.LENGTH_LONG).show();
            }
        };
    }

    private static android.graphics.Bitmap qrBitmap(String text, int size) {
        try {
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size);
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565);
            int[] row = new int[size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) row[x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
                b.setPixels(row, 0, size, 0, y, size, 1);
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- Helpers

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

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

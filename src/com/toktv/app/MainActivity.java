package com.toktv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The main screen: tiktok.com in a WebView (desktop mode, so Lives work), with a TV
 * control layer injected (assets/tv.js) and a native side menu.
 */
public class MainActivity extends Activity {
    static final Pattern LIVE_ROOM = Pattern.compile("^https?://(?:www\\.)?tiktok\\.com/@([^/?#]+)/live", Pattern.CASE_INSENSITIVE);
    private static final int REQ_LIVE = 1;
    private static final int REQ_SETTINGS = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private FrameLayout root;
    private WebView web;
    private ScrollView menu;
    private LinearLayout menuList;
    private TextView hint;
    private String tvScript = "";
    private long lastBackAt;
    private int appliedScale;
    /** A live room the user chose to watch in the website player (after a native fallback). */
    private String webOnlyUser;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private View fullscreenView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = new Prefs(this);
        tvScript = readAsset("tv.js");

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        web = new WebView(this);
        setupWebView(web);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        buildMenu();
        buildHint();

        Uri data = getIntent() != null ? getIntent().getData() : null;
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(data != null ? data.toString() : Prefs.HOME_URL);
        }
        showHint("◀ or ≡  Menu      ▲ ▼  Next video      OK  Pause (twice = Like)      ▶  Buttons", 7000);
    }

    // ---------------------------------------------------------------- WebView

    private void setupWebView(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(Prefs.DESKTOP_UA);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        appliedScale = prefs.uiScale();
        w.setInitialScale(appliedScale);
        w.setBackgroundColor(Color.BLACK);
        w.setFocusable(true);
        w.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 26) w.setDefaultFocusHighlightEnabled(false);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);

        w.addJavascriptInterface(new Bridge(), "TVBridge");

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return handleUrl(req.getUrl().toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                inject();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                inject();
                checkNativeLive(url);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                js("window.__tv&&__tv.onRoute()");
                checkNativeLive(url);
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                if (p >= 60) inject();
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Only allow protected media (some players ask for it); deny camera/mic.
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) {
                        request.grant(new String[]{r});
                        return;
                    }
                }
                request.deny();
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) { callback.onCustomViewHidden(); return; }
                fullscreenView = view;
                fullscreenCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });
    }

    private boolean handleUrl(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            Matcher m = LIVE_ROOM.matcher(url);
            if (m.find() && shouldOpenNative(m.group(1))) {
                openNativeLive(m.group(1), null);
                return true;
            }
            return false;
        }
        // App deep links (snssdk://, intent://, market://) would try to open the phone app.
        return true;
    }

    private boolean shouldOpenNative(String user) {
        return prefs.nativeLive() && (webOnlyUser == null || !webOnlyUser.equalsIgnoreCase(user));
    }

    private void checkNativeLive(String url) {
        if (url == null) return;
        Matcher m = LIVE_ROOM.matcher(url);
        if (m.find() && shouldOpenNative(m.group(1))) {
            js("window.__tv&&__tv.pauseAll()");
            openNativeLive(m.group(1), null);
        }
    }

    private void inject() {
        String settings = settingsJson();
        js("window.__TV_SETTINGS=" + settings + ";" + tvScript);
    }

    private void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    private String settingsJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("nativeLive", prefs.nativeLive());
            o.put("autoUnmute", prefs.autoUnmute());
            o.put("hidePromos", prefs.hidePromos());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private void exitFullscreen() {
        if (fullscreenView == null) return;
        root.removeView(fullscreenView);
        fullscreenView = null;
        if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
        fullscreenCallback = null;
    }

    // ---------------------------------------------------------------- Native live

    private boolean liveLaunching;

    void openNativeLive(final String user, final String streamJson) {
        if (liveLaunching) return;
        liveLaunching = true;
        // Gather the other Lives on the page so Up/Down can switch between them.
        web.evaluateJavascript("window.__tv?__tv.livesJson():'[]'", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                String lives = "[]";
                try {
                    if (value != null && value.startsWith("\"")) lives = new org.json.JSONArray("[" + value + "]").getString(0);
                } catch (Exception ignored) {
                }
                Intent i = new Intent(MainActivity.this, LiveActivity.class);
                i.putExtra("user", user);
                i.putExtra("lives", lives);
                if (streamJson != null) i.putExtra("stream", streamJson);
                startActivityForResult(i, REQ_LIVE);
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() { liveLaunching = false; }
                }, 800);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_LIVE) {
            String fallback = data != null ? data.getStringExtra("fallbackUser") : null;
            if (fallback != null) {
                webOnlyUser = fallback;
                String url = "https://www.tiktok.com/@" + fallback + "/live";
                Matcher m = LIVE_ROOM.matcher(String.valueOf(web.getUrl()));
                if (m.find() && m.group(1).equalsIgnoreCase(fallback)) js("window.__tv&&__tv.resumeAll()");
                else web.loadUrl(url);
                toast("Playing in the website player");
            } else {
                String url = String.valueOf(web.getUrl());
                if (LIVE_ROOM.matcher(url).find()) {
                    if (web.canGoBack()) web.goBack();
                    else web.loadUrl("https://www.tiktok.com/live");
                }
            }
        } else if (requestCode == REQ_SETTINGS) {
            applySettings();
        }
    }

    private void applySettings() {
        js("window.__tv&&__tv.setSettings(" + settingsJson() + ")");
        if (prefs.uiScale() != appliedScale) {
            appliedScale = prefs.uiScale();
            web.setInitialScale(appliedScale);
            web.reload();
        }
        if (!prefs.nativeLive()) webOnlyUser = null;
        if ("1".equals(prefs.get("needs_reload", "0"))) {
            prefs.put("needs_reload", "0");
            web.loadUrl(Prefs.HOME_URL);
        }
    }

    // ---------------------------------------------------------------- Keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        if (fullscreenView != null && code == KeyEvent.KEYCODE_BACK) {
            if (!down) exitFullscreen();
            return true;
        }

        if (menu.getVisibility() == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (!down) closeMenu();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        String k = null;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: k = "up"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: k = "down"; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: k = "left"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: k = "right"; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: k = "ok"; break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE: k = "playpause"; break;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_NEXT: k = "next"; break;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: k = "prev"; break;
            case KeyEvent.KEYCODE_MENU:
                if (!down) openMenu();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) onBackKey();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
        if (down) {
            hideHint();
            js("window.__tv&&__tv.key('" + k + "'," + e.getRepeatCount() + ")");
        }
        return true;
    }

    private void onBackKey() {
        web.evaluateJavascript("window.__tv?__tv.back():false", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String handled) {
                if ("true".equals(handled)) return;
                if (web.canGoBack()) {
                    web.goBack();
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - lastBackAt < 2500) {
                    finish();
                } else {
                    lastBackAt = now;
                    toast("Press Back again to exit");
                }
            }
        });
    }

    // ---------------------------------------------------------------- Menu

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildMenu() {
        menu = new ScrollView(this);
        menu.setBackgroundResource(R.drawable.panel_bg);
        menu.setVisibility(View.GONE);
        menu.setFocusable(false);
        menuList = new LinearLayout(this);
        menuList.setOrientation(LinearLayout.VERTICAL);
        menuList.setPadding(dp(16), dp(28), dp(16), dp(28));
        menu.addView(menuList);

        TextView title = new TextView(this);
        title.setText("TokTV");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(16), 0, 0, dp(18));
        menuList.addView(title);

        addMenuItem("For You", new Runnable() { public void run() { go("https://www.tiktok.com/foryou"); } });
        addMenuItem("Following", new Runnable() { public void run() { go("https://www.tiktok.com/following"); } });
        addMenuItem("LIVE", new Runnable() { public void run() { go("https://www.tiktok.com/live"); } });
        addMenuItem("Explore", new Runnable() { public void run() { go("https://www.tiktok.com/explore"); } });
        addMenuItem("Search", new Runnable() { public void run() { showSearch(); } });
        addMenuItem("My profile", new Runnable() { public void run() { go("https://www.tiktok.com/profile"); } });
        addMenuItem("Log in (QR code)", new Runnable() { public void run() { go("https://www.tiktok.com/login/qrcode"); } });
        addMenuItem("Settings", new Runnable() {
            public void run() { startActivityForResult(new Intent(MainActivity.this, SettingsActivity.class), REQ_SETTINGS); }
        });
        addMenuItem("Reload page", new Runnable() { public void run() { web.reload(); } });
        addMenuItem("Exit", new Runnable() { public void run() { finish(); } });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(300), -1, Gravity.START);
        root.addView(menu, lp);
    }

    private void addMenuItem(String label, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(20);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        t.setFocusable(true);
        t.setBackgroundResource(R.drawable.menu_item_bg);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeMenu();
                action.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(4);
        menuList.addView(t, lp);
    }

    void openMenu() {
        if (menu.getVisibility() == View.VISIBLE) return;
        hideHint();
        menu.setVisibility(View.VISIBLE);
        menu.bringToFront();
        View first = menuList.getChildAt(1);
        if (first != null) first.requestFocus();
    }

    private void closeMenu() {
        menu.setVisibility(View.GONE);
        web.requestFocus();
    }

    private void go(String url) {
        webOnlyUser = null;
        web.loadUrl(url);
    }

    private void showSearch() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Search TikTok");
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        final AlertDialog d = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Search")
                .setView(input)
                .setPositiveButton("Videos", null)
                .setNeutralButton("LIVE", null)
                .setNegativeButton("Accounts", null)
                .create();
        DialogInterface.OnShowListener onShow = new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                bindSearch(d, input, AlertDialog.BUTTON_POSITIVE, "https://www.tiktok.com/search?q=");
                bindSearch(d, input, AlertDialog.BUTTON_NEGATIVE, "https://www.tiktok.com/search/user?q=");
                bindSearch(d, input, AlertDialog.BUTTON_NEUTRAL, "https://www.tiktok.com/search/live?q=");
                input.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        };
        d.setOnShowListener(onShow);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                // Move focus to the buttons so the user can pick Videos / Accounts / LIVE.
                View b = d.getButton(AlertDialog.BUTTON_POSITIVE);
                if (b != null) b.requestFocus();
                return true;
            }
        });
        d.show();
    }

    private void bindSearch(final AlertDialog d, final EditText input, int which, final String base) {
        View b = d.getButton(which);
        if (b == null) return;
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String q = input.getText().toString().trim();
                if (q.isEmpty()) return;
                d.dismiss();
                go(base + Uri.encode(q));
            }
        });
    }

    // ---------------------------------------------------------------- Hint + toast

    private void buildHint() {
        hint = new TextView(this);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(16);
        hint.setBackgroundResource(R.drawable.card_bg);
        hint.setPadding(dp(20), dp(10), dp(20), dp(10));
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(24);
        root.addView(hint, lp);
    }

    private final Runnable hideHintRunnable = new Runnable() {
        @Override
        public void run() { hideHint(); }
    };

    private void showHint(String text, long ms) {
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
        hint.bringToFront();
        ui.removeCallbacks(hideHintRunnable);
        ui.postDelayed(hideHintRunnable, ms);
    }

    private void hideHint() {
        if (hint != null) hint.setVisibility(View.GONE);
    }

    void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- Lifecycle

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getData() != null) web.loadUrl(intent.getData().toString());
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        web.requestFocus();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- JS bridge

    private class Bridge {
        @JavascriptInterface
        public void openMenu() {
            ui.post(new Runnable() { public void run() { MainActivity.this.openMenu(); } });
        }

        @JavascriptInterface
        public void openLive(final String user, final String streamJson) {
            ui.post(new Runnable() {
                public void run() {
                    if (shouldOpenNative(user)) openNativeLive(user, streamJson);
                    else web.loadUrl("https://www.tiktok.com/@" + user + "/live");
                }
            });
        }

        @JavascriptInterface
        public void showKeyboard() {
            ui.post(new Runnable() {
                public void run() {
                    web.requestFocus();
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        }

        @JavascriptInterface
        public void toast(final String msg) {
            ui.post(new Runnable() { public void run() { MainActivity.this.toast(msg); } });
        }

        @JavascriptInterface
        public void hint(final String msg) {
            ui.post(new Runnable() { public void run() { showHint(msg, 4000); } });
        }
    }
}

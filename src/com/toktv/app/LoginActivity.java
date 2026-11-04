package com.toktv.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Shows TikTok's QR-code login page and closes itself once the login cookie appears. */
public class LoginActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private Pointer pointer;
    private boolean wasLoggedIn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wasLoggedIn = FeedLoader.loggedIn();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(Prefs.DESKTOP_UA);
        web.setInitialScale(150);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                return !(u.startsWith("https://") && u.contains("tiktok"));
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        TextView hint = new TextView(this);
        hint.setText("Scan the code with the TikTok app on your phone: Profile → ☰ → scan icon.   Back to cancel.");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(16);
        hint.setBackgroundResource(R.drawable.card_bg);
        hint.setPadding(24, 12, 24, 12);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = 30;
        root.addView(hint, hlp);

        pointer = new Pointer(this, root);
        root.addView(pointer, new FrameLayout.LayoutParams(-1, -1));

        if (wasLoggedIn) {
            // Logging in again: start from a clean session.
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
        }
        web.loadUrl("https://www.tiktok.com/login/qrcode");
        ui.postDelayed(check, 2000);
    }

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            if (FeedLoader.loggedIn()) {
                CookieManager.getInstance().flush();
                Toast.makeText(LoginActivity.this, "Logged in", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
                return;
            }
            ui.postDelayed(this, 1500);
        }
    };

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_UP) pointer.toggle();
            return true;
        }
        if (pointer.handleKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}

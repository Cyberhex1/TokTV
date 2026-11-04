package com.toktv.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;

/**
 * Read-only LIVE chat drawn natively beside the video (sending happens from TokTV Remote on the\n * phone). The LIVE room page runs in a hidden WebView (video off, images off) and a small
 * script hands each new chat line, join and gift to the app; nothing of the website is shown.
 */
public class ChatPanel {
    private static final int MAX_LINES = 40;

    private final Activity a;
    private final FrameLayout root;
    private final Prefs prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private LinearLayout panel, lines;
    private TextView header;
    private boolean open;
    private String user;

    public ChatPanel(Activity a, FrameLayout root, Prefs prefs) {
        this.a = a;
        this.root = root;
        this.prefs = prefs;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }

    public boolean isOpen() { return open; }

    public boolean chatOnRight() { return !"left".equals(prefs.get("chat_side", "right")); }

    // ---------------------------------------------------------------- Open / close

    public void open(String forUser) {
        if (!open) {
            open = true;
            buildViews();
        }
        if (forUser != null && !forUser.equalsIgnoreCase(user)) {
            user = forUser;
            gotFirst = false;
            clearLines();
            note("Connecting to chat…");
            loadPage();
        }
        panel.setVisibility(View.VISIBLE);
        panel.bringToFront();
    }

    public void close() {
        open = false;
        user = null;
        if (panel != null) { root.removeView(panel); panel = null; }
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
    }

    /** Switching LIVE: follow the new room's chat. */
    public void switchTo(String newUser) {
        if (open) open(newUser);
    }

    // ---------------------------------------------------------------- Views

    private void buildViews() {
        boolean right = chatOnRight();
        float size = textSize();

        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#C0101010"));
        bg.setCornerRadius(dp(14));
        panel.setBackground(bg);
        panel.setPadding(dp(14), dp(10), dp(14), dp(10));
        header = new TextView(a);
        header.setTextColor(Color.parseColor("#888888"));
        header.setTextSize(13);
        header.setText("LIVE chat");
        panel.addView(header);
        lines = new LinearLayout(a);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setGravity(Gravity.BOTTOM);
        panel.addView(lines, new LinearLayout.LayoutParams(-1, 0, 1f));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(panelWidth(), -1,
                right ? Gravity.END : Gravity.START);
        plp.setMargins(dp(16), dp(16), dp(16), dp(16));
        root.addView(panel, plp);

        lines.setTag(size);
    }

    private int panelWidth() {
        int w = a.getResources().getDisplayMetrics().widthPixels;
        return (int) (w * 0.27f);
    }

    private float textSize() {
        try { return Float.parseFloat(prefs.get("chat_size", "18")); } catch (Exception e) { return 18; }
    }

    private void clearLines() {
        if (lines != null) lines.removeAllViews();
    }

    private void note(String s) {
        addLine(null, s, Color.parseColor("#888888"), false);
    }

    private void addLine(String name, String msg, int msgColor, boolean small) {
        if (lines == null) return;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (name != null && name.length() > 0) {
            sb.append(name);
            sb.setSpan(new ForegroundColorSpan(nameColor(name)), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append("  ");
        }
        int s = sb.length();
        sb.append(msg);
        sb.setSpan(new ForegroundColorSpan(msgColor), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (small) sb.setSpan(new RelativeSizeSpan(0.8f), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView t = new TextView(a);
        t.setText(sb);
        t.setTextSize(textSize());
        t.setPadding(0, dp(3), 0, dp(3));
        lines.addView(t);
        while (lines.getChildCount() > MAX_LINES) lines.removeViewAt(0);
    }

    private static final int[] NAME_COLORS = {
            0xFF25F4EE, 0xFFFFC94D, 0xFF8AB4FF, 0xFFB6F36A, 0xFFFF9AC2, 0xFFFFA45C, 0xFFC7A6FF};

    private static int nameColor(String name) {
        return NAME_COLORS[(name.hashCode() & 0x7FFFFFFF) % NAME_COLORS.length];
    }

    // ---------------------------------------------------------------- Hidden room page

    private void loadPage() {
        if (web == null) {
            web = new WebView(a);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setUserAgentString(Prefs.DESKTOP_UA);
            s.setMediaPlaybackRequiresUserGesture(true);
            s.setBlockNetworkImage(true);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            web.setFocusable(false);
            web.setBackgroundColor(Color.BLACK);
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
            web.addJavascriptInterface(new Bridge(), "TVChat");
            web.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    return !r.getUrl().toString().startsWith("https://www.tiktok.com/");
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                    String url = r.getUrl().toString();
                    String host = r.getUrl().getHost() == null ? "" : r.getUrl().getHost().toLowerCase();
                    String path = url.split("\\?")[0].toLowerCase();
                    // The video and pictures aren't needed for chat.
                    if (host.startsWith("pull-") || path.endsWith(".flv") || path.endsWith(".m3u8")
                            || path.endsWith(".ts") || path.endsWith(".mp4") || MainActivity.isBlocked(host)) {
                        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                    }
                    return null;
                }

                @Override
                public void onPageFinished(WebView v, String url) {
                    v.evaluateJavascript(CHAT_JS, null);
                }
            });
            // Full size but underneath the (black) video area, so the page lays out its chat
            // column normally without ever being seen.
            root.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
        }
        web.loadUrl("https://www.tiktok.com/@" + user + "/live");
        ui.removeCallbacks(injectAgain);
        ui.postDelayed(injectAgain, 8000);
    }

    private final Runnable injectAgain = new Runnable() {
        @Override
        public void run() {
            if (web != null) web.evaluateJavascript(CHAT_JS, null);
        }
    };

    private static final String CHAT_JS =
            "(function(){if(window.__tvchat)return;window.__tvchat=1;"
            + "var st=document.createElement('style');st.textContent='video{display:none!important}';document.head.appendChild(st);"
            + "var seen=new WeakSet();function tx(e){return e?(e.innerText||'').trim():''}"
            + "function scan(){var v=document.querySelectorAll('video');for(var i=0;i<v.length;i++){v[i].muted=true;try{v[i].pause()}catch(e){}}"
            + "var out=[];var els=document.querySelectorAll('[data-e2e=\"chat-message\"],[data-e2e=\"enter-message\"],[data-e2e=\"social-message\"],[data-e2e=\"gift-message\"]');"
            + "for(var i=0;i<els.length;i++){var e=els[i];if(seen.has(e))continue;seen.add(e);var k=e.getAttribute('data-e2e');"
            + "var ne=e.querySelector('[data-e2e=\"message-owner-name\"]');var n=ne?(ne.getAttribute('title')||tx(ne)):'';var m='';"
            + "if(k==='chat-message'){var col=ne?ne.closest('.flex-col'):null;var c=col?col.lastElementChild:null;"
            + "if(c&&!c.contains(ne))m=tx(c);if(!m){var t=tx(e).split('\\n');m=t[t.length-1];}"
            + "var g=e.querySelector('img[src*=\"gift\"],img[src*=\"webcast\"]');if(g&&/sent|\\u00d7|x\\d/i.test(m))k='gift';}"
            + "else{var p=tx(e).split('\\n');if(!n){n=p[0];p=p.slice(1);}else if(p[0]===n){p=p.slice(1);}m=p.join(' ');}"
            + "if(m||n)out.push({k:k,n:n,m:m});}"
            + "if(out.length)TVChat.push(JSON.stringify(out.slice(-25)));}"
            + "scan();setInterval(scan,700);})();";

    private class Bridge {
        @JavascriptInterface
        public void push(final String json) {
            ui.post(new Runnable() {
                @Override
                public void run() { onLines(json); }
            });
        }
    }

    private boolean gotFirst;

    private void onLines(String json) {
        if (!open || lines == null) return;
        try {
            JSONArray arr = new JSONArray(json);
            if (!gotFirst || (lines.getChildCount() == 1 && arr.length() > 0)) {
                clearLines();
                gotFirst = true;
            }
            boolean joins = !"0".equals(prefs.get("chat_joins", "1"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String k = o.optString("k"), n = o.optString("n"), m = o.optString("m");
                if ("enter-message".equals(k)) {
                    if (joins) addLine(n, "joined", Color.parseColor("#8A8A8A"), true);
                } else if ("gift".equals(k) || "gift-message".equals(k)) {
                    addLine(n, "🎁 " + m, Color.parseColor("#FFD66B"), false);
                } else if ("social-message".equals(k)) {
                    addLine(n, m, Color.parseColor("#9FD8FF"), true);
                } else {
                    addLine(n, m, Color.WHITE, false);
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- For TokTV Remote (phone)

    private final java.util.LinkedList<JSONObject> recent = new java.util.LinkedList<>();

    /** The last chat lines (for the phone remote's chat mirror). */
    public synchronized JSONArray recentLines() {
        JSONArray a = new JSONArray();
        for (JSONObject o : recent) a.put(o);
        return a;
    }

    private synchronized void keep(JSONObject o) {
        try { o.put("t", System.currentTimeMillis()); } catch (Exception ignored) { }
        recent.add(o);
        while (recent.size() > 60) recent.removeFirst();
    }

    public String currentUser() { return open ? user : null; }

    /** Sends a chat message as the logged-in user, through the LIVE page's own chat box. */
    public void sendMessage(String text, android.webkit.ValueCallback<String> done) {
        if (web == null) { if (done != null) done.onReceiveValue("\"nochat\""); return; }
        String js = "(function(t){var f=document.querySelector('[data-e2e=\"room-chat-input-field\"]');if(!f)return 'none';"
                + "var ed=f.matches('[contenteditable],textarea,input')?f:(f.querySelector('[contenteditable],textarea,input')||f);"
                + "ed.focus();if(!document.execCommand('insertText',false,t)){ed.textContent=t;ed.dispatchEvent(new InputEvent('input',{bubbles:true,data:t}));}"
                + "setTimeout(function(){var b=document.querySelector('[data-e2e=\"room-chat-send-btn\"]');if(b)b.click();"
                + "else ed.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',keyCode:13,bubbles:true}));},250);return 'ok';})("
                + JSONObject.quote(text) + ")";
        web.evaluateJavascript(js, done);
    }

    /** Taps the LIVE page's like (heart) button. */
    public void like(android.webkit.ValueCallback<String> done) {
        if (web == null) { if (done != null) done.onReceiveValue("\"nochat\""); return; }
        web.evaluateJavascript("(function(){var b=document.querySelector('[data-e2e=\"room-chat-like-btn\"]');if(!b)return 'none';b.click();return 'ok';})()", done);
    }

    /** Runs a script in the hidden LIVE page (used by the remote for follow and gifts). */
    public void runInPage(String js, android.webkit.ValueCallback<String> done) {
        if (web == null) { if (done != null) done.onReceiveValue("\"nochat\""); return; }
        web.evaluateJavascript(js, done);
    }
}

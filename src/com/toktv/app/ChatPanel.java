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
 * Read-only LIVE chat drawn natively beside the video, plus a column of quick reactions on the
 * other side. The LIVE room page runs in a hidden WebView (video off, images off) and a small
 * script hands each new chat line, join and gift to the app; nothing of the website is shown.
 */
public class ChatPanel {
    private static final int MAX_LINES = 40;
    private static final String[][] REACTIONS = {
            {"like", "❤️", "Like"},
            {"msg", "👋", "👋"},
            {"msg", "😂", "😂"},
            {"msg", "🔥", "🔥"},
            {"msg", "👏", "👏"},
            {"msg", "hi!", "hi!"},
    };

    private final Activity a;
    private final FrameLayout root;
    private final Prefs prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private LinearLayout panel, lines, reactions;
    private TextView header;
    private boolean open;
    private String user;
    private int reactIndex = -1;   // -1: reactions not focused
    private long lastReactAt;

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

    public boolean reactionsFocused() { return reactIndex >= 0; }

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
        reactions.setVisibility(View.VISIBLE);
        panel.bringToFront();
        reactions.bringToFront();
    }

    public void close() {
        open = false;
        reactIndex = -1;
        user = null;
        if (panel != null) { root.removeView(panel); panel = null; }
        if (reactions != null) { root.removeView(reactions); reactions = null; }
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

        reactions = new LinearLayout(a);
        reactions.setOrientation(LinearLayout.VERTICAL);
        reactions.setGravity(Gravity.CENTER_HORIZONTAL);
        for (String[] r : REACTIONS) {
            TextView b = new TextView(a);
            b.setText(r[1]);
            b.setTextSize("hi!".equals(r[1]) ? 20 : 28);
            b.setTextColor(Color.WHITE);
            b.setGravity(Gravity.CENTER);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(76), dp(64));
            lp.bottomMargin = dp(8);
            reactions.addView(b, lp);
        }
        TextView hint = new TextView(a);
        hint.setTextSize(12);
        hint.setTextColor(Color.parseColor("#999999"));
        hint.setGravity(Gravity.CENTER);
        hint.setText(right ? "◀ to react" : "▶ to react");
        reactions.addView(hint);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(-2, -2,
                (right ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL);
        rlp.setMargins(dp(24), 0, dp(24), 0);
        root.addView(reactions, rlp);
        paintReactions();
        lines.setTag(size);
    }

    private int panelWidth() {
        int w = a.getResources().getDisplayMetrics().widthPixels;
        return (int) (w * 0.27f);
    }

    private float textSize() {
        try { return Float.parseFloat(prefs.get("chat_size", "18")); } catch (Exception e) { return 18; }
    }

    private void paintReactions() {
        if (reactions == null) return;
        for (int i = 0; i < REACTIONS.length; i++) {
            View b = reactions.getChildAt(i);
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dp(16));
            boolean sel = i == reactIndex;
            d.setColor(Color.parseColor(sel ? "#FFFE2C55" : "#A0101010"));
            if (sel) d.setStroke(dp(2), Color.WHITE);
            b.setBackground(d);
            b.setScaleX(sel ? 1.12f : 1f);
            b.setScaleY(sel ? 1.12f : 1f);
        }
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

    // ---------------------------------------------------------------- Reactions

    /** Arrow toward the reactions side focuses them; returns true if the key was used. */
    public boolean handleKey(KeyEvent e) {
        if (!open) return false;
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int toward = chatOnRight() ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
        int away = chatOnRight() ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
        if (reactIndex < 0) {
            if (code == toward) {
                if (down) {
                    reactIndex = 0;
                    paintReactions();
                }
                return true;
            }
            return false;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) { reactIndex = Math.max(0, reactIndex - 1); paintReactions(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) { reactIndex = Math.min(REACTIONS.length - 1, reactIndex + 1); paintReactions(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (down && e.getRepeatCount() == 0) react(REACTIONS[reactIndex]);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) { reactIndex = -1; paintReactions(); }
                return true;
        }
        if (code == away || code == toward) {
            if (down && code == away) { reactIndex = -1; paintReactions(); }
            return true;
        }
        return false;
    }

    private void react(String[] r) {
        long now = System.currentTimeMillis();
        if (now - lastReactAt < 1500) return;   // no accidental spam
        lastReactAt = now;
        if (web == null) return;
        String js;
        if ("like".equals(r[0])) {
            js = "(function(){var b=document.querySelector('[data-e2e=\"room-chat-like-btn\"]');if(!b)return 'none';b.click();return 'ok';})()";
        } else {
            js = "(function(t){var f=document.querySelector('[data-e2e=\"room-chat-input-field\"]');if(!f)return 'none';"
                    + "var ed=f.matches('[contenteditable],textarea,input')?f:(f.querySelector('[contenteditable],textarea,input')||f);"
                    + "ed.focus();if(!document.execCommand('insertText',false,t)){ed.textContent=t;ed.dispatchEvent(new InputEvent('input',{bubbles:true,data:t}));}"
                    + "setTimeout(function(){var b=document.querySelector('[data-e2e=\"room-chat-send-btn\"]');if(b)b.click();"
                    + "else ed.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',keyCode:13,bubbles:true}));},250);return 'ok';})("
                    + JSONObject.quote(r[1]) + ")";
        }
        final String label = r[2];
        web.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                Toast.makeText(a, "\"ok\"".equals(v) ? "Sent " + label : "Couldn't send yet: chat is still loading",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }
}

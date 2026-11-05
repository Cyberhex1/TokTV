package com.toktv.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional chat filter (Settings → Profanity filter, off by default): masks common swear words. */
final class Profanity {
    private static final Pattern WORDS = Pattern.compile(
            "\\b(f+u+c+k+\\w*|sh+i+t+\\w*|b+i+t+c+h+\\w*|a+s+s+h+o+l+e+\\w*|d+i+c+k+\\w*|p+u+s+s+y+\\w*|c+u+n+t+\\w*"
                    + "|n+i+g+g+\\w*|f+a+g+\\w*|wh+o+r+e+\\w*|sl+u+t+\\w*|bastard\\w*|motherf\\w*)",
            Pattern.CASE_INSENSITIVE);

    private Profanity() { }

    static String mask(String s) {
        if (s == null || s.isEmpty()) return s;
        Matcher m = WORDS.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String w = m.group();
            StringBuilder r = new StringBuilder().append(w.charAt(0));
            for (int i = 1; i < w.length(); i++) r.append('*');
            m.appendReplacement(out, Matcher.quoteReplacement(r.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }
}

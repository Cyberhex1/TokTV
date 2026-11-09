package com.toktv.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Decodes TikTok's LIVE websocket frames (protobuf, usually gzipped) into chat lines, so chat
 * reaches the TV the moment the frame arrives instead of after TikTok's page has rendered it.
 *
 * PushFrame{6 payloadEncoding, 7 payloadType="msg", 8 payload}
 *   payload = Response{1 messages[]: Message{1 method, 2 body}}
 *   every body starts with Common{3 roomId, 4 createTime ms, 8 displayText}
 */
final class ChatWire {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    static final class Line {
        String kind, name, text;
        long roomId, createdAt;
        /** Stable key for dropping the same message twice (TikTok resends on reconnect). */
        long msgId;
    }

    private ChatWire() { }

    // ---------------------------------------------------------------- Protobuf reading

    /** A field: number, wire type, varint value or [off, len) of a length-delimited value. */
    private static final class Reader {
        final byte[] b;
        int pos;
        final int end;
        int field, wire;
        long varint;
        int off, len;

        Reader(byte[] b, int off, int len) {
            this.b = b;
            this.pos = off;
            this.end = off + len;
        }

        boolean next() {
            if (pos >= end) return false;
            long key = readVarint();
            if (key < 0) return false;
            field = (int) (key >>> 3);
            wire = (int) (key & 7);
            switch (wire) {
                case 0: varint = readVarint(); return varint != Long.MIN_VALUE;
                case 1: pos += 8; return pos <= end;
                case 5: pos += 4; return pos <= end;
                case 2: {
                    long n = readVarint();
                    if (n < 0 || pos + n > end) return false;
                    off = pos;
                    len = (int) n;
                    pos += len;
                    return true;
                }
                default: return false;
            }
        }

        private long readVarint() {
            long x = 0;
            for (int s = 0; s < 64; s += 7) {
                if (pos >= end) return Long.MIN_VALUE;
                int c = b[pos++] & 0xFF;
                x |= (long) (c & 0x7F) << s;
                if ((c & 0x80) == 0) return x;
            }
            return Long.MIN_VALUE;
        }

        String str() { return new String(b, off, len, UTF8); }

        Reader sub() { return new Reader(b, off, len); }
    }

    // ---------------------------------------------------------------- Frames

    /** All displayable lines in one websocket frame. Never throws. */
    static List<Line> decodeFrame(byte[] frame) {
        List<Line> out = new ArrayList<>();
        try {
            Reader r = new Reader(frame, 0, frame.length);
            String enc = "", type = "";
            int pOff = -1, pLen = 0;
            while (r.next()) {
                if (r.wire != 2) continue;
                if (r.field == 6) enc = r.str();
                else if (r.field == 7) type = r.str();
                else if (r.field == 8) { pOff = r.off; pLen = r.len; }
            }
            if (pOff < 0 || !"msg".equals(type)) return out;
            byte[] payload;
            if ("gzip".equals(enc) || (pLen > 2 && frame[pOff] == 0x1f && frame[pOff + 1] == (byte) 0x8b)) {
                payload = gunzip(frame, pOff, pLen);
            } else {
                payload = new byte[pLen];
                System.arraycopy(frame, pOff, payload, 0, pLen);
            }
            decodeResponse(payload, out);
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** A Response body as returned by TikTok's /webcast/im/fetch/ polling (the socket's fallback). */
    static List<Line> decodeResponse(byte[] payload) {
        List<Line> out = new ArrayList<>();
        try {
            decodeResponse(payload, out);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void decodeResponse(byte[] payload, List<Line> out) {
        {
            Reader resp = new Reader(payload, 0, payload.length);
            while (resp.next()) {
                if (resp.field != 1 || resp.wire != 2) continue;
                Reader m = resp.sub();
                String method = null;
                int bOff = -1, bLen = 0;
                while (m.next()) {
                    if (m.wire != 2) continue;
                    if (m.field == 1) method = m.str();
                    else if (m.field == 2) { bOff = m.off; bLen = m.len; }
                }
                if (method == null || bOff < 0) continue;
                Line l = decodeMessage(method, payload, bOff, bLen);
                if (l != null) out.add(l);
            }
        }
    }

    private static byte[] gunzip(byte[] b, int off, int len) throws java.io.IOException {
        GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(b, off, len));
        ByteArrayOutputStream out = new ByteArrayOutputStream(len * 4);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- Messages

    private static Line decodeMessage(String method, byte[] b, int off, int len) {
        String kind;
        if ("WebcastChatMessage".equals(method)) kind = "chat-message";
        else if ("WebcastGiftMessage".equals(method)) kind = "gift";
        else if ("WebcastSocialMessage".equals(method)) kind = "social-message";
        else if ("WebcastMemberMessage".equals(method)) kind = "enter-message";
        else return null;

        Line l = new Line();
        l.kind = kind;
        String content = null, giftName = null;
        long repeat = 0, repeatEnd = 0, giftType = 0;
        int dtOff = -1, dtLen = 0;
        Reader r = new Reader(b, off, len);
        while (r.next()) {
            if (r.field == 1 && r.wire == 2) {               // Common
                Reader c = r.sub();
                while (c.next()) {
                    if (c.field == 2 && c.wire == 0) l.msgId = c.varint;
                    else if (c.field == 3 && c.wire == 0) l.roomId = c.varint;
                    else if (c.field == 4 && c.wire == 0) l.createdAt = c.varint;
                    else if (c.field == 8 && c.wire == 2) { dtOff = c.off; dtLen = c.len; }
                }
            } else if (r.wire == 2 && userField(kind) == r.field) {
                l.name = nickname(r.sub());
            } else if (kind.equals("chat-message") && r.field == 3 && r.wire == 2) {
                content = r.str();
            } else if (kind.equals("gift")) {
                if (r.field == 5 && r.wire == 0) repeat = r.varint;
                else if (r.field == 9 && r.wire == 0) repeatEnd = r.varint;
                else if (r.field == 15 && r.wire == 2) {         // GiftStruct
                    Reader g = r.sub();
                    while (g.next()) {
                        if (g.field == 16 && g.wire == 2) giftName = g.str();
                        else if (g.field == 11 && g.wire == 0) giftType = g.varint;
                    }
                }
            }
        }
        if (kind.equals("chat-message")) {
            if (content == null || content.length() == 0) return null;
            l.text = content;
        } else if (kind.equals("gift")) {
            // Streak gifts (type 1) arrive once per tap; show the streak once, when it ends.
            if (giftType == 1 && repeatEnd == 0) return null;
            l.text = "sent " + (giftName != null && giftName.length() > 0 ? giftName : "a gift")
                    + (repeat > 1 ? " ×" + repeat : "");
        } else if (kind.equals("enter-message")) {
            l.text = "joined";
        } else {
            l.text = dtOff >= 0 ? displayText(b, dtOff, dtLen, l) : null;
            if (l.text == null || l.text.length() == 0) return null;
        }
        if (l.name == null) l.name = "";
        return l;
    }

    /** Field holding the sender in each message type. */
    private static int userField(String kind) {
        return kind.equals("gift") ? 7 : 2;
    }

    /** User{3 nickname, 38 uniqueId}. */
    private static String nickname(Reader u) {
        String nick = null, id = null;
        while (u.next()) {
            if (u.wire != 2) continue;
            if (u.field == 3) nick = u.str();
            else if (u.field == 38) id = u.str();
        }
        return nick != null && nick.length() > 0 ? nick : id;
    }

    /**
     * Text{2 pattern "{0:user} shared the LIVE", 4 pieces[]: Piece{1 type, 11 string, 21 {1 user}}}.
     * The sender's name is cut from the start, since it's shown in its own colour.
     */
    private static String displayText(byte[] b, int off, int len, Line l) {
        String pattern = null;
        List<String> pieces = new ArrayList<>();
        Reader t = new Reader(b, off, len);
        while (t.next()) {
            if (t.wire != 2) continue;
            if (t.field == 2) pattern = t.str();
            else if (t.field == 4) {
                Reader p = t.sub();
                String v = "";
                while (p.next()) {
                    if (p.wire != 2) continue;
                    if (p.field == 11) v = p.str();
                    else if (p.field == 21) {
                        Reader uv = p.sub();
                        while (uv.next()) if (uv.field == 1 && uv.wire == 2) v = nickname(uv.sub());
                    }
                }
                pieces.add(v == null ? "" : v);
            }
        }
        if (pattern == null) return null;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            int close = c == '{' ? pattern.indexOf('}', i) : -1;
            if (close > i) {
                String spec = pattern.substring(i + 1, close);
                int colon = spec.indexOf(':');
                try {
                    int n = Integer.parseInt(colon < 0 ? spec : spec.substring(0, colon));
                    if (n < pieces.size()) sb.append(pieces.get(n));
                } catch (NumberFormatException e) {
                    sb.append(pattern, i, close + 1);
                }
                i = close + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        String s = sb.toString().trim();
        if (l.name != null && l.name.length() > 0 && s.startsWith(l.name)) s = s.substring(l.name.length()).trim();
        return s;
    }
}

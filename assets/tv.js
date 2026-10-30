/* TokTV remote-control layer for tiktok.com (desktop site).
 * Injected by MainActivity on every page load; safe to inject more than once.
 * Plain ES5 so it runs on older Fire OS WebViews. */
(function () {
  if (window.__tv) {
    if (window.__TV_SETTINGS) window.__tv.setSettings(window.__TV_SETTINGS);
    window.__tv.onRoute();
    return;
  }

  var B = window.TVBridge || { openMenu: function () {}, openLive: function () {}, showKeyboard: function () {}, toast: function () {}, hint: function () {} };
  var S = window.__TV_SETTINGS || {};
  var FOCUS = '__tv-focus';
  var focused = null;
  var mode = 'feed';          // 'feed' = Up/Down changes video; 'nav' = move a focus box around the page
  var lastOk = 0, lastMove = 0;
  var unmuted = [];

  // ------------------------------------------------------------------ styles
  function addStyle() {
    if (document.getElementById('__tv-style')) return;
    var st = document.createElement('style');
    st.id = '__tv-style';
    st.textContent =
      'html,body,*{cursor:none!important}' +
      '.' + FOCUS + '{outline:4px solid #25F4EE!important;outline-offset:2px!important;' +
      'box-shadow:0 0 0 8px rgba(37,244,238,.28)!important;border-radius:10px!important;' +
      'transition:outline-color .1s;z-index:2147483000;}' +
      '::-webkit-scrollbar{display:none!important}' +
      (S.hidePromos ?
        '[class*="DivPromotion"],[class*="AppPromote"],[class*="DownloadApp"],[class*="GetAppContainer"],' +
        '[data-e2e="download-app-entrance"],[class*="DivDownloadBar"],[class*="BannerContainer"][class*="App"]{display:none!important}' : '');
    (document.head || document.documentElement).appendChild(st);
  }

  // ------------------------------------------------------------------ helpers
  function path() { return location.pathname.replace(/\/+$/, '') || '/'; }
  function liveRoomUser() { var m = /^\/@([^\/]+)\/live/.exec(location.pathname); return m ? decodeURIComponent(m[1]) : null; }
  function isLiveFeed() { return /^\/live(\/|$)/.test(location.pathname); }
  function isLiveRoom() { return !!liveRoomUser(); }
  function isVideoPage() { return /^\/@[^\/]+\/(video|photo)\//.test(location.pathname); }
  function isFeedPage() {
    var p = path();
    return p === '/' || p === '/foryou' || p === '/following' || p === '/friends' || isVideoPage();
  }
  function hasFeedMode() { return isFeedPage() || isLiveFeed() || isLiveRoom(); }

  function vw() { return window.innerWidth || document.documentElement.clientWidth; }
  function vh() { return window.innerHeight || document.documentElement.clientHeight; }

  function visible(el) {
    if (!el || !el.getBoundingClientRect) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 6 || r.height < 6) return false;
    if (r.bottom < -vh() || r.top > vh() * 2 || r.right < 0 || r.left > vw()) return false;
    var cs = window.getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) === 0) return false;
    return true;
  }
  function onScreen(el) {
    var r = el.getBoundingClientRect();
    return r.bottom > 0 && r.top < vh() && r.right > 0 && r.left < vw() && r.width > 0;
  }

  function fire(el, type) {
    var r = el.getBoundingClientRect();
    var o = { bubbles: true, cancelable: true, view: window, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
    var ev;
    try {
      ev = /^pointer/.test(type) && window.PointerEvent ? new PointerEvent(type, o) : new MouseEvent(type, o);
    } catch (e) {
      ev = document.createEvent('MouseEvents');
      ev.initMouseEvent(type, true, true, window, 1, 0, 0, o.clientX, o.clientY, false, false, false, false, 0, null);
    }
    el.dispatchEvent(ev);
  }
  function hover(el) { ['pointerover', 'pointerenter', 'mouseover', 'mouseenter', 'mousemove'].forEach(function (t) { fire(el, t); }); }
  function click(el) {
    ['pointerdown', 'mousedown', 'pointerup', 'mouseup'].forEach(function (t) { fire(el, t); });
    el.click();
  }
  function sendKey(key, code) {
    var t = document.activeElement || document.body;
    ['keydown', 'keyup'].forEach(function (type) {
      var e;
      try { e = new KeyboardEvent(type, { key: key, code: key, keyCode: code, which: code, bubbles: true, cancelable: true }); }
      catch (x) { e = document.createEvent('Event'); e.initEvent(type, true, true); e.key = key; e.keyCode = code; }
      t.dispatchEvent(e);
    });
  }
  function isTextInput(el) {
    if (!el) return false;
    var tag = el.tagName;
    if (tag === 'TEXTAREA' || el.isContentEditable) return true;
    if (tag !== 'INPUT') return false;
    return !/^(button|submit|checkbox|radio|range|file|image|reset|color)$/i.test(el.type || 'text');
  }

  // ------------------------------------------------------------------ focus box
  var SEL = 'a[href],button,input,textarea,select,[role="button"],[role="tab"],[role="link"],[role="menuitem"],' +
    '[role="option"],[role="switch"],[role="checkbox"],[tabindex]:not([tabindex="-1"]),[contenteditable="true"],' +
    '[data-e2e$="-icon"],[data-e2e*="live-card"],[data-e2e*="user-card"]';

  function scopeRoot() {
    var ds = document.querySelectorAll('[role="dialog"],[aria-modal="true"],[class*="DivModalContainer"],[class*="ModalContent"]');
    for (var i = ds.length - 1; i >= 0; i--) {
      if (visible(ds[i]) && ds[i].getBoundingClientRect().height > 80) return ds[i];
    }
    return document;
  }

  function candidates() {
    var root = scopeRoot();
    var list = root.querySelectorAll(SEL), out = [];
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (el.disabled || el.getAttribute('aria-hidden') === 'true') continue;
      // Skip wrappers whose focusable child is what you actually want.
      if (el.tagName !== 'INPUT' && el.querySelector && el.querySelector('button,a[href]') && el.getBoundingClientRect().width > vw() * 0.6) continue;
      if (visible(el)) out.push(el);
    }
    // Drop elements nested inside another candidate with the same box (icon inside button).
    return out.filter(function (el) {
      var p = el.parentElement;
      while (p && p !== document.body) {
        if (out.indexOf(p) >= 0) {
          var a = el.getBoundingClientRect(), b = p.getBoundingClientRect();
          if (Math.abs(a.width - b.width) < 24 && Math.abs(a.height - b.height) < 24) return false;
          break;
        }
        p = p.parentElement;
      }
      return true;
    });
  }

  function setFocus(el, center) {
    if (focused && focused !== el) focused.classList.remove(FOCUS);
    focused = el;
    if (!el) return;
    el.classList.add(FOCUS);
    try { el.focus({ preventScroll: true }); } catch (e) { try { el.focus(); } catch (x) {} }
    var r = el.getBoundingClientRect();
    if (r.top < 60 || r.bottom > vh() - 40 || r.left < 0 || r.right > vw()) {
      try { el.scrollIntoView({ block: center ? 'center' : 'nearest', inline: 'nearest', behavior: 'smooth' }); }
      catch (e) { el.scrollIntoView(false); }
    }
    hover(el);
  }

  function clearFocus() {
    if (focused) focused.classList.remove(FOCUS);
    focused = null;
  }

  function focusValid() {
    return focused && document.contains(focused) && visible(focused) && (scopeRoot() === document || scopeRoot().contains(focused));
  }

  function firstFocus() {
    var c = candidates().filter(onScreen);
    if (!c.length) return null;
    // Prefer something in the main content (not the far-left nav) near the top.
    c.sort(function (a, b) {
      var ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
      var sa = ra.top + (ra.left < vw() * 0.2 ? 400 : 0), sb = rb.top + (rb.left < vw() * 0.2 ? 400 : 0);
      return sa - sb;
    });
    return c[0];
  }

  function bestInDirection(dir) {
    var c = focused.getBoundingClientRect();
    var cx = c.left + c.width / 2, cy = c.top + c.height / 2;
    var best = null, bestScore = Infinity, list = candidates();
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (el === focused || focused.contains(el) || el.contains(focused)) continue;
      var r = el.getBoundingClientRect();
      var x = r.left + r.width / 2, y = r.top + r.height / 2, main, cross, overlap;
      if (dir === 'down') { main = r.top - c.bottom; if (y <= cy + 2) continue; cross = Math.max(0, Math.max(r.left - c.right, c.left - r.right)); overlap = cross === 0; }
      else if (dir === 'up') { main = c.top - r.bottom; if (y >= cy - 2) continue; cross = Math.max(0, Math.max(r.left - c.right, c.left - r.right)); overlap = cross === 0; }
      else if (dir === 'right') { main = r.left - c.right; if (x <= cx + 2) continue; cross = Math.max(0, Math.max(r.top - c.bottom, c.top - r.bottom)); overlap = cross === 0; }
      else { main = c.left - r.right; if (x >= cx - 2) continue; cross = Math.max(0, Math.max(r.top - c.bottom, c.top - r.bottom)); overlap = cross === 0; }
      main = Math.max(main, 0);
      var score = main + cross * 3 + (overlap ? 0 : 60) + Math.abs((dir === 'up' || dir === 'down') ? x - cx : y - cy) * 0.15;
      if (score < bestScore) { bestScore = score; best = el; }
    }
    return best;
  }

  function scrollable(el) {
    while (el && el !== document.body && el !== document.documentElement) {
      var cs = window.getComputedStyle(el);
      if (/(auto|scroll)/.test(cs.overflowY) && el.scrollHeight > el.clientHeight + 10) return el;
      el = el.parentElement;
    }
    return document.scrollingElement || document.documentElement;
  }

  function move(dir) {
    if (!focusValid()) {
      var f = firstFocus();
      if (f) setFocus(f);
      else if (dir === 'left') B.openMenu();
      else if (dir === 'up' || dir === 'down') scrollable(null).scrollBy(0, (dir === 'down' ? 1 : -1) * vh() * 0.6);
      return;
    }
    var next = bestInDirection(dir);
    if (next) { setFocus(next); return; }
    if (dir === 'left') { B.openMenu(); return; }
    if (dir === 'up' || dir === 'down') {
      // Nothing more on screen: scroll, then try again once new content shows.
      var sc = scrollable(focused);
      var before = sc.scrollTop;
      sc.scrollBy({ top: (dir === 'down' ? 1 : -1) * vh() * 0.6, behavior: 'smooth' });
      setTimeout(function () {
        var n = bestInDirection(dir);
        if (n) setFocus(n);
        else if (sc.scrollTop === before && dir === 'up') { var t = firstFocus(); if (t) setFocus(t); }
      }, 350);
    }
  }

  function activate() {
    if (!focusValid()) { var f = firstFocus(); if (f) setFocus(f); return; }
    var el = focused;
    var href = el.getAttribute && el.getAttribute('href');
    var m = href && /^(?:https?:\/\/(?:www\.)?tiktok\.com)?\/@([^\/?#]+)\/live/.exec(href);
    if (m && S.nativeLive) { B.openLive(decodeURIComponent(m[1]), ''); return; }
    if (isTextInput(el)) {
      el.focus();
      click(el);
      B.showKeyboard();
      return;
    }
    click(el);
  }

  // ------------------------------------------------------------------ feed (videos + Lives)
  function videos() {
    var vs = document.querySelectorAll('video'), out = [];
    for (var i = 0; i < vs.length; i++) {
      var r = vs[i].getBoundingClientRect();
      if (r.height > 150 && r.width > 100) out.push(vs[i]);
    }
    return out;
  }
  function currentVideo() {
    var vs = videos(), best = null, bd = Infinity, mid = vh() / 2;
    for (var i = 0; i < vs.length; i++) {
      var r = vs[i].getBoundingClientRect();
      if (r.bottom < 0 || r.top > vh()) continue;
      var d = Math.abs(r.top + r.height / 2 - mid);
      if (d < bd) { bd = d; best = vs[i]; }
    }
    return best;
  }
  function feedItems() {
    var sels = ['[data-e2e="recommend-list-item-container"]', 'article[data-scroll-index]', 'article', '[class*="DivItemContainerForSearch"]', '[class*="DivItemContainer"]'];
    for (var i = 0; i < sels.length; i++) {
      var list = Array.prototype.slice.call(document.querySelectorAll(sels[i])).filter(function (e) { return e.getBoundingClientRect().height > 200; });
      if (list.length > 1) return list;
    }
    return [];
  }
  function currentItem(items) {
    var mid = vh() / 2, best = -1, bd = Infinity;
    for (var i = 0; i < items.length; i++) {
      var r = items[i].getBoundingClientRect();
      var d = Math.abs(r.top + r.height / 2 - mid);
      if (d < bd) { bd = d; best = i; }
    }
    return best;
  }

  // TikTok's own up/down arrows (For You side arrows, video page arrows, LIVE feed arrows).
  function arrowButton(next) {
    var re = next ? /(arrow-(down|right)|next|swiper-next)/i : /(arrow-(up|left)|prev|previous|swiper-prev)/i;
    var btns = document.querySelectorAll('button,[role="button"],[data-e2e*="arrow"]');
    for (var i = 0; i < btns.length; i++) {
      var b = btns[i];
      var label = (b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('data-e2e') || '') + ' ' + (b.getAttribute('title') || '');
      if (!re.test(label) || /upload|comment|reply|search/i.test(label)) continue;
      var r = b.getBoundingClientRect();
      if (!visible(b) || !onScreen(b) || r.width > 160 || r.left < vw() * 0.4) continue;
      if (b.disabled || b.getAttribute('aria-disabled') === 'true') continue;
      return b;
    }
    return null;
  }

  function step(dir) {
    var now = Date.now();
    if (now - lastMove < 450) return;   // one video per press, even when the key repeats
    lastMove = now;
    var room = liveRoomUser();
    if (room) { nextLiveRoom(room, dir); return; }

    var btn = arrowButton(dir > 0);
    if (btn) { click(btn); afterStep(); return; }

    var items = feedItems();
    if (items.length) {
      var i = currentItem(items), t = items[i + dir];
      if (t) { t.scrollIntoView({ block: 'center', behavior: 'smooth' }); afterStep(); return; }
    }
    // Fall back to the keyboard shortcut and a page-height scroll.
    sendKey(dir > 0 ? 'ArrowDown' : 'ArrowUp', dir > 0 ? 40 : 38);
    var v = currentVideo();
    var sc = scrollable(v || null);
    sc.scrollBy({ top: dir * vh() * 0.9, behavior: 'smooth' });
    afterStep();
  }

  function afterStep() {
    clearFocus();
    setTimeout(ensureAudio, 700);
    setTimeout(ensureAudio, 1600);
    setTimeout(rememberLives, 1200);
  }

  function ensureAudio() {
    if (!S.autoUnmute) return;
    var v = currentVideo();
    if (!v) return;
    if (v.paused && v.readyState > 1 && !v.__tvUserPaused) { var p = v.play(); if (p && p.catch) p.catch(function () {}); }
    if (v.muted && unmuted.indexOf(v) < 0) {
      unmuted.push(v);
      if (unmuted.length > 30) unmuted.shift();
      // Prefer TikTok's own unmute control so its UI stays in sync.
      var b = findNear(v, /unmute|sound|volume|mute/i, 'button,[role="button"],[data-e2e*="volume"],[data-e2e*="mute"]');
      if (b && /unmute|muted/i.test((b.getAttribute('aria-label') || '') + (b.getAttribute('data-e2e') || ''))) click(b);
      if (v.muted) { v.muted = false; v.volume = 1; }
    }
  }

  function findNear(v, re, sel) {
    var p = v;
    for (var up = 0; up < 8 && p; up++) {
      p = p.parentElement;
      if (!p) break;
      var list = p.querySelectorAll(sel);
      for (var i = 0; i < list.length; i++) {
        var lab = (list[i].getAttribute('aria-label') || '') + ' ' + (list[i].getAttribute('data-e2e') || '') + ' ' + (list[i].getAttribute('title') || '');
        if (re.test(lab) && visible(list[i])) return list[i];
      }
    }
    return null;
  }

  function itemFor(v) {
    var items = feedItems();
    for (var i = 0; i < items.length; i++) if (items[i].contains(v)) return items[i];
    return null;
  }

  function actionButtons() {
    var v = currentVideo();
    var scope = (v && itemFor(v)) || document;
    var sel = '[data-e2e="like-icon"],[data-e2e="browse-like-icon"],[data-e2e="comment-icon"],[data-e2e="browse-comment-icon"],' +
      '[data-e2e="undefined-icon"],[data-e2e="bookmark-icon"],[data-e2e="share-icon"],[data-e2e="video-author-avatar"],' +
      '[data-e2e="feed-follow"],[data-e2e="browse-follow"]';
    var list = Array.prototype.slice.call(scope.querySelectorAll(sel)).filter(visible);
    return list.map(function (el) { return el.closest('button,a,[role="button"]') || el; });
  }

  function like() {
    var btns = actionButtons();
    for (var i = 0; i < btns.length; i++) {
      var e2e = btns[i].querySelector('[data-e2e$="like-icon"]') || (/like-icon/.test(btns[i].getAttribute('data-e2e') || '') ? btns[i] : null);
      if (e2e) { click(btns[i]); B.toast('♥ Liked'); return; }
    }
    var b = document.querySelector('button[aria-label*="like" i]');
    if (b && onScreen(b)) { click(b); B.toast('♥ Liked'); }
  }

  function playPause() {
    var v = currentVideo();
    if (!v) return;
    if (v.muted && S.autoUnmute) { v.muted = false; v.volume = 1; }
    if (v.paused) { v.__tvUserPaused = false; var p = v.play(); if (p && p.catch) p.catch(function () {}); }
    else { v.__tvUserPaused = true; v.pause(); }
  }

  function feedOk() {
    var now = Date.now();
    var v = currentVideo();
    if (now - lastOk < 450 && (isFeedPage())) {
      // Second press: undo the pause from the first press and like the video.
      lastOk = 0;
      if (v && v.paused) { v.__tvUserPaused = false; v.play(); }
      like();
      return;
    }
    lastOk = now;
    if (isLiveFeed() && S.nativeLive) {
      var u = currentLiveUser();
      if (u) { B.openLive(u, streamFromPage(u)); return; }
    }
    if ((isLiveFeed() || isLiveRoom()) && v && v.muted) { v.muted = false; v.volume = 1; return; }
    playPause();
  }

  function enterNav() {
    mode = 'nav';
    var btns = actionButtons();
    if (btns.length && isFeedPage()) setFocus(btns[0]);
    else { var f = firstFocusRightOf(currentVideo()); if (f) setFocus(f); else move('right'); }
    B.hint('Buttons: ▲▼◀▶ move   OK select   Back return to video');
  }

  function firstFocusRightOf(v) {
    if (!v) return firstFocus();
    var vr = v.getBoundingClientRect();
    var c = candidates().filter(function (el) { var r = el.getBoundingClientRect(); return onScreen(el) && r.left >= vr.right - 20; });
    c.sort(function (a, b) { return a.getBoundingClientRect().top - b.getBoundingClientRect().top; });
    return c[0] || firstFocus();
  }

  // ------------------------------------------------------------------ Lives
  function livesFromPage() {
    var out = [], a = document.querySelectorAll('a[href*="/live"]');
    for (var i = 0; i < a.length; i++) {
      var m = /\/@([^\/?#]+)\/live/.exec(a[i].getAttribute('href') || '');
      if (m) { var u = decodeURIComponent(m[1]); if (out.indexOf(u) < 0) out.push(u); }
    }
    return out;
  }
  function savedLives() { try { return JSON.parse(sessionStorage.getItem('__tvLives') || '[]'); } catch (e) { return []; } }
  function rememberLives() {
    var cur = livesFromPage();
    if (!cur.length) return;
    var all = savedLives();
    // Keep page order for what's on screen now, then older ones.
    var merged = cur.concat(all.filter(function (u) { return cur.indexOf(u) < 0; })).slice(0, 200);
    try { sessionStorage.setItem('__tvLives', JSON.stringify(merged)); } catch (e) {}
  }

  function currentLiveUser() {
    var room = liveRoomUser();
    if (room) return room;
    var v = currentVideo();
    if (!v) return null;
    var p = v;
    for (var up = 0; up < 10 && p; up++) {
      p = p.parentElement;
      if (!p) break;
      var a = p.querySelectorAll('a[href*="/@"]');
      for (var i = 0; i < a.length; i++) {
        var m = /\/@([^\/?#]+)/.exec(a[i].getAttribute('href') || '');
        if (m) return decodeURIComponent(m[1]);
      }
    }
    return null;
  }

  function nextLiveRoom(user, dir) {
    rememberLives();
    var list = savedLives();
    var i = -1;
    for (var k = 0; k < list.length; k++) if (list[k].toLowerCase() === user.toLowerCase()) { i = k; break; }
    var target = list[(i + dir + list.length) % list.length];
    if (!list.length || !target || target.toLowerCase() === user.toLowerCase()) {
      B.toast('No other LIVEs found. Opening the LIVE feed.');
      location.href = 'https://www.tiktok.com/live';
      return;
    }
    location.href = 'https://www.tiktok.com/@' + encodeURIComponent(target) + '/live';
  }

  // Stream info embedded in a live room page, so the native player can start instantly.
  function streamFromPage(user) {
    try {
      var st = window.SIGI_STATE;
      if (!st) { var s = document.getElementById('SIGI_STATE'); if (s) st = JSON.parse(s.textContent); }
      var info = st && st.LiveRoom && st.LiveRoom.liveRoomUserInfo;
      if (!info || !info.user || (user && String(info.user.uniqueId).toLowerCase() !== user.toLowerCase())) return '';
      return JSON.stringify(info);
    } catch (e) { return ''; }
  }

  // ------------------------------------------------------------------ back
  function closeOverlay() {
    var root = scopeRoot();
    var closeSel = '[data-e2e*="close"],button[aria-label*="close" i],[role="button"][aria-label*="close" i],[class*="CloseButton"],[class*="DivClose"]';
    if (root !== document) {
      var c = root.querySelector(closeSel) || document.querySelector('[data-e2e="modal-close-inner-button"]');
      if (c && visible(c)) click(c); else sendKey('Escape', 27);
      clearFocus();
      return true;
    }
    // Side panels (comments on For You, etc.)
    var cs = document.querySelectorAll(closeSel);
    for (var i = 0; i < cs.length; i++) {
      if (visible(cs[i]) && onScreen(cs[i]) && cs[i].getBoundingClientRect().left > vw() * 0.45) { click(cs[i]); clearFocus(); return true; }
    }
    return false;
  }

  // ------------------------------------------------------------------ public API (called from Java)
  var api = {
    key: function (k, repeat) {
      addStyle();
      var ae = document.activeElement;
      if (isTextInput(ae) && (k === 'left' || k === 'right')) {
        // Move the text cursor instead of the focus box.
        try {
          var pos = ae.selectionStart + (k === 'left' ? -1 : 1);
          if (pos >= 0 && pos <= (ae.value || '').length) { ae.setSelectionRange(pos, pos); return; }
        } catch (e) {}
      }
      if (k === 'playpause') { playPause(); return; }
      if (k === 'next' || k === 'prev') { step(k === 'next' ? 1 : -1); return; }

      var feed = hasFeedMode() && mode === 'feed' && scopeRoot() === document;
      if (feed) {
        if (k === 'up') step(-1);
        else if (k === 'down') step(1);
        else if (k === 'ok') feedOk();
        else if (k === 'left') B.openMenu();
        else if (k === 'right') enterNav();
        return;
      }
      if (k === 'ok') activate();
      else move(k);
    },
    back: function () {
      var ae = document.activeElement;
      if (isTextInput(ae)) { ae.blur(); return true; }
      if (closeOverlay()) { if (hasFeedMode()) mode = 'feed'; return true; }
      if (hasFeedMode() && mode === 'nav') { mode = 'feed'; clearFocus(); return true; }
      clearFocus();
      return false;
    },
    onRoute: function () {
      addStyle();
      mode = 'feed';
      clearFocus();
      setTimeout(rememberLives, 1500);
      setTimeout(ensureAudio, 1500);
    },
    setSettings: function (s) {
      S = s || {};
      var st = document.getElementById('__tv-style');
      if (st) st.parentNode.removeChild(st);
      addStyle();
    },
    livesJson: function () { rememberLives(); return JSON.stringify(savedLives()); },
    pauseAll: function () {
      var vs = document.querySelectorAll('video');
      for (var i = 0; i < vs.length; i++) { try { vs[i].muted = true; vs[i].pause(); } catch (e) {} }
    },
    resumeAll: function () {
      var v = currentVideo();
      if (v) { v.muted = false; v.play(); }
    },
    streamFromPage: streamFromPage
  };
  window.__tv = api;

  addStyle();
  document.addEventListener('DOMContentLoaded', addStyle);
  // Keep the hidden-cursor style and the remembered Lives list fresh as TikTok re-renders.
  setInterval(function () { addStyle(); if (focused && !document.contains(focused)) focused = null; }, 2000);
  setTimeout(rememberLives, 2000);
  setTimeout(ensureAudio, 2000);
})();

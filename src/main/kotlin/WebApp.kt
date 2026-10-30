// The web version of the remote. The PC server hands this page to any browser (iPhone Safari, etc.)
// at http://<PC IP>:8080/app. It talks to the same server using the same token as the Android app.
//
// NOTE for editing: this page is inside a Kotlin raw string, so never write a dollar sign in it.

val WEB_APP_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-title" content="OneTouch">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="theme-color" content="#0f172a">
<title>One Touch Controller</title>
<style>
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  html, body { height: 100%; margin: 0; }
  body {
    background: #0f172a; color: #e2e8f0;
    font-family: -apple-system, BlinkMacSystemFont, "Hiragino Sans", "Segoe UI", Roboto, sans-serif;
    display: flex; flex-direction: column;
    height: 100vh; height: 100dvh;
    padding: env(safe-area-inset-top) 12px env(safe-area-inset-bottom) 12px;
    -webkit-user-select: none; user-select: none; -webkit-touch-callout: none;
  }
  h1 { font-size: 20px; margin: 10px 0 8px; display: flex; align-items: center; gap: 8px; }
  #dot { width: 10px; height: 10px; border-radius: 50%; background: #64748b; display: inline-block; }
  #dot.ok { background: #22c55e; }
  #dot.bad { background: #ef4444; }
  .row { display: flex; gap: 8px; align-items: center; }
  input[type=password], input[type=text], textarea {
    width: 100%; background: #1e293b; color: #e2e8f0; border: 1px solid #334155; border-radius: 10px;
    padding: 10px 12px; font-size: 16px; font-family: inherit; /* 16px stops iPhone from zooming in */
    -webkit-user-select: text; user-select: text;
  }
  textarea { resize: none; min-height: 90px; }
  button {
    background: #334155; color: #e2e8f0; border: 0; border-radius: 12px; font-size: 15px;
    min-height: 50px; padding: 0 10px; cursor: pointer; touch-action: manipulation;
  }
  button:active { background: #475569; }
  button.primary { background: #2563eb; }
  button.primary:active { background: #1d4ed8; }
  button.danger { background: #7f1d1d; }
  .tabs { display: flex; gap: 6px; margin: 10px 0; }
  .tabs button { flex: 1; min-height: 44px; background: #1e293b; }
  .tabs button.active { background: #2563eb; }
  .view { display: none; flex: 1; min-height: 0; overflow-y: auto; }
  .view.active { display: flex; flex-direction: column; gap: 10px; }
  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
  .keys { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; }
  .keys button { min-height: 46px; font-size: 14px; padding: 0 2px; }
  #padwrap { display: flex; gap: 10px; flex: 1; min-height: 220px; }
  #pad, #scroll {
    background: #1e293b; border: 1px solid #334155; border-radius: 16px;
    display: flex; align-items: center; justify-content: center; color: #64748b; touch-action: none;
  }
  #pad { flex: 1; }
  #scroll { width: 56px; }
  .btnrow { display: flex; gap: 10px; }
  .btnrow button { flex: 1; min-height: 56px; }
  label.check { display: flex; align-items: center; gap: 8px; font-size: 15px; }
  label.check input { width: 22px; height: 22px; }
  #status { padding: 8px 2px 10px; font-size: 14px; color: #94a3b8; min-height: 38px; }
  .hint { font-size: 12px; color: #94a3b8; }
</style>
</head>
<body>
  <h1><span id="dot"></span> One Touch Controller</h1>

  <div class="row">
    <input id="token" type="password" placeholder="Access token" autocomplete="off">
    <button id="saveToken" class="primary" style="min-width:76px">Save</button>
  </div>

  <div class="tabs">
    <button data-tab="cmd" class="active">Commands</button>
    <button data-tab="pad">Touchpad</button>
    <button data-tab="kb">Keyboard</button>
  </div>

  <!-- ===== Commands ===== -->
  <div id="view-cmd" class="view active">
    <div class="grid" id="cmdGrid"></div>
  </div>

  <!-- ===== Touchpad ===== -->
  <div id="view-pad" class="view">
    <div id="padwrap">
      <div id="pad">Touchpad<br>tap = click, hold = right click</div>
      <div id="scroll">Scroll</div>
    </div>
    <div class="btnrow">
      <button id="leftClick">Left click</button>
      <button id="rightClick">Right click</button>
    </div>
  </div>

  <!-- ===== Keyboard ===== -->
  <div id="view-kb" class="view">
    <textarea id="kbText" placeholder="Type here, then send to PC" autocomplete="off"
              autocorrect="off" autocapitalize="off" spellcheck="false"></textarea>
    <label class="check"><input type="checkbox" id="liveSwitch"> Live typing (English)</label>
    <label class="check" id="enterWrap"><input type="checkbox" id="enterAfter"> Press Enter after sending</label>
    <div class="btnrow">
      <button id="sendText" class="primary">Send to PC</button>
      <button id="clearText" style="flex:0 0 100px">Clear</button>
    </div>
    <div class="hint" id="kbHint">Click the place you want to type into on your PC first.</div>
    <div class="keys" id="keyGrid"></div>
  </div>

  <div id="status">Ready</div>

<script>
(function () {
  function by(id) { return document.getElementById(id); }

  var TEXT = 'text/plain; charset=utf-8';
  var SENSITIVITY = 1.8;
  var token = '';
  try { token = localStorage.getItem('otc_token') || ''; } catch (e) {}

  function setStatus(msg) { by('status').textContent = msg; }

  // ---------- talking to the PC server ----------
  function api(path, body, type) {
    return fetch(path, {
      method: 'POST',
      headers: { 'X-Auth-Token': token, 'Content-Type': type || 'application/json' },
      body: body
    }).then(function (r) {
      return r.text().then(function (t) { return { code: r.status, text: t }; });
    }).catch(function () { return { code: -1, text: '' }; });
  }

  function describe(r, okText) {
    if (r.code === 200) return r.text || okText;
    if (r.code === 401) return 'Wrong token';
    if (r.code === 404) return 'Unknown command or key';
    if (r.code === -1) return "Can't reach the PC. Is the server running and the phone on the same network?";
    return 'Server error (' + r.code + '): ' + r.text;
  }

  // green dot = server answers, red = it does not
  function ping() {
    fetch('/', { cache: 'no-store' }).then(function (r) {
      by('dot').className = r.ok ? 'ok' : 'bad';
    }).catch(function () { by('dot').className = 'bad'; });
  }
  ping();
  setInterval(ping, 5000);

  // ---------- token ----------
  by('token').value = token;
  by('saveToken').onclick = function () {
    token = by('token').value.trim();
    try { localStorage.setItem('otc_token', token); } catch (e) {}
    by('token').blur();
    setStatus(token ? 'Token saved on this phone' : 'Token is empty');
  };
  if (!token) setStatus('Type the access token from the PC window, then press Save.');

  // ---------- tabs ----------
  var tabButtons = document.querySelectorAll('.tabs button');
  Array.prototype.forEach.call(tabButtons, function (b) {
    b.onclick = function () {
      Array.prototype.forEach.call(tabButtons, function (x) { x.classList.remove('active'); });
      b.classList.add('active');
      ['cmd', 'pad', 'kb'].forEach(function (name) {
        by('view-' + name).classList.toggle('active', name === b.getAttribute('data-tab'));
      });
    };
  });

  // ---------- Commands ----------
  var commands = [
    ['open_notepad', 'Notepad'], ['open_calculator', 'Calculator'], ['open_explorer', 'File Explorer'],
    ['open_chrome', 'Chrome'], ['open_youtube', 'YouTube'], ['open_google', 'Google'],
    ['run_script_1', 'Run script 1'], ['play_pause', 'Play / Pause'], ['prev_track', 'Previous'],
    ['next_track', 'Next'], ['volume_down', 'Volume -'], ['volume_up', 'Volume +'],
    ['mute', 'Mute'], ['screenshot', 'Screenshot'], ['lock_screen', 'Lock screen'],
    ['shutdown_60', 'Shutdown (60s)', true], ['restart_60', 'Restart (60s)', true],
    ['cancel_shutdown', 'Cancel shutdown']
  ];
  commands.forEach(function (c) {
    var b = document.createElement('button');
    b.textContent = c[1];
    if (c[2]) b.className = 'danger';
    b.onclick = function () {
      if (c[2] && !confirm('Are you sure? ' + c[1])) return;
      setStatus('Sending...');
      api('/run', JSON.stringify({ command: c[0] })).then(function (r) {
        setStatus(describe(r, 'Sent: ' + c[1]));
      });
    };
    by('cmdGrid').appendChild(b);
  });

  // ---------- Touchpad ----------
  function click(button) { api('/mouse/click', JSON.stringify({ button: button })); }
  by('leftClick').onclick = function () { click('left'); };
  by('rightClick').onclick = function () { click('right'); };

  var pad = by('pad');
  var lastX = 0, lastY = 0, startX = 0, startY = 0, startT = 0;
  var moved = false, longFired = false, longTimer = null;
  var pendX = 0, pendY = 0, lastSent = 0;

  // sends the collected movement (at most every 30 ms so the network is not flooded)
  function flush(force) {
    var now = Date.now();
    if (!force && now - lastSent < 30) return;
    var dx = Math.round(pendX), dy = Math.round(pendY);
    if (dx === 0 && dy === 0) return;
    pendX -= dx; pendY -= dy; lastSent = now;
    api('/mouse/move', JSON.stringify({ dx: dx, dy: dy }));
  }

  pad.addEventListener('touchstart', function (e) {
    e.preventDefault();
    var t = e.touches[0];
    lastX = startX = t.clientX; lastY = startY = t.clientY;
    startT = Date.now(); moved = false; longFired = false;
    pendX = 0; pendY = 0;
    clearTimeout(longTimer);
    longTimer = setTimeout(function () {
      if (!moved) { longFired = true; click('right'); if (navigator.vibrate) navigator.vibrate(20); }
    }, 500);
  }, { passive: false });

  pad.addEventListener('touchmove', function (e) {
    e.preventDefault();
    var t = e.touches[0];
    if (!moved) {
      // small wiggles still count as a tap
      if (Math.abs(t.clientX - startX) + Math.abs(t.clientY - startY) < 8) return;
      moved = true; clearTimeout(longTimer);
      lastX = t.clientX; lastY = t.clientY;
      return;
    }
    pendX += (t.clientX - lastX) * SENSITIVITY;
    pendY += (t.clientY - lastY) * SENSITIVITY;
    lastX = t.clientX; lastY = t.clientY;
    flush(false);
  }, { passive: false });

  pad.addEventListener('touchend', function (e) {
    e.preventDefault();
    clearTimeout(longTimer);
    flush(true);
    if (!moved && !longFired && Date.now() - startT < 400) click('left');
  }, { passive: false });

  pad.addEventListener('touchcancel', function () { clearTimeout(longTimer); });

  // scroll strip: drag up/down
  var strip = by('scroll');
  var sLastY = 0, pendScroll = 0;
  strip.addEventListener('touchstart', function (e) {
    e.preventDefault(); sLastY = e.touches[0].clientY; pendScroll = 0;
  }, { passive: false });
  strip.addEventListener('touchmove', function (e) {
    e.preventDefault();
    var y = e.touches[0].clientY;
    pendScroll += -(y - sLastY) / 20;
    sLastY = y;
    var notches = pendScroll < 0 ? Math.ceil(pendScroll) : Math.floor(pendScroll);
    if (notches !== 0) {
      pendScroll -= notches;
      api('/mouse/scroll', JSON.stringify({ amount: notches }));
    }
  }, { passive: false });

  // ---------- Keyboard ----------
  var ta = by('kbText');
  var live = false;
  var prev = '';
  var liveChain = Promise.resolve();   // live changes are sent one after another, in order

  var keyRows = [
    ['Esc', 'esc'], ['Tab', 'tab'], ['Backspace', 'backspace'], ['Delete', 'delete'],
    ['Enter', 'enter'], ['Space', 'space'], ['Home', 'home'], ['End', 'end'],
    ['\u2190', 'left'], ['\u2191', 'up'], ['\u2193', 'down'], ['\u2192', 'right'],
    ['PgUp', 'pageup'], ['PgDn', 'pagedown'], ['Win', 'win'], ['F5', 'f5'],
    ['Ctrl+A', 'ctrl+a'], ['Ctrl+C', 'ctrl+c'], ['Ctrl+V', 'ctrl+v'], ['Ctrl+X', 'ctrl+x'],
    ['Ctrl+Z', 'ctrl+z'], ['Ctrl+S', 'ctrl+s'], ['Alt+Tab', 'alt+tab'], ['\u534a/\u5168', 'ime']
  ];
  keyRows.forEach(function (k) {
    var b = document.createElement('button');
    b.textContent = k[0];
    b.onclick = function () { api('/keyboard/key', JSON.stringify({ key: k[1] })); };
    by('keyGrid').appendChild(b);
  });

  // normal mode: send everything at once (works for Japanese too)
  by('sendText').onclick = function () {
    var text = ta.value;
    if (!text) return;
    setStatus('Sending...');
    api('/keyboard/type', text, TEXT).then(function (r) {
      setStatus(describe(r, 'Typed'));
      if (r.code === 200) {
        ta.value = '';
        if (by('enterAfter').checked) api('/keyboard/key', JSON.stringify({ key: 'enter' }));
      }
    });
  };
  by('clearText').onclick = function () { ta.value = ''; prev = ''; };

  // live mode: work out what changed and send only that
  function liveSync() {
    var cur = ta.value;
    var p = 0, m = Math.min(prev.length, cur.length);
    while (p < m && prev.charAt(p) === cur.charAt(p)) p++;
    var backspaces = Array.from(prev.slice(p)).length;
    var added = cur.slice(p);
    prev = cur;
    if (backspaces === 0 && added === '') return;
    liveChain = liveChain.then(function () {
      return api('/keyboard/live', backspaces + '\n' + added, TEXT).then(function (r) {
        if (r.code !== 200) setStatus('Live typing failed: ' + describe(r, ''));
      });
    });
  }
  ta.addEventListener('input', function (e) { if (live && !e.isComposing) liveSync(); });
  ta.addEventListener('compositionend', function () { if (live) liveSync(); });

  by('liveSwitch').onchange = function () {
    live = this.checked;
    ta.value = ''; prev = '';
    liveChain = Promise.resolve();
    by('sendText').style.display = live ? 'none' : '';
    by('enterWrap').style.display = live ? 'none' : '';
    by('clearText').textContent = live ? 'Clear box' : 'Clear';
    by('kbHint').textContent = live
      ? 'Live: everything you type goes straight to the PC. Set the PC to direct input (English) first.'
      : 'Click the place you want to type into on your PC first.';
    setStatus(live ? 'Live typing on (English)' : 'Live typing off');
    if (live) ta.focus();
  };
})();
</script>
</body>
</html>
"""

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
<meta name="theme-color" content="#0e1420">
<title>One Touch Controller</title>
<style>
  :root {
    --ink: #0e1420; --panel: #161e2d; --key: #212b3f; --key-hi: #2b3852; --line: #2c3850;
    --text: #e8edf7; --muted: #8e9bb3; --accent: #3dd6c6; --accent-ink: #052521; --warn: #ffb454; --danger: #ff7a7a;
    --accent-rgb: 61, 214, 198; --accent-deep: #1c7a70; --shadow: #080c14;
    --r-key: 14px; --r-panel: 20px;
  }
  /* light theme: only the colours change (chosen in Settings) */
  html[data-theme=light] {
    --ink: #f3f5f9; --panel: #ffffff; --key: #e3e8f1; --key-hi: #eef2f8; --line: #d3dae6;
    --text: #131a26; --muted: #5d6b85; --danger: #d83a3a; --shadow: #c3ccdb;
  }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  html { background: var(--ink); }
  html, body { height: 100%; margin: 0; }
  body {
    background: radial-gradient(120% 60% at 50% -10%, rgba(var(--accent-rgb), .10), transparent 60%), var(--ink);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Hiragino Sans", "Segoe UI Variable Text", "Segoe UI", Roboto, sans-serif;
    display: flex; flex-direction: column;
    height: 100vh; height: 100dvh;
    padding: env(safe-area-inset-top) 14px env(safe-area-inset-bottom) 14px;
    max-width: 1200px; margin: 0 auto;
    -webkit-user-select: none; user-select: none; -webkit-touch-callout: none;
  }
  h1 { font-size: 19px; font-weight: 700; letter-spacing: -.01em; margin: 14px 2px 12px; display: flex; align-items: center; gap: 10px; }
  h1 .mark { width: 30px; height: 30px; border-radius: 9px; background: var(--accent); color: var(--accent-ink);
             display: flex; align-items: center; justify-content: center; }
  h1 .mark svg { width: 18px; height: 18px; }
  h1 .grow { flex: 1; }
  #dot { width: 10px; height: 10px; border-radius: 50%; background: #5d6b85; display: inline-block; margin-right: 4px;
         box-shadow: 0 0 0 4px rgba(93, 107, 133, .18); }
  #dot.ok { background: var(--accent); box-shadow: 0 0 0 4px rgba(var(--accent-rgb), .18); }
  #dot.bad { background: var(--danger); box-shadow: 0 0 0 4px rgba(255, 122, 122, .18); }
  .row { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; }
  input[type=password], input[type=text], textarea {
    width: 100%; background: var(--panel); color: var(--text); border: 1px solid var(--line); border-radius: var(--r-key);
    padding: 12px 14px; font-size: 16px; font-family: inherit; /* 16px stops iPhone from zooming in */
    -webkit-user-select: text; user-select: text; outline: none;
  }
  input:focus, textarea:focus { border-color: var(--accent); box-shadow: 0 0 0 3px rgba(var(--accent-rgb), .18); }
  ::placeholder { color: #66748e; }
  textarea { resize: none; min-height: 96px; line-height: 1.45; }

  /* every button is a "key": a raised surface that sinks when pressed */
  button {
    background: linear-gradient(180deg, var(--key-hi), var(--key)); color: var(--text); border: 0; border-radius: var(--r-key);
    font-size: 15px; font-weight: 600; font-family: inherit; min-height: 52px; padding: 0 10px; cursor: pointer;
    touch-action: manipulation; box-shadow: inset 0 1px 0 rgba(255, 255, 255, .07), 0 3px 0 var(--shadow);
    transition: transform .06s ease, box-shadow .06s ease;
  }
  button:active { transform: translateY(3px); box-shadow: inset 0 1px 0 rgba(255, 255, 255, .04), 0 0 0 var(--shadow); }
  button:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
  button.primary { background: var(--accent); color: var(--accent-ink); box-shadow: inset 0 1px 0 rgba(255, 255, 255, .35), 0 3px 0 var(--accent-deep); }
  button.primary:active { box-shadow: 0 0 0 var(--accent-deep); }
  button.danger { background: rgba(255, 122, 122, .10); color: var(--danger); box-shadow: inset 0 0 0 1px rgba(255, 122, 122, .45), 0 3px 0 var(--shadow); }
  button.danger:active { box-shadow: inset 0 0 0 1px rgba(255, 122, 122, .45); }

  /* the five sections sit at the bottom, where the thumb is */
  .tabs { order: 9; display: flex; gap: 2px; margin: 0 -4px 8px; padding: 6px; background: var(--panel);
          border: 1px solid var(--line); border-radius: var(--r-panel); }
  .tabs button { flex: 1; min-height: 54px; background: none; box-shadow: none; color: var(--muted); font-size: 11px; font-weight: 600;
                 padding: 4px 0 2px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px; border-radius: 14px; }
  .tabs button svg { width: 22px; height: 22px; }
  .tabs button:active { transform: none; box-shadow: none; }
  .tabs button.active { background: rgba(var(--accent-rgb), .13); color: var(--accent); }
  #status { order: 8; }

  .view { display: none; flex: 1; min-height: 0; overflow-y: auto; padding: 4px 0 6px; }
  .view.active { display: flex; flex-direction: column; gap: 12px; }
  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; padding-bottom: 4px; }
  .grid .group { grid-column: 1 / -1; font-size: 13px; font-weight: 600; color: var(--muted); margin: 8px 2px -2px; }
  .grid .group:first-child { margin-top: 0; }
  .grid button { min-height: 58px; }
  @media (min-width: 700px) { .grid { grid-template-columns: repeat(4, 1fr); } .keys { grid-template-columns: repeat(8, 1fr); } }

  #screenRow { display: flex; gap: 8px; flex: 1; min-height: 220px; }
  #screenBox { flex: 1; position: relative; background: #000; border-radius: var(--r-key); overflow: hidden; touch-action: none;
               box-shadow: 0 0 0 1px var(--line); }
  #screenImg { position: absolute; left: 0; top: 0; width: 100%; height: 100%; object-fit: contain; display: none; }
  #screenMsg { position: absolute; left: 0; top: 0; width: 100%; height: 100%; display: flex; align-items: center;
               justify-content: center; text-align: center; padding: 12px; color: var(--muted); font-size: 14px; line-height: 1.5; }
  #screenScroll { width: 44px; background: var(--panel); border: 1px solid var(--line); border-radius: var(--r-key); display: flex;
                  align-items: center; justify-content: center; color: var(--muted); font-size: 12px; touch-action: none; }
  #screenControls { display: flex; flex-direction: column; gap: 10px; }
  #screenMenu { display: none; }
  #captureBox { position: fixed; left: 0; bottom: 0; width: 1px; height: 1px; opacity: 0; border: 0; padding: 0;
                font-size: 16px; pointer-events: none; }
  /* full screen ("immersive") mode: only the PC picture is shown; a small menu button opens the controls */
  body.immersive { padding: 0; max-width: none; background: #000; }
  body.immersive h1, body.immersive .row, body.immersive .tabs, body.immersive #status { display: none; }
  body.immersive #view-screen { position: fixed; left: 0; top: 0; width: 100vw; height: 100vh; height: 100dvh; z-index: 50;
        background: #000; gap: 0; overflow: hidden; padding: env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom) env(safe-area-inset-left); }
  body.immersive #screenRow { gap: 0; min-height: 0; }
  body.immersive #screenBox { border-radius: 0; box-shadow: none; }
  body.immersive #screenScroll { width: 30px; border-radius: 0; border: 0; opacity: .6; font-size: 10px; writing-mode: vertical-rl; }
  body.immersive #screenControls { display: none; position: fixed; right: 8px; top: 58px; width: min(380px, 92vw); max-height: 80vh;
        overflow-y: auto; z-index: 70; background: var(--panel); border: 1px solid var(--line); border-radius: var(--r-panel); padding: 12px; }
  body.immersive.panel #screenControls { display: flex; }
  body.immersive #screenMenu { display: block; position: fixed; right: 8px; top: 8px; z-index: 80; width: 44px; min-height: 44px;
        border-radius: 22px; opacity: .75; padding: 0; font-size: 18px; }

  /* a row of choices where one is switched on */
  .seg { display: flex; gap: 4px; padding: 4px; background: var(--panel); border: 1px solid var(--line); border-radius: var(--r-key); }
  .seg button { flex: 1; min-height: 40px; font-size: 14px; padding: 0 2px; background: none; box-shadow: none; color: var(--muted); border-radius: 10px; }
  .seg button:active { transform: none; background: var(--key); }
  .seg button.on { background: var(--accent); color: var(--accent-ink); }
  .stack { display: grid; grid-template-columns: 1fr; gap: 10px; }
  .stack button { min-height: 64px; font-size: 17px; text-align: left; padding: 0 18px; }
  .keys { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; padding-bottom: 4px; }
  .keys button { min-height: 46px; font-size: 13px; padding: 0 2px; border-radius: 10px; }
  #padwrap { display: flex; gap: 10px; flex: 1; min-height: 220px; }
  #pad, #scroll {
    background-color: var(--panel); border: 1px solid var(--line); border-radius: var(--r-panel);
    display: flex; align-items: center; justify-content: center; color: var(--muted); touch-action: none; font-size: 14px; line-height: 1.6;
  }
  /* the dotted surface is the touchpad's "texture" */
  #pad { flex: 1; background-image: radial-gradient(rgba(142, 155, 179, .22) 1px, transparent 1.5px); background-size: 18px 18px; }
  #pad b { display: block; color: var(--text); font-size: 16px; margin-bottom: 2px; }
  #padInfo { display: inline-block; margin-top: 10px; padding: 4px 12px; border-radius: 999px; background: var(--key); color: var(--accent); font-size: 12px; font-weight: 600; }
  #scroll { width: 56px; font-size: 12px; writing-mode: vertical-rl; letter-spacing: .08em;
            background-image: repeating-linear-gradient(180deg, transparent 0 13px, rgba(142, 155, 179, .18) 13px 14px); }
  .btnrow { display: flex; gap: 10px; }
  .btnrow button { flex: 1; min-height: 56px; }
  label.check { display: flex; align-items: center; gap: 10px; font-size: 15px; padding: 2px; }
  label.check input { width: 22px; height: 22px; accent-color: var(--accent); }
  #status { margin: 6px 0 8px; padding: 9px 14px; font-size: 13px; color: var(--muted); min-height: 36px; line-height: 1.4;
            background: var(--panel); border-radius: 12px; }
  .hint { font-size: 12.5px; color: var(--muted); line-height: 1.5; }
  /* Guard mode button: filled with the accent colour while Guard mode is on */
  #guardBtn.on { background: var(--accent); color: var(--accent-ink); box-shadow: inset 0 1px 0 rgba(255, 255, 255, .35), 0 3px 0 var(--accent-deep); }
  /* settings */
  #setBtn { width: 40px; min-height: 40px; padding: 0; background: none; box-shadow: none; color: var(--muted); display: flex; align-items: center; justify-content: center; }
  #setBtn svg { width: 22px; height: 22px; }
  #setBtn:active { transform: none; color: var(--accent); }
  #msgSheet { position: fixed; left: 0; top: 0; width: 100%; height: 100%; z-index: 90; background: rgba(0, 0, 0, .5);
              display: flex; align-items: flex-end; justify-content: center; }
  #msgSheet[hidden] { display: none; }
  #msgSheet .sheet { width: min(480px, 100%); background: var(--ink); border: 1px solid var(--line); border-radius: 24px 24px 0 0;
                     padding: 18px 16px calc(18px + env(safe-area-inset-bottom)); display: flex; flex-direction: column; gap: 10px; }
  #msgSheet h2 { font-size: 18px; margin: 0 2px 2px; }
  #msgSheet .lbl { font-size: 13px; color: var(--muted); margin: 0 2px; line-height: 1.4; }
  #msgSheet textarea { min-height: 80px; }
  #msgPresets { display: flex; flex-wrap: wrap; gap: 8px; }
  #msgPresets button { min-height: 38px; font-size: 13px; padding: 0 12px; border-radius: 999px; box-shadow: none; }
  #msgPresets button:active { transform: none; }
  #settings { position: fixed; left: 0; top: 0; width: 100%; height: 100%; z-index: 90; background: rgba(0, 0, 0, .5);
              display: flex; align-items: flex-end; justify-content: center; }
  #settings[hidden] { display: none; }
  #settings .sheet { width: min(480px, 100%); background: var(--ink); border: 1px solid var(--line); border-radius: 24px 24px 0 0;
                     padding: 18px 16px calc(18px + env(safe-area-inset-bottom)); display: flex; flex-direction: column; gap: 10px; }
  #settings h2 { font-size: 18px; margin: 0 2px 2px; }
  #settings .lbl { font-size: 13px; font-weight: 600; color: var(--muted); margin: 6px 2px -4px; }
  #accentRow { display: flex; gap: 12px; padding: 4px 2px; }
  #accentRow button { width: 44px; min-height: 44px; border-radius: 50%; padding: 0; box-shadow: none; border: 3px solid transparent; background-clip: padding-box; }
  #accentRow button.on { border-color: var(--text); }
  #accentRow button:active { transform: none; }
  #setDone { margin-top: 8px; }
  body.immersive #settings { display: none; }
  @media (prefers-reduced-motion: reduce) { button { transition: none; } }
</style>
</head>
<body>
  <h1><span class="mark"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v8"/><path d="M6.3 7.2a8 8 0 1 0 11.4 0"/></svg></span>One Touch Controller<span class="grow"></span><button id="setBtn" aria-label="Settings"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 7h10M18 7h2M4 17h2M10 17h10"/><circle cx="16" cy="7" r="2"/><circle cx="8" cy="17" r="2"/></svg></button><span id="dot" title="Connection to the PC"></span></h1>

  <div class="row">
    <input id="token" type="password" placeholder="Access token" autocomplete="off">
    <button id="saveToken" class="primary" style="min-width:76px">Save</button>
  </div>

  <div class="tabs">
    <button data-tab="cmd" class="active"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="4" width="7" height="7" rx="2"/><rect x="13" y="4" width="7" height="7" rx="2"/><rect x="4" y="13" width="7" height="7" rx="2"/><rect x="13" y="13" width="7" height="7" rx="2"/></svg>Commands</button>
    <button data-tab="pad"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="3" width="16" height="18" rx="3"/><path d="M4 15h16"/><path d="M12 15v6"/></svg>Touchpad</button>
    <button data-tab="kb"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2.5" y="6" width="19" height="12" rx="2.5"/><path d="M6.5 10h.01M10 10h.01M14 10h.01M17.5 10h.01M8 14h8"/></svg>Keyboard</button>
    <button data-tab="routines"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 3 5 13.5h6L10 21l8-10.5h-6L13 3z"/></svg>Routines</button>
    <button data-tab="screen"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/></svg>Screen</button>
  </div>

  <!-- ===== Commands ===== -->
  <div id="view-cmd" class="view active">
    <div class="grid" id="cmdGrid"></div>
  </div>

  <!-- ===== Touchpad ===== -->
  <div id="view-pad" class="view">
    <div id="padwrap">
      <div id="pad"><div style="text-align:center"><b>Touchpad</b>Tap to click, hold to right-click<br><span id="padInfo">Standard connection (slower)</span></div></div>
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

  <!-- ===== Routines ===== -->
  <div id="view-routines" class="view">
    <button id="reloadRoutines">Refresh list</button>
    <div class="stack" id="routineList"></div>
    <div class="hint" id="routineHint"></div>
  </div>

  <!-- ===== Screen ===== -->
  <div id="view-screen" class="view">
    <div id="screenRow">
      <div id="screenBox"><img id="screenImg" alt=""><div id="screenMsg">Waiting for the PC screen...</div></div>
      <div id="screenScroll">Scroll</div>
    </div>
    <div id="screenControls">
      <div class="seg" id="qualityRow">
        <button data-q="0">Low</button><button data-q="1" class="on">Medium</button><button data-q="2">High</button><button data-q="3">Max</button>
      </div>
      <div class="seg" id="zoomRow">
        <button data-z="100" class="on">1x</button><button data-z="200">2x</button><button data-z="300">3x</button>
        <button id="panLeft">&#8592;</button><button id="panUp">&#8593;</button><button id="panDown">&#8595;</button><button id="panRight">&#8594;</button>
      </div>
      <div class="seg">
        <button id="fullBtn">Full screen</button><button id="kbBtn">Keyboard</button>
      </div>
      <label class="check"><input type="checkbox" id="directType"> Type with this device's keyboard</label>
      <div class="hint">Tap or click = click, double = double click, hold or right button = right click, drag = drag.
        Scroll with the strip on the right (or the mouse wheel). With "Type with this device's keyboard" on, everything you type
        goes to the PC; Ctrl+V types the text from this device's clipboard.</div>
    </div>
    <button id="screenMenu">&#9776;</button>
    <textarea id="captureBox" autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false" aria-label="keyboard input"></textarea>
  </div>

  <div id="status">Ready</div>

  <!-- ===== A message for the PC screen ===== -->
  <div id="msgSheet" class="sheetWrap" hidden>
    <div class="sheet">
      <h2>Message to PC</h2>
      <div class="lbl">Shown on the PC screen (on the lock screen while Guard mode is on)</div>
      <textarea id="msgText" maxlength="200" placeholder="Type a message for the PC"></textarea>
      <div id="msgPresets"></div>
      <div class="btnrow"><button id="msgCancel">Cancel</button><button id="msgSend" class="primary">Send</button></div>
    </div>
  </div>

  <!-- ===== Settings (theme, accent colour, language): saved in this browser ===== -->
  <div id="settings" hidden>
    <div class="sheet">
      <h2>Settings</h2>
      <div class="lbl">Theme</div>
      <div class="seg" id="themeRow"><button data-v="dark">Dark</button><button data-v="light">Light</button></div>
      <div class="lbl">Accent color</div>
      <div id="accentRow"></div>
      <div class="lbl">Language</div>
      <div class="seg" id="langRow"><button data-v="en">English</button><button data-v="ja">日本語</button><button data-v="zh">中文</button></div>
      <button id="setDone" class="primary">Done</button>
    </div>
  </div>

<script>
// ================= Settings: language, theme, accent colour (saved in this browser) =================
var otcStore = {
  get: function (k, d) { try { return localStorage.getItem(k) || d; } catch (e) { return d; } },
  set: function (k, v) { try { localStorage.setItem(k, v); } catch (e) { } }
};
var otcLang = otcStore.get('otc_lang', '');
if (!otcLang) {                                  // first visit: use the language of the phone or browser
  var navLang = (navigator.language || 'en').toLowerCase();
  otcLang = navLang.indexOf('ja') === 0 ? 'ja' : (navLang.indexOf('zh') === 0 ? 'zh' : 'en');
}
var otcTheme = otcStore.get('otc_theme', 'dark');
var otcAccent = otcStore.get('otc_accent', 'teal');

// English text: [Japanese, Chinese]. A text that is not in this list stays English.
var otcWords = {
    "Access token": ["アクセストークン", "访问令牌"],
    "Save": ["保存", "保存"],
    "Commands": ["コマンド", "命令"],
    "Touchpad": ["タッチパッド", "触控板"],
    "Keyboard": ["キーボード", "键盘"],
    "Routines": ["ルーティン", "例程"],
    "Screen": ["画面", "屏幕"],
    "Tap to click, hold to right-click": ["タップでクリック、長押しで右クリック", "轻点为单击，长按为右键单击"],
    "Scroll": ["スクロール", "滚动"],
    "Left click": ["左クリック", "左键单击"],
    "Right click": ["右クリック", "右键单击"],
    "Type here, then send to PC": ["ここに入力してPCに送信", "在此输入，然后发送到电脑"],
    "Live typing (English)": ["ライブ入力（英語）", "实时输入（英文）"],
    "Press Enter after sending": ["送信後にEnterを押す", "发送后按 Enter"],
    "Send to PC": ["PCに送信", "发送到电脑"],
    "Clear": ["クリア", "清除"],
    "Clear box": ["入力欄をクリア", "清空输入框"],
    "Click the place you want to type into on your PC first.": ["先にPC上で入力したい場所をクリックしてください。", "请先在电脑上点击要输入的位置。"],
    "Live: everything you type goes straight to the PC. Set the PC to direct input (English) first.": ["ライブ：入力した文字がそのままPCに送られます。先にPCを直接入力（英語）にしてください。", "实时：输入的内容会直接发送到电脑。请先把电脑切换为英文输入。"],
    "Refresh list": ["一覧を更新", "刷新列表"],
    "Waiting for the PC screen...": ["PCの画面を待っています...", "正在等待电脑屏幕..."],
    "Low": ["低", "低"],
    "Medium": ["中", "中"],
    "High": ["高", "高"],
    "Max": ["最高", "最高"],
    "Full screen": ["全画面", "全屏"],
    "Exit full screen": ["全画面を終了", "退出全屏"],
    "Type with this device's keyboard": ["この端末のキーボードで入力する", "使用此设备的键盘输入"],
    "Tap or click = click, double = double click, hold or right button = right click, drag = drag. Scroll with the strip on the right (or the mouse wheel). With \"Type with this device's keyboard\" on, everything you type goes to the PC; Ctrl+V types the text from this device's clipboard.": ["タップまたはクリック＝クリック、2回＝ダブルクリック、長押しまたは右ボタン＝右クリック、ドラッグ＝ドラッグ。スクロールは右側の帯（またはマウスホイール）で行います。「この端末のキーボードで入力する」をオンにすると、入力した文字がすべてPCに送られます。Ctrl+Vでこの端末のクリップボードの文字を入力します。", "轻点或单击＝单击，两次＝双击，长按或右键＝右键单击，拖动＝拖动。用右侧的滚动条（或鼠标滚轮）滚动。打开“使用此设备的键盘输入”后，输入的内容都会发送到电脑；Ctrl+V 会输入此设备剪贴板中的文字。"],
    "Ready": ["準備完了", "就绪"],
    "Wrong token": ["トークンが違います", "令牌错误"],
    "Blocked by the PC": ["PCによってブロックされました", "已被电脑拦截"],
    "Too many wrong tokens. Wait a few minutes.": ["トークンの間違いが多すぎます。数分お待ちください。", "令牌错误次数过多，请等待几分钟。"],
    "Unknown command or key": ["不明なコマンドまたはキーです", "未知的命令或按键"],
    "Server error (": ["サーバーエラー (", "服务器错误 ("],
    "Can't reach the PC. Is the server running and the phone on the same network?": ["PCに接続できません。サーバーは起動していますか？スマホは同じネットワークにありますか？", "无法连接电脑。服务器是否已启动？手机是否在同一网络？"],
    "Token saved on this phone": ["トークンをこの端末に保存しました", "令牌已保存到此设备"],
    "Token is empty": ["トークンが空です", "令牌为空"],
    "Type the access token from the PC window, then press Save.": ["PCのウィンドウに表示されているアクセストークンを入力して、保存を押してください。", "请输入电脑窗口中显示的访问令牌，然后按保存。"],
    "No routines yet. On the PC press \"Edit file\" in the One Touch Controller window, write a routine, press \"Reload\" there, then Refresh here.": ["ルーティンはまだありません。PCのOne Touch Controllerウィンドウで「Edit file」を押してルーティンを書き、「Reload」を押してから、ここで更新してください。", "还没有例程。请在电脑的 One Touch Controller 窗口中按“Edit file”编写例程，再按“Reload”，然后在这里刷新。"],
    "No routines on the PC yet": ["PCにルーティンはまだありません", "电脑上还没有例程"],
    " routine(s)": [" 件のルーティン", " 个例程"],
    "Run \"": ["\"", "运行 \""],
    "\"? It contains shutdown, restart or script steps.": ["\" を実行しますか？シャットダウン、再起動、またはスクリプトの手順が含まれています。", "\"？它包含关机、重启或脚本步骤。"],
    "Sending...": ["送信中...", "正在发送..."],
    "Started": ["開始しました", "已开始"],
    "Open on the PC": ["PCで開く", "在电脑上打开"],
    "Notepad": ["メモ帳", "记事本"],
    "Calculator": ["電卓", "计算器"],
    "File Explorer": ["エクスプローラー", "文件资源管理器"],
    "Run script 1": ["スクリプト1を実行", "运行脚本 1"],
    "Media and sound": ["メディアと音量", "媒体和声音"],
    "Play / Pause": ["再生 / 一時停止", "播放 / 暂停"],
    "Previous": ["前へ", "上一首"],
    "Next": ["次へ", "下一首"],
    "Volume -": ["音量 -", "音量 -"],
    "Volume +": ["音量 +", "音量 +"],
    "Mute": ["ミュート", "静音"],
    "System": ["システム", "系统"],
    "Screenshot": ["スクリーンショット", "截图"],
    "Lock screen": ["画面をロック", "锁定屏幕"],
    "Shutdown (60s)": ["シャットダウン (60秒)", "关机 (60秒)"],
    "Restart (60s)": ["再起動 (60秒)", "重启 (60秒)"],
    "Cancel shutdown": ["シャットダウンを取り消す", "取消关机"],
    "Are you sure? ": ["実行しますか？ ", "确定吗？ "],
    "Sent: ": ["送信しました: ", "已发送: "],
    "Live connection: ": ["ライブ接続: ", "实时连接: "],
    "Standard connection (slower)": ["標準接続（遅め）", "标准连接（较慢）"],
    "Screen viewing is turned off on the PC (switch in the PC window).": ["PC側で画面表示がオフになっています（PCのウィンドウのスイッチ）。", "电脑端已关闭屏幕查看（电脑窗口中的开关）。"],
    "The PC could not capture its screen: ": ["PCが画面を取得できませんでした: ", "电脑无法截取屏幕: "],
    "Typed": ["入力しました", "已输入"],
    "Live typing failed: ": ["ライブ入力に失敗しました: ", "实时输入失败: "],
    "Live typing on (English)": ["ライブ入力オン（英語）", "实时输入已开启（英文）"],
    "Live typing off": ["ライブ入力オフ", "实时输入已关闭"],
    "Settings": ["設定", "设置"],
    "Theme": ["テーマ", "主题"],
    "Dark": ["ダーク", "深色"],
    "Light": ["ライト", "浅色"],
    "Accent color": ["アクセントカラー", "主题色"],
    "Language": ["言語", "语言"],
    "Done": ["完了", "完成"],
    "Security": ["セキュリティ", "安全"],
    "Guard mode: off": ["ガードモード：オフ", "守护模式：关闭"],
    "Guard mode: on": ["ガードモード：オン", "守护模式：开启"],
    "Lock with Windows": ["Windowsでロック", "用 Windows 锁定"],
    "Guard mode on": ["ガードモードをオンにしました", "守护模式已开启"],
    "Guard mode off": ["ガードモードをオフにしました", "守护模式已关闭"],
    "Set a guard password in the PC window first": ["先にPCのウィンドウでガードパスワードを設定してください", "请先在电脑窗口中设置守护密码"],
    "Guard mode works on Windows only": ["ガードモードはWindowsでのみ使えます", "守护模式只能在 Windows 上使用"],
    "Guard mode could not start": ["ガードモードを開始できませんでした", "无法启动守护模式"],
    "Guard mode on. Wrong passwords typed at the PC: ": ["ガードモードはオンです。PCで入力された間違ったパスワード: ", "守护模式已开启。在电脑上输错密码的次数: "],
    "Turn on Guard mode? The PC's own mouse and keyboard stop working until someone types the Guard password at the PC or you turn it off here.": ["ガードモードをオンにしますか？PCでガードパスワードが入力されるか、ここでオフにするまで、PC本体のマウスとキーボードは使えなくなります。", "开启守护模式吗？在电脑上输入守护密码或在这里关闭之前，电脑本身的鼠标和键盘将无法使用。"],
    "Lock with Windows? Your phone cannot see or control the PC until someone signs in at the PC.": ["Windowsでロックしますか？PCでサインインするまで、スマホから画面を見ることも操作することもできなくなります。", "用 Windows 锁定吗？在电脑上登录之前，手机将无法查看或控制电脑。"],
    "Guard mode": ["ガードモード", "守护模式"],
    "Windows locked the PC, so Guard mode turned itself off.": ["Windowsがロックされたため、ガードモードは自動でオフになりました。", "Windows 已锁定电脑，守护模式已自动关闭。"],
    "Finished: ": ["完了しました: ", "已完成: "],
    "Finished with problems: ": ["問題がありましたが完了しました: ", "已完成但有问题: "],
    " step(s) failed": [" 件の手順が失敗", " 个步骤失败"],
    "Message to PC": ["PCにメッセージ", "发送消息到电脑"],
    "Shown on the PC screen (on the lock screen while Guard mode is on)": ["PCの画面に表示されます（ガードモード中はロック画面に表示）", "将显示在电脑屏幕上（守护模式下显示在锁定画面上）"],
    "Type a message for the PC": ["PCに表示するメッセージ", "要在电脑上显示的消息"],
    "Send": ["送信", "发送"],
    "This PC is in use. Please do not touch it.": ["このPCは使用中です。触らないでください。", "此电脑正在使用中，请勿触碰。"],
    "I will be right back.": ["すぐに戻ります。", "我马上回来。"],
    "Please wait for the owner.": ["持ち主が戻るまでお待ちください。", "请等待主人回来。"],
    "Message shown on the PC": ["PCにメッセージを表示しました", "已在电脑上显示消息"],
    "The message is empty": ["メッセージが空です", "消息为空"]
  };
function t(s) {
  if (otcLang === 'en') return s;
  var w = otcWords[s];
  return w ? w[otcLang === 'ja' ? 0 : 1] : s;
}

// accent colours: [colour, the same as r,g,b, text on it, darker edge] for the dark and the light theme
var otcAccents = {
  teal:   [['#3dd6c6', '61, 214, 198', '#052521', '#1c7a70'], ['#0e9488', '14, 148, 136', '#ffffff', '#0a6b62']],
  blue:   [['#6aa8ff', '106, 168, 255', '#06183a', '#2f5fa8'], ['#2563eb', '37, 99, 235', '#ffffff', '#1a47ad']],
  violet: [['#a78bfa', '167, 139, 250', '#1c0f45', '#5e45b3'], ['#7c3aed', '124, 58, 237', '#ffffff', '#5826b0']],
  orange: [['#ffa24c', '255, 162, 76', '#3a1c00', '#a8611f'], ['#d9650b', '217, 101, 11', '#ffffff', '#a04a06']],
  pink:   [['#ff7eb6', '255, 126, 182', '#3d0a22', '#a8477a'], ['#db2777', '219, 39, 119', '#ffffff', '#a31a57']]
};
function otcApplyLook() {
  var root = document.documentElement, light = otcTheme === 'light';
  var c = (otcAccents[otcAccent] || otcAccents.teal)[light ? 1 : 0];
  root.setAttribute('data-theme', light ? 'light' : 'dark');
  root.style.setProperty('--accent', c[0]);
  root.style.setProperty('--accent-rgb', c[1]);
  root.style.setProperty('--accent-ink', c[2]);
  root.style.setProperty('--accent-deep', c[3]);
  var meta = document.querySelector('meta[name=theme-color]');
  if (meta) meta.setAttribute('content', light ? '#f3f5f9' : '#0e1420');
  var marks = document.querySelectorAll('#themeRow button, #accentRow button, #langRow button');
  Array.prototype.forEach.call(marks, function (b) {
    var v = b.getAttribute('data-v');
    b.classList.toggle('on', v === otcTheme || v === otcAccent || v === otcLang);
  });
}

// Translates the texts that are written in the page itself (buttons, hints, placeholders)
function otcTranslatePage() {
  document.documentElement.lang = otcLang;
  if (otcLang === 'en') return;
  var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null), node, todo = [];
  while ((node = walker.nextNode())) todo.push(node);
  todo.forEach(function (n) {
    var tag = n.parentNode && n.parentNode.nodeName;
    if (tag === 'SCRIPT' || tag === 'STYLE') return;
    var key = n.nodeValue.replace(/\s+/g, ' ').trim();
    if (key && otcWords[key]) n.nodeValue = (/^\s/.test(n.nodeValue) ? ' ' : '') + t(key);
  });
  Array.prototype.forEach.call(document.querySelectorAll('[placeholder]'), function (el) {
    el.setAttribute('placeholder', t(el.getAttribute('placeholder')));
  });
}

(function () {
  var panel = document.getElementById('settings');
  Object.keys(otcAccents).forEach(function (name) {
    var b = document.createElement('button');
    b.setAttribute('data-v', name);
    b.setAttribute('aria-label', name);
    b.style.background = otcAccents[name][0][0];
    b.onclick = function () { otcAccent = name; otcStore.set('otc_accent', name); otcApplyLook(); };
    document.getElementById('accentRow').appendChild(b);
  });
  Array.prototype.forEach.call(document.querySelectorAll('#themeRow button'), function (b) {
    b.onclick = function () { otcTheme = b.getAttribute('data-v'); otcStore.set('otc_theme', otcTheme); otcApplyLook(); };
  });
  Array.prototype.forEach.call(document.querySelectorAll('#langRow button'), function (b) {
    b.onclick = function () {
      if (b.getAttribute('data-v') === otcLang) return;
      otcStore.set('otc_lang', b.getAttribute('data-v'));
      location.reload();                          // the page is built again in the new language
    };
  });
  document.getElementById('setBtn').onclick = function () { panel.hidden = false; };
  document.getElementById('setDone').onclick = function () { panel.hidden = true; };
  panel.onclick = function (e) { if (e.target === panel) panel.hidden = true; };
  otcTranslatePage();
  otcApplyLook();
})();

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
    if (r.code === 401) return t('Wrong token');
    if (r.code === 403) return r.text || t('Blocked by the PC');
    if (r.code === 429) return r.text || t('Too many wrong tokens. Wait a few minutes.');
    if (r.code === 404) return t('Unknown command or key');
    if (r.code === -1) return t("Can't reach the PC. Is the server running and the phone on the same network?");
    return t('Server error (') + r.code + '): ' + r.text;
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
    if (ws) { var old = ws; ws = null; wsReady = false; try { old.close(); } catch (e) {} }   // reconnect with the new token right away
    wsRetryAt = 0; latency = -1; showLatency();
    by('token').blur();
    setStatus(token ? t('Token saved on this phone') : t('Token is empty'));
    refreshGuard();
  };
  if (!token) setStatus(t('Type the access token from the PC window, then press Save.'));

  // ---------- tabs ----------
  var tabButtons = document.querySelectorAll('.tabs button');
  Array.prototype.forEach.call(tabButtons, function (b) {
    b.onclick = function () {
      Array.prototype.forEach.call(tabButtons, function (x) { x.classList.remove('active'); });
      b.classList.add('active');
      ['cmd', 'pad', 'kb', 'routines', 'screen'].forEach(function (name) {
        by('view-' + name).classList.toggle('active', name === b.getAttribute('data-tab'));
      });
      var which = b.getAttribute('data-tab');
      if (which === 'pad') wsConnect();
      if (which === 'routines') loadRoutines();
      if (which === 'screen') screenOpen(); else screenClose();
    };
  });

  // ---------- Routines ----------
  // A routine is a list of steps (e.g. "get ready for Valorant") written in a file on the PC.
  // This page only shows their names and tells the PC which one to run.
  function loadRoutines() {
    fetch('/routines', { headers: { 'X-Auth-Token': token }, cache: 'no-store' }).then(function (r) {
      return r.text().then(function (t) { return { code: r.status, text: t }; });
    }).catch(function () {
      return { code: -1, text: '' };
    }).then(function (r) {
      var list = by('routineList');
      list.innerHTML = '';
      if (r.code !== 200) { setStatus(describe(r, '')); return; }
      var lines = r.text.split('\n').filter(function (l) { return l.trim() !== ''; });
      by('routineHint').textContent = lines.length ? '' :
        t('No routines yet. On the PC press "Edit file" in the One Touch Controller window, write a routine, press "Reload" there, then Refresh here.');
      setStatus(lines.length ? lines.length + t(' routine(s)') : t('No routines on the PC yet'));
      lines.forEach(function (line) {
        var p = line.split('\t');
        var name = p[1] || p[0];
        var b = document.createElement('button');
        b.textContent = name;
        if (p[2] === '!') b.className = 'danger';
        b.onclick = function () {
          if (p[2] === '!' && !confirm(t('Run "') + name + t('"? It contains shutdown, restart or script steps.'))) return;
          setStatus(t('Sending...'));
          api('/routine/run', JSON.stringify({ id: p[0] })).then(function (res) {
            setStatus(describe(res, t('Started')));
            if (res.code === 200) waitForRoutine(p[0], name);
          });
        };
        list.appendChild(b);
      });
    });
  }
  by('reloadRoutines').onclick = loadRoutines;

  // Asks the PC every 1.5 seconds whether the routine is still running, then shows "Finished".
  function waitForRoutine(id, name) {
    var tries = 0;
    function check() {
      fetch('/routine/status?id=' + encodeURIComponent(id), { headers: { 'X-Auth-Token': token }, cache: 'no-store' })
        .then(function (r) { return r.status === 200 ? r.text() : ''; })
        .catch(function () { return 'retry'; })
        .then(function (text) {
          var p = (text || '').trim().split(' ');
          if (p[0] === 'running' || p[0] === 'retry') {
            if (++tries < 1200) setTimeout(check, 1500);      // gives up after about 30 minutes
            return;
          }
          if (p[0] !== 'finished') return;                    // an older PC server: it cannot tell
          if (p[1] && p[1] !== '0') setStatus(t('Finished with problems: ') + name + ' (' + p[1] + t(' step(s) failed') + ')');
          else setStatus(t('Finished: ') + name);
        });
    }
    setTimeout(check, 1000);
  }

  // ---------- Commands ----------
  var commands = [
    ['#', t('Open on the PC')],
    ['open_notepad', t('Notepad')], ['open_calculator', t('Calculator')], ['open_explorer', t('File Explorer')],
    ['open_chrome', 'Chrome'], ['open_youtube', 'YouTube'], ['open_google', 'Google'],
    ['run_script_1', t('Run script 1')],
    ['#', t('Media and sound')],
    ['play_pause', t('Play / Pause')], ['prev_track', t('Previous')],
    ['next_track', t('Next')], ['volume_down', t('Volume -')], ['volume_up', t('Volume +')],
    ['mute', t('Mute')],
    ['#', t('Security')],
    ['guard', t('Guard mode: off')], ['lock_screen', t('Lock with Windows')], ['message', t('Message to PC')],
    ['#', t('System')],
    ['screenshot', t('Screenshot')],
    ['shutdown_60', t('Shutdown (60s)'), true], ['restart_60', t('Restart (60s)'), true],
    ['cancel_shutdown', t('Cancel shutdown')]
  ];
  commands.forEach(function (c) {
    if (c[0] === '#') {                       // a heading above a group of buttons
      var g = document.createElement('div');
      g.className = 'group';
      g.textContent = c[1];
      by('cmdGrid').appendChild(g);
      return;
    }
    var b = document.createElement('button');
    b.textContent = c[1];
    if (c[2]) b.className = 'danger';
    if (c[0] === 'message') {                 // opens the message sheet
      b.onclick = function () { by('msgSheet').hidden = false; by('msgText').focus(); };
      by('cmdGrid').appendChild(b);
      return;
    }
    if (c[0] === 'guard') {                   // Guard mode is switched on and off, not "run"
      b.id = 'guardBtn';
      b.onclick = toggleGuard;
      by('cmdGrid').appendChild(b);
      return;
    }
    if (c[0] === 'lock_screen') c[3] = t('Lock with Windows? Your phone cannot see or control the PC until someone signs in at the PC.');
    b.onclick = function () {
      if (c[3] && !confirm(c[3])) return;
      if (c[2] && !confirm(t('Are you sure? ') + c[1])) return;
      setStatus(t('Sending...'));
      api('/run', JSON.stringify({ command: c[0] })).then(function (r) {
        setStatus(describe(r, t('Sent: ') + c[1]));
      });
    };
    by('cmdGrid').appendChild(b);
  });

  // ---------- Guard mode: the PC's own mouse and keyboard are blocked while you use it from here ----------
  var guardOn = false, lastGuard = '';
  function showGuard(text) {               // text from the PC: "on 2", "off", "off locked" or "nopassword"
    text = (text || '').trim();
    var changed = text !== lastGuard;
    lastGuard = text;
    var parts = text.split(' ');
    guardOn = parts[0] === 'on';
    var b = by('guardBtn');
    if (!b) return;
    b.textContent = guardOn ? t('Guard mode: on') : t('Guard mode: off');
    b.classList.toggle('on', guardOn);
    if (!changed) return;                  // only tell about news, not every 15 seconds
    if (guardOn && parts[1] && parts[1] !== '0') setStatus(t('Guard mode on. Wrong passwords typed at the PC: ') + parts[1]);
    if (parts[0] === 'off' && parts[1] === 'locked') setStatus(t('Windows locked the PC, so Guard mode turned itself off.'));
  }
  function refreshGuard() {
    if (!token) return;
    fetch('/guard', { headers: { 'X-Auth-Token': token }, cache: 'no-store' }).then(function (r) {
      if (r.status === 200) return r.text().then(showGuard);
    }).catch(function () { });
  }
  function toggleGuard() {
    var turnOn = !guardOn;
    if (turnOn && !confirm(t("Turn on Guard mode? The PC's own mouse and keyboard stop working until someone types the Guard password at the PC or you turn it off here."))) return;
    setStatus(t('Sending...'));
    api('/guard', JSON.stringify({ on: turnOn })).then(function (r) {
      if (r.code === 409) setStatus(t(r.text || 'Guard mode could not start'));
      else setStatus(describe(r, ''));
      if (r.code === 200) setStatus(t(r.text));
      refreshGuard();
    });
  }
  refreshGuard();
  setInterval(function () { if (!document.hidden) refreshGuard(); }, 15000);

  // ---------- Message to PC ----------
  [t('This PC is in use. Please do not touch it.'), t('I will be right back.'), t('Please wait for the owner.')].forEach(function (text) {
    var p = document.createElement('button');
    p.textContent = text;
    p.onclick = function () { by('msgText').value = text; };
    by('msgPresets').appendChild(p);
  });
  by('msgCancel').onclick = function () { by('msgSheet').hidden = true; };
  by('msgSheet').onclick = function (e) { if (e.target === by('msgSheet')) by('msgSheet').hidden = true; };
  by('msgSend').onclick = function () {
    var text = by('msgText').value.trim();
    if (!text) return;
    setStatus(t('Sending...'));
    api('/message', text, TEXT).then(function (r) {
      setStatus(r.code === 200 ? t('Message shown on the PC') : describe(r, ''));
      if (r.code === 200) { by('msgText').value = ''; by('msgSheet').hidden = true; }
    });
  };

  // ---------- Fast live connection (WebSocket) ----------
  // A normal request waits for the PC's answer after every movement. Over the internet that takes 100-300 ms,
  // so only a few movements per second get through. A WebSocket stays open and the movements stream through
  // without waiting. If it is not connected (yet), normal requests are used instead.
  var ws = null, wsReady = false, wsRetryAt = 0, latency = -1, wsSession = 0;

  function showLatency() {
    by('padInfo').textContent = latency >= 0 ? t('Live connection: ') + latency + ' ms' : t('Standard connection (slower)');
  }

  function wsConnect() {
    if (!token || (ws && ws.readyState < 2) || Date.now() < wsRetryAt) return;
    var sock;
    try {
      sock = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws');
    } catch (e) { return; }
    ws = sock; wsReady = false;
    sock.onopen = function () { sock.send('auth ' + token); };
    sock.onmessage = function (e) {
      if (typeof e.data !== 'string') {                 // a picture of the PC screen
        if (screenActive) showPicture(e.data); else if (wsOk()) ws.send('a');
      } else if (e.data === 'ok') {
        if (ws === sock) { wsReady = true; wsSession++; }
      } else if (e.data === 'viewdenied') {
        screenMessage(t('Screen viewing is turned off on the PC (switch in the PC window).'));
      } else if (e.data.indexOf('screenerror') === 0) {
        setStatus(t('The PC could not capture its screen: ') + e.data.substring(12));
      } else if (e.data.indexOf('q ') === 0) {
        latency = Math.max(0, Date.now() - Number(e.data.substring(2)));
        showLatency();
      }
    };
    sock.onclose = function (e) {
      if (ws !== sock) return;
      wsReady = false; latency = -1; showLatency();
      wsRetryAt = Date.now() + (e.code === 1008 ? 10000 : 2000);   // 1008 = wrong token: do not hammer the server
    };
    sock.onerror = function () {};
  }

  function wsOk() { return wsReady && ws && ws.readyState === 1; }

  // while the touchpad tab is open: keep it connected and measure the delay every 2 seconds
  setInterval(function () {
    if (!by('view-pad').classList.contains('active')) return;
    wsConnect();
    if (wsOk()) ws.send('p ' + Date.now());
  }, 2000);

  // ---------- Touchpad ----------
  function click(button) {
    if (wsOk()) { ws.send('c ' + button); return; }
    api('/mouse/click', JSON.stringify({ button: button }));
  }
  by('leftClick').onclick = function () { click('left'); };
  by('rightClick').onclick = function () { click('right'); };

  var pad = by('pad');
  var lastX = 0, lastY = 0, startX = 0, startY = 0, startT = 0;
  var moved = false, longFired = false, longTimer = null;
  var pendX = 0, pendY = 0, lastSent = 0, moveBusy = false;

  // sends the collected movement
  function flush(force) {
    var now = Date.now();
    var dx, dy;
    if (wsOk()) {
      // live connection: no waiting for answers. If the network is slow right now, keep collecting and send more later.
      if (!force && now - lastSent < 16) return;
      if (ws.bufferedAmount > 1024) return;
      dx = Math.round(pendX); dy = Math.round(pendY);
      if (dx === 0 && dy === 0) return;
      pendX -= dx; pendY -= dy; lastSent = now;
      ws.send('m ' + dx + ' ' + dy);
      return;
    }
    // no live connection: normal requests, one at a time
    if (!force && (now - lastSent < 30 || moveBusy)) return;
    dx = Math.round(pendX); dy = Math.round(pendY);
    if (dx === 0 && dy === 0) return;
    pendX -= dx; pendY -= dy; lastSent = now;
    moveBusy = true;   // wait for the PC to answer before sending the next movement
    api('/mouse/move', JSON.stringify({ dx: dx, dy: dy })).then(function () { moveBusy = false; });
  }

  pad.addEventListener('touchstart', function (e) {
    e.preventDefault();
    var t = e.touches[0];
    lastX = startX = t.clientX; lastY = startY = t.clientY;
    startT = Date.now(); moved = false; longFired = false;
    pendX = 0; pendY = 0;
    wsConnect();
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
      if (wsOk()) ws.send('s ' + notches);
      else api('/mouse/scroll', JSON.stringify({ amount: notches }));
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
    setStatus(t('Sending...'));
    api('/keyboard/type', text, TEXT).then(function (r) {
      setStatus(describe(r, t('Typed')));
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
        if (r.code !== 200) setStatus(t('Live typing failed: ') + describe(r, ''));
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
    by('clearText').textContent = live ? t('Clear box') : t('Clear');
    by('kbHint').textContent = live
      ? t('Live: everything you type goes straight to the PC. Set the PC to direct input (English) first.')
      : t('Click the place you want to type into on your PC first.');
    setStatus(live ? t('Live typing on (English)') : t('Live typing off'));
    if (live) ta.focus();
  };
  // ---------- Screen: look at the PC and control it by tapping the picture ----------
  // The PC sends its screen as small JPEG pictures over the live connection. After each picture the page
  // answers "a" (got it), and only then does the PC send the next one, so a slow connection never gets flooded.
  var presets = [[640, 35], [960, 50], [1280, 65], [1920, 70]];     // [picture width in pixels, JPEG quality]
  var DOUBLE_MS = 320;
  var screenActive = false, quality = 1, zoom = 100, cx = 500, cy = 500, viewSession = -1;
  var box = by('screenBox'), shot = by('screenImg'), lastUrl = null;

  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }
  function sendLive(msg) { if (wsOk()) ws.send(msg); }

  shot.onload = shot.onerror = function () { sendLive('a'); };      // "got it, send the next picture"

  function showPicture(blob) {
    var url = URL.createObjectURL(blob);
    shot.src = url;
    if (lastUrl) URL.revokeObjectURL(lastUrl);
    lastUrl = url;
    shot.style.display = 'block';
    by('screenMsg').style.display = 'none';
  }

  function screenMessage(text) {
    by('screenMsg').textContent = text;
    by('screenMsg').style.display = 'flex';
    shot.style.display = 'none';
  }

  function sendView() {
    if (!wsOk()) return;
    var p = presets[quality];
    ws.send('v ' + p[0] + ' ' + p[1] + ' ' + zoom + ' ' + cx + ' ' + cy);
    viewSession = wsSession;
  }
  function screenOpen() { screenActive = true; viewSession = -1; wsConnect(); sendView(); focusCapture(); }
  function screenClose() {
    if (!screenActive) return;
    screenActive = false;
    if (wsOk()) ws.send('v off');
    exitFull();
    cap.blur();
  }

  // (re)connect while the tab is open, and ask for the picture again after a reconnect
  setInterval(function () {
    if (!screenActive) return;
    wsConnect();
    if (wsOk() && viewSession !== wsSession) sendView();
  }, 700);

  function markOn(rowId, button) {
    Array.prototype.forEach.call(document.querySelectorAll('#' + rowId + ' button[data-q], #' + rowId + ' button[data-z]'),
      function (x) { x.classList.remove('on'); });
    button.classList.add('on');
  }
  Array.prototype.forEach.call(document.querySelectorAll('#qualityRow button'), function (b) {
    b.onclick = function () { quality = Number(b.getAttribute('data-q')); markOn('qualityRow', b); sendView(); };
  });
  Array.prototype.forEach.call(document.querySelectorAll('#zoomRow button[data-z]'), function (b) {
    b.onclick = function () { zoom = Number(b.getAttribute('data-z')); markOn('zoomRow', b); sendView(); };
  });
  function pan(dx, dy) {
    if (zoom <= 100) return;
    var step = Math.round(100 / zoom * 500);          // half of what is visible
    cx = clamp(cx + dx * step, 0, 1000); cy = clamp(cy + dy * step, 0, 1000);
    sendView();
  }
  by('panLeft').onclick = function () { pan(-1, 0); };
  by('panRight').onclick = function () { pan(1, 0); };
  by('panUp').onclick = function () { pan(0, -1); };
  by('panDown').onclick = function () { pan(0, 1); };

  // where the picture really is inside the black box (it is centred, with bars at the sides if needed)
  function pictureRect() {
    var b = box.getBoundingClientRect(), nw = shot.naturalWidth, nh = shot.naturalHeight;
    if (!nw || !nh) return null;
    var s = Math.min(b.width / nw, b.height / nh), w = nw * s, h = nh * s;
    return { x: b.left + (b.width - w) / 2, y: b.top + (b.height - h) / 2, w: w, h: h };
  }
  // a touch position -> a spot on the real PC screen (0-1000 in both directions), or null if outside the picture
  function toScreenSpot(clientX, clientY) {
    var r = pictureRect();
    if (!r) return null;
    var fx = (clientX - r.x) / r.w, fy = (clientY - r.y) / r.h;
    if (fx < 0 || fx > 1 || fy < 0 || fy > 1) return null;
    var size = 100 / zoom;                            // the same formula the PC uses (viewRect in ScreenStream.kt)
    var x0 = clamp(cx / 1000 - size / 2, 0, 1 - size), y0 = clamp(cy / 1000 - size / 2, 0, 1 - size);
    return [Math.round((x0 + fx * size) * 1000), Math.round((y0 + fy * size) * 1000)];
  }
  function pointAt(spot) { sendLive('u ' + spot[0] + ' ' + spot[1]); }

  var g = null, gLong = null, tapTimer = null, lastTap = null, lastDragSent = 0;
  function cancelGesture() { clearTimeout(gLong); if (g && g.down) sendLive('b up'); g = null; }

  var lastTouchAt = 0;
  box.addEventListener('touchstart', function (e) {
    e.preventDefault();
    lastTouchAt = Date.now();
    if (e.touches.length > 1) { cancelGesture(); return; }
    var t = e.touches[0];
    g = { x: t.clientX, y: t.clientY, t0: Date.now(), moved: false, long: false, down: false };
    clearTimeout(gLong);
    gLong = setTimeout(function () {                  // finger held still = right click
      if (g && !g.moved) {
        g.long = true;
        var s = toScreenSpot(g.x, g.y);
        if (s) { pointAt(s); sendLive('c right'); }
        if (navigator.vibrate) navigator.vibrate(20);
      }
    }, 500);
  }, { passive: false });

  box.addEventListener('touchmove', function (e) {
    e.preventDefault();
    if (!g) return;
    var t = e.touches[0];
    if (!g.moved) {
      if (Math.abs(t.clientX - g.x) + Math.abs(t.clientY - g.y) < 12) return;   // small wiggles still count as a tap
      g.moved = true;
      clearTimeout(gLong);
      if (g.long) return;
      var s0 = toScreenSpot(g.x, g.y);
      if (s0) { pointAt(s0); sendLive('b down'); g.down = true; }               // start dragging where the finger started
    }
    var now = Date.now();
    if (g.down && now - lastDragSent >= 30) {
      var s = toScreenSpot(t.clientX, t.clientY);
      if (s) { pointAt(s); lastDragSent = now; }
    }
  }, { passive: false });

  box.addEventListener('touchend', function (e) {
    e.preventDefault();
    clearTimeout(gLong);
    if (!g) return;
    var cur = g;
    g = null;
    if (cur.moved) {
      if (cur.down) {
        var t = e.changedTouches[0], s = toScreenSpot(t.clientX, t.clientY);
        if (s) pointAt(s);
        sendLive('b up');
      }
      return;
    }
    if (cur.long || Date.now() - cur.t0 >= 400) return;
    handleTap(cur.x, cur.y);
  }, { passive: false });

  // a tap or a mouse click (without moving)
  function handleTap(x, y) {
    var spot = toScreenSpot(x, y);
    if (!spot) return;
    if (lastTap && Date.now() - lastTap.t < DOUBLE_MS && Math.abs(x - lastTap.x) + Math.abs(y - lastTap.y) < 40) {
      clearTimeout(tapTimer); lastTap = null;         // second tap close to the first = double click
      pointAt(spot); sendLive('c double');
    } else {
      lastTap = { t: Date.now(), x: x, y: y };
      pointAt(spot);                                  // move the pointer right away
      tapTimer = setTimeout(function () { sendLive('c left'); lastTap = null; }, DOUBLE_MS);   // wait in case a 2nd click follows
    }
  }

  // ----- the same with a mouse (when this page is opened on a computer) -----
  // click = click, two quick clicks = double click, drag = drag, right button = right click, wheel = scroll
  var mouseG = null;
  function fromTouch() { return Date.now() - lastTouchAt < 800; }       // phones also send fake mouse events after a touch
  box.addEventListener('mousedown', function (e) {
    if (e.button !== 0 || fromTouch()) return;
    e.preventDefault();
    mouseG = { x: e.clientX, y: e.clientY, moved: false, down: false };
  });
  window.addEventListener('mousemove', function (e) {
    if (!mouseG) return;
    if (!mouseG.moved) {
      if (Math.abs(e.clientX - mouseG.x) + Math.abs(e.clientY - mouseG.y) < 6) return;
      mouseG.moved = true;
      var s0 = toScreenSpot(mouseG.x, mouseG.y);
      if (s0) { pointAt(s0); sendLive('b down'); mouseG.down = true; }
    }
    var now = Date.now();
    if (mouseG.down && now - lastDragSent >= 30) {
      var s = toScreenSpot(e.clientX, e.clientY);
      if (s) { pointAt(s); lastDragSent = now; }
    }
  });
  window.addEventListener('mouseup', function (e) {
    if (!mouseG) return;
    var cur = mouseG;
    mouseG = null;
    if (cur.moved) {
      if (cur.down) {
        var s = toScreenSpot(e.clientX, e.clientY);
        if (s) pointAt(s);
        sendLive('b up');
      }
      return;
    }
    handleTap(cur.x, cur.y);
  });
  box.addEventListener('contextmenu', function (e) {
    e.preventDefault();
    if (fromTouch()) return;
    var s = toScreenSpot(e.clientX, e.clientY);
    if (s) { pointAt(s); sendLive('c right'); }
  });
  box.addEventListener('wheel', function (e) {
    e.preventDefault();
    if (e.deltaY === 0) return;
    var n = Math.max(1, Math.round(Math.abs(e.deltaY) / 100)) * (e.deltaY > 0 ? 1 : -1);
    if (wsOk()) ws.send('s ' + n); else api('/mouse/scroll', JSON.stringify({ amount: n }));
  }, { passive: false });
  box.addEventListener('touchcancel', cancelGesture);

  // scroll strip on the right
  var sStrip = by('screenScroll'), sLast = 0, sPend = 0;
  sStrip.addEventListener('touchstart', function (e) { e.preventDefault(); sLast = e.touches[0].clientY; sPend = 0; }, { passive: false });
  sStrip.addEventListener('touchmove', function (e) {
    e.preventDefault();
    var y = e.touches[0].clientY;
    sPend += -(y - sLast) / 20;
    sLast = y;
    var n = sPend < 0 ? Math.ceil(sPend) : Math.floor(sPend);
    if (n !== 0) {
      sPend -= n;
      if (wsOk()) ws.send('s ' + n); else api('/mouse/scroll', JSON.stringify({ amount: n }));
    }
  }, { passive: false });
  // ---------- Direct typing: the keyboard of THIS device types on the PC ----------
  // A hidden text box catches the keys. Normal characters (also Japanese from an input method) are sent as text;
  // Enter, arrows, F-keys and shortcuts like Ctrl+C are sent as key presses.
  var cap = by('captureBox'), directType = by('directType');
  var coarse = !!(window.matchMedia && window.matchMedia('(pointer: coarse)').matches);   // phone or tablet
  directType.checked = !coarse;           // on a computer typing is on by default; on a phone use the Keyboard button
  var isMac = /Mac|iPhone|iPad/.test(navigator.platform || '');
  var typeBuf = '', typeTimer = null;

  function flushTyping() {
    clearTimeout(typeTimer); typeTimer = null;
    while (typeBuf) { sendLive('t ' + typeBuf.substring(0, 900)); typeBuf = typeBuf.substring(900); }
  }
  // fast typing is collected for a moment and sent as one piece (fewer messages, symbols are pasted together)
  function typeText(text) {
    if (!text) return;
    typeBuf += text;
    if (!typeTimer) typeTimer = setTimeout(flushTyping, 25);
  }
  function pressKey(combo) { flushTyping(); sendLive('k ' + combo); }

  function focusCapture() {
    if (screenActive && directType.checked) { try { cap.focus({ preventScroll: true }); } catch (e) { cap.focus(); } }
  }
  directType.onchange = function () { if (directType.checked) focusCapture(); else cap.blur(); };
  by('kbBtn').onclick = function () { try { cap.focus({ preventScroll: true }); } catch (e) { cap.focus(); } };
  // after any click in the Screen tab give the keys back to the hidden box (a focused button would eat Space / Enter)
  by('view-screen').addEventListener('click', function (e) { if (e.target !== directType && e.target.id !== 'kbBtn') focusCapture(); });
  window.addEventListener('mouseup', function () { if (screenActive) setTimeout(focusCapture, 0); });

  var namedKeys = { Enter: 'enter', Backspace: 'backspace', Tab: 'tab', Escape: 'esc', Delete: 'delete', ArrowUp: 'up',
                    ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Home: 'home', End: 'end',
                    PageUp: 'pageup', PageDown: 'pagedown' };
  // the key itself (not the character), for shortcuts
  function keyName(e) {
    if (namedKeys[e.key]) return namedKeys[e.key];
    var c = e.code || '';
    if (c.length === 4 && c.indexOf('Key') === 0) return c.charAt(3).toLowerCase();       // KeyA ... KeyZ
    if (c.length === 6 && c.indexOf('Digit') === 0) return c.charAt(5);                  // Digit0 ... Digit9
    var fk = c.match(/^F(\d{1,2})/);                                                      // F1 ... F12
    if (fk && fk[0] === c && Number(fk[1]) >= 1 && Number(fk[1]) <= 12) return c.toLowerCase();
    if (c === 'Space') return 'space';
    return null;
  }
  cap.addEventListener('keydown', function (e) {
    if (e.isComposing || e.keyCode === 229) return;                         // an input method is busy: handled when it finishes
    if (e.getModifierState && e.getModifierState('AltGraph')) return;       // AltGr + key = a normal character (e.g. @ on some layouts)
    var ctrl = e.ctrlKey || (isMac && e.metaKey), alt = e.altKey;
    var key = keyName(e);
    if (!key) return;                                                       // only a modifier, or a normal character (arrives as text below)
    var special = !!namedKeys[e.key] || /^F\d/.test(e.key);
    if (!(special || ctrl || alt)) return;                                  // a normal character: the text box turns it into text
    if (ctrl && !alt && key === 'v') return;                                // Ctrl+V: see the paste handler
    e.preventDefault();
    pressKey((ctrl ? 'ctrl+' : '') + (alt ? 'alt+' : '') + (e.shiftKey ? 'shift+' : '') + key);
  });
  function drainCapture() { if (cap.value) { typeText(cap.value); cap.value = ''; } }
  cap.addEventListener('input', function (e) { if (!e.isComposing) drainCapture(); });
  cap.addEventListener('compositionend', function () { setTimeout(drainCapture, 0); });
  cap.addEventListener('paste', function (e) {
    e.preventDefault();
    var text = (e.clipboardData || window.clipboardData).getData('text');
    if (text) typeText(text);                                               // types the text from THIS device's clipboard on the PC
  });

  // ---------- Full screen ----------
  // Browsers on a computer (and iPad) can really go full screen. An iPhone cannot, so there the page just hides
  // everything except the picture ("Add to Home Screen" in Safari makes it a true full-screen app).
  var apiFull = false;
  function fullElement() { return document.fullscreenElement || document.webkitFullscreenElement; }
  function updateFullBtn() { by('fullBtn').textContent = document.body.classList.contains('immersive') ? t('Exit full screen') : t('Full screen'); }
  function enterFull() {
    document.body.classList.add('immersive');
    document.body.classList.remove('panel');
    updateFullBtn();
    var el = document.documentElement, rf = el.requestFullscreen || el.webkitRequestFullscreen;
    if (rf) { try { var r = rf.call(el); if (r && r.catch) r.catch(function () {}); } catch (e) {} }
    try { if (screen.orientation && screen.orientation.lock) screen.orientation.lock('landscape').catch(function () {}); } catch (e) {}
    // lets Alt+Tab, Esc and similar keys reach the PC (Chrome / Edge only; hold Esc to leave full screen)
    try { if (navigator.keyboard && navigator.keyboard.lock) navigator.keyboard.lock().catch(function () {}); } catch (e) {}
    focusCapture();
  }
  function exitFull() {
    if (!document.body.classList.contains('immersive')) return;
    document.body.classList.remove('immersive', 'panel');
    updateFullBtn();
    apiFull = false;
    if (fullElement()) {
      var ex = document.exitFullscreen || document.webkitExitFullscreen;
      if (ex) { try { var r = ex.call(document); if (r && r.catch) r.catch(function () {}); } catch (e) {} }
    }
    try { if (screen.orientation && screen.orientation.unlock) screen.orientation.unlock(); } catch (e) {}
    try { if (navigator.keyboard && navigator.keyboard.unlock) navigator.keyboard.unlock(); } catch (e) {}
  }
  function onFullChange() {
    if (fullElement()) apiFull = true;
    else if (apiFull) exitFull();                       // the browser left full screen (Esc): leave our full screen too
  }
  document.addEventListener('fullscreenchange', onFullChange);
  document.addEventListener('webkitfullscreenchange', onFullChange);
  by('fullBtn').onclick = function () { if (document.body.classList.contains('immersive')) exitFull(); else enterFull(); };
  by('screenMenu').onclick = function () { document.body.classList.toggle('panel'); };
})();
</script>
</body>
</html>
"""
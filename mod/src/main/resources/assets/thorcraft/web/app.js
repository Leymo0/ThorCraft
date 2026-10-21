/* ThorCraft bottom-screen panel. Served by the mod at http://127.0.0.1:47123/ and shown by the
   ThorCraft companion app on the AYN Thor's bottom screen (also works in any browser for testing). */
'use strict';

// ------------------------------------------------------------------ helpers
const $ = (s, r = document) => r.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const Thor = window.Thor || null; // Android bridge (companion app)
const store = {
  get(k, d) { try { const v = localStorage.getItem('tc.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('tc.' + k, JSON.stringify(v)); } catch { } },
};

function cmd(o) {
  return fetch('/cmd', { method: 'POST', body: JSON.stringify(o), headers: { 'Content-Type': 'text/plain' } }).catch(() => { });
}
function buzz(ms) {
  if (!S.settings.haptics) return;
  try { if (Thor && Thor.vibrate) Thor.vibrate(ms); else if (navigator.vibrate) navigator.vibrate(ms); } catch { }
}
function segs(arr, fallback = '') {
  if (!arr || !arr.length) return esc(fallback);
  return arr.map(s => {
    let st = '';
    if (s.c) st += `color:${s.c};`;
    if (s.b) st += 'font-weight:900;';
    if (s.it) st += 'font-style:italic;';
    if (s.st) st += 'text-decoration:line-through;';
    if (s.u) st += 'text-decoration:underline;';
    return st ? `<span class="seg" style="${st}">${esc(s.t)}</span>` : esc(s.t);
  }).join('');
}
const plain = arr => (arr || []).map(s => s.t).join('');
function imgTag(src, cls = '') {
  return `<img class="${cls}" src="${src}" data-src="${src}" onerror="iconErr(this)" draggable="false" alt="">`;
}
/** Rendered item / Pokémon icon (exactly as the game draws it, resource packs included). */
const icon = (key, cls = '') => key ? imgTag(`/icon/${key}.png`, cls) : '';
/** Icon of any item by id: gi('minecraft:chest'). Rendered by the game, so resource packs apply. */
const gi = (id, cls = 'gi') => { const [ns, p] = id.includes(':') ? id.split(':') : ['minecraft', id]; return imgTag(`/itemicon/${ns}/${p}.png`, cls); };
/** Any game texture, e.g. tex('minecraft:gui/sprites/hud/heart/full'). Pixel-art, resource packs apply. */
const tex = (id, cls = 'px') => { const [ns, p] = id.includes(':') ? id.split(':') : ['minecraft', id]; return imgTag(`/tex/${ns}/${p}.png`, cls); };
window.iconErr = img => {
  const n = (+img.dataset.n || 0) + 1;
  if (n > 12) { img.style.visibility = 'hidden'; return; }
  img.dataset.n = n;
  setTimeout(() => { img.src = `${img.dataset.src}?r=${n}`; }, 250 + n * 150);
};
/** Only touch the DOM when the markup actually changed (keeps images and scroll steady). */
function setHTML(el, html) {
  if (el._html === html) return false;
  el._html = html;
  el.innerHTML = html;
  if (el.id === 'main') el.dataset.layout = '';
  return true;
}
/** Build a view's fixed skeleton once; afterwards only its parts get updated. */
function layout(main, key, html) {
  if (main.dataset.layout === key) return false;
  main.innerHTML = html;
  main._html = null;
  main.dataset.layout = key;
  return true;
}
let toastTimer;
function toast(msg, cls = '') {
  const t = $('#toast');
  t.className = 'toast ' + cls;
  t.textContent = msg;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add('hidden'), cls === 'shiny' ? 6000 : 2200);
}

// ------------------------------------------------------------------ state
const DEFAULT_PINS = [];
const DEFAULT_QUICK = ['/spawn', '/home', '/rtp', '/pokeheal'];
const S = {
  st: null,
  tab: store.get('tab', 'home') === 'map' ? 'home' : store.get('tab', 'home'),
  settings: Object.assign({ zoom: 1, auto: true, haptics: true, hideFillers: true, menuInv: true, menuPreview: true, padLayout: 'nintendo', theme: 'minecraft', autoPicture: true }, store.get('settings', {})),
  pins: store.get('pins', DEFAULT_PINS),
  quick: store.get('quick', DEFAULT_QUICK),
  chat: [],
  keys: null,
  keyFilter: '',
  slotMode: 'pick',
  picked: null,       // slot id last tapped (info panel)
  summary: null,      // {slot, data, tab}
  dismissed: null,    // context the user stepped away from
  showWidgets: false,
  battleMirror: false,
  connected: false,
  debug: null,
};
function saveSettings() { store.set('settings', S.settings); applyZoom(); applyTheme(); }
const THEMES = [
  { id: 'night', name: 'Night', c: ['#000', '#16181d', '#ff7a2f', '#eceef2'] },
  { id: 'oled', name: 'OLED Black', c: ['#000', '#000', '#fff', '#222'] },
  { id: 'minecraft', name: 'Minecraft', c: ['#6b4a2f', '#c6c6c6', '#7fb238', '#6f6f6f'] },
  { id: 'matcha', name: 'Matcha & Oak', c: ['#9c7442', '#b7c98a', '#5e7f2e', '#6b4a2b'] },
  { id: 'neon', name: 'Neon Purple', c: ['#05000c', '#24124a', '#c04dff', '#36f1ff'] },
  { id: 'cloud', name: 'Cloudy', c: ['#d9e7f5', '#ffffff', '#5aa0e6', '#f2f6fa'] },
  { id: 'snes', name: 'SNES', c: ['#b9b9c3', '#1c3a9e', '#6b5ac8', '#e24a4a'] },
  { id: 'gba', name: 'Game Boy Advance', c: ['#4a3fa8', '#f8f8f8', '#e83838', '#404050'] },
  { id: 'dp', name: 'Diamond & Pearl', c: ['#33475c', '#f8f8f8', '#e04040', '#3868a8'] },
];
function applyTheme() { document.documentElement.dataset.theme = S.settings.theme || 'minecraft'; }
applyTheme();
function applyZoom() { document.documentElement.style.fontSize = (16 * S.settings.zoom) + 'px'; }
applyZoom();

// ------------------------------------------------------------------ connection
let es, lastMsg = 0, waitingTimer;
function connect() {
  try { es && es.close(); } catch { }
  es = new EventSource('/events');
  es.addEventListener('state', e => {
    lastMsg = Date.now();
    S.st = JSON.parse(e.data);
    setConnected(true);
    schedule();
  });
  es.addEventListener('chat', e => {
    const m = JSON.parse(e.data);
    if (!S.chat.length || S.chat[S.chat.length - 1].id < m.id) {
      S.chat.push(m);
      if (S.chat.length > 200) S.chat.shift();
      if (currentView() === 'chat') schedule();
    }
  });
  es.addEventListener('fx', e => onFx(JSON.parse(e.data)));
  es.onerror = () => setConnected(false);
}
function setConnected(on) {
  if (S.connected === on) return;
  S.connected = on;
  $('#conn').classList.toggle('off', !on);
  clearTimeout(waitingTimer);
  if (!on) {
    // Minecraft closed: hand the bottom screen back to the companion's waiting page.
    waitingTimer = setTimeout(() => { if (!S.connected && Thor && Thor.waiting) Thor.waiting(); }, 6000);
  } else {
    fetch('/api/chat').then(r => r.json()).then(h => { S.chat = h; schedule(); }).catch(() => { });
  }
}
setInterval(() => { if (S.connected && Date.now() - lastMsg > 5000) setConnected(false); }, 1000);

function onFx(f) {
  if (f.type === 'hurt') buzz(Math.min(140, 30 + f.value * 15));
  else if (f.type === 'battle') { buzz(120); S.dismissed = null; }
  else if (f.type === 'shiny') {
    const [name, dir] = (f.text || '?|?').split('|');
    toast(`✨ Shiny ${name}! ${Math.round(f.value)} blocks ${dir}`, 'shiny');
    buzz(400);
  }
}

// ------------------------------------------------------------------ routing
const TABS = [
  { id: 'home', ic: '⌂', item: 'minecraft:grass_block', name: 'Home' },
  { id: 'party', ic: '◉', item: 'cobblemon:poke_ball', name: 'Party', cobble: true },
  { id: 'chat', ic: '✉', item: 'minecraft:writable_book', name: 'Chat' },
  { id: 'keys', ic: '⌨', item: 'minecraft:tripwire_hook', name: 'Keys' },
  { id: 'settings', ic: '⚙', item: 'minecraft:comparator', name: 'More' },
];
const tabIcon = t => S.connected ? `<span class="ic">${gi(t.item)}</span>` : `<span class="ic">${t.ic}</span>`;

/** What the game is showing right now that deserves the bottom screen. */
function gameContext() {
  const st = S.st;
  if (!st) return null;
  const battle = st.cobble && st.cobble.battle;
  const kind = st.screen && st.screen.kind;
  if (battle) return 'battle';
  if (kind === 'container') return 'container';
  if (kind === 'mirror') return 'mirror';
  if (kind === 'chat') return 'chat';
  return null;
}
function currentView() {
  const ctx = gameContext();
  if (ctx && S.settings.auto && S.dismissed !== ctxSig()) return ctx;
  return S.tab;
}
function ctxSig() {
  const st = S.st;
  if (!st) return '';
  return gameContext() + ':' + (st.screen && st.screen.cls || '') + ':' + (st.screen && st.screen.syncId || '');
}

let raf = 0;
function schedule() { if (!raf) raf = requestAnimationFrame(() => { raf = 0; render(); }); }

function render() {
  const st = S.st;
  const view = currentView();
  renderHeader(view);
  renderTabs(view);
  const main = $('#main');
  if (main.dataset.view !== view) { main.dataset.view = view; main._html = null; main.dataset.layout = ''; main.scrollTop = 0; }
  if (!st) return setHTML(main, `<div class="empty-state"><div class="big">⛏</div><p>Waiting for Minecraft…</p></div>`);
  const fn = VIEWS[view] || VIEWS.home;
  fn(main, st);
  if (!$('#mirror')) stopMirror();
}

function renderHeader(view) {
  const st = S.st;
  const ctx = gameContext();
  let title = 'ThorCraft';
  if (st && st.screen && st.screen.title && st.screen.title.length && (view === 'container' || view === 'mirror')) title = segs(st.screen.title);
  else if (view === 'battle') title = st.cobble.battle.wild ? 'Wild battle' : 'Battle';
  else { const t = TABS.find(t => t.id === view); if (t) title = esc(t.name); }
  setHTML($('#title'), title);

  let v = '';
  if (st && st.player) {
    const p = st.player;
    v = `<span class="hp">${tex(HEART)} <b>${Math.ceil(p.hp)}</b></span><span class="food">${tex(FOOD)} <b>${p.food}</b></span>` + (p.armor ? `<span>${tex(ARMOR)} <b>${p.armor}</b></span>` : '');
  }
  setHTML($('#vitals'), v);

  const pill = $('#ctxPill');
  const away = ctx && view !== ctx;
  pill.classList.toggle('hidden', !away);
  if (away) pill.textContent = { battle: '⚔ Battle', container: '▣ Menu', mirror: '▣ Menu', chat: '✉ Chat' }[ctx] || 'Back';
}

function renderTabs(view) {
  const st = S.st;
  const ctx = gameContext();
  const inCtx = ctx && view === ctx && ctx !== 'chat';
  let html = '';
  if (inCtx) {
    html = `<button data-tap="ctx:leave"><span class="ic">${gi('minecraft:compass')}</span>Tabs</button>`;
    if (ctx === 'container' || ctx === 'mirror') html += `<button data-tap="screen:close"><span class="ic">${gi('minecraft:barrier')}</span>Close</button>`;
    if (ctx === 'battle') html += `<button data-tap="battle:mirror" class="${S.battleMirror ? 'on' : ''}"><span class="ic">${gi('minecraft:spyglass')}</span>Screen</button>`;
  } else {
    html = TABS.filter(t => !t.cobble || (st && st.cobblemon)).map(t =>
      `<button class="${t.id === view ? 'on' : ''}" data-tap="tab:${t.id}">${tabIcon(t)}${t.name}</button>`).join('');
  }
  setHTML($('#tabs'), html);
}

// ------------------------------------------------------------------ views
const VIEWS = {};

// ---------------- HOME
const HEART = 'minecraft:gui/sprites/hud/heart/full', FOOD = 'minecraft:gui/sprites/hud/food_full', ARMOR = 'minecraft:gui/sprites/hud/armor_full';
const AIR = 'minecraft:gui/sprites/hud/air';
const QUICK_ACTIONS = [
  { a: 'inventory', item: 'minecraft:chest', n: 'Inventory' },
  { a: 'openChatTab', item: 'minecraft:writable_book', n: 'Chat' },
  { a: 'perspective', item: 'minecraft:ender_eye', n: 'Camera view (F5)' },
  { a: 'key:key.drop', item: 'minecraft:dropper', n: 'Drop item' },
  { a: 'key:key.swapOffhand', item: 'minecraft:shield', n: 'Swap offhand' },
  { a: 'sneak', item: 'minecraft:leather_boots', n: 'Sneak (toggle)', on: st => st.player.sneakLatched },
  { a: 'hud', item: 'minecraft:item_frame', n: 'Hide HUD (F1)', on: st => st.hudHidden },
  { a: 'screenshot', item: 'minecraft:painting', n: 'Screenshot' },
  { a: 'pause', item: 'minecraft:clock', n: 'Pause menu' },
];
VIEWS.home = (main, st) => {
  if (!st.inWorld) {
    return setHTML(main, `<div class="empty-state"><div class="big">⛏</div><p>Not in a world yet.</p><p class="muted">Menus like the title screen show here automatically.</p></div>`);
  }
  const p = st.player;
  layout(main, 'home', `<div id="compass" class="compass"></div>
    <div class="mapbox home" id="homemap" data-tap="mapbig"><canvas id="homemapc"></canvas></div>
    <div id="mapextra"></div>
    <div id="coords"></div>
    <div id="hquick"></div>`);
  ensureMapPolling();

  // compass strip: heading 0 = north, 90 = east (Minecraft yaw 0 = south)
  const heading = ((p.yaw + 180) % 360 + 360) % 360;
  const marks = [];
  for (let a = 0; a < 360; a += 15) {
    let d = a - heading;
    if (d > 180) d -= 360;
    if (d < -180) d += 360;
    if (Math.abs(d) > 100) continue;
    const left = 50 + d / 2; // 200° visible across the strip
    const lbl = { 0: 'N', 45: 'NE', 90: 'E', 135: 'SE', 180: 'S', 225: 'SW', 270: 'W', 315: 'NW' }[a];
    marks.push(lbl ? `<span class="cm big ${lbl === 'N' ? 'north' : ''}" style="left:${left}%">${lbl}</span>` : `<span class="cm" style="left:${left}%">|</span>`);
  }
  const hm = $('#homemap', main);
  if (hm.classList.contains('big') !== !!S.mapBig) { hm.classList.toggle('big', !!S.mapBig); requestAnimationFrame(drawMap); }
  const cobble = st.cobblemon;
  setHTML($('#mapextra', main), S.mapBig ? `<div class="legend"><span><i style="background:#fff"></i>You</span><span><i style="background:#4da3ff"></i>Player</span>${cobble ? '<span><i style="background:#3ddc84"></i>Pokémon</span><span><i style="background:#ffe066"></i>Shiny</span>' : ''}<span><i style="background:#ff4d4d"></i>Hostile</span></div>
    ${cobble ? '<div class="h">Nearby Pokémon</div><div id="nearby"></div>' : ''}` : '');
  setHTML($('#compass', main), marks.join('') + `<span class="cneedle"></span><span class="cdeg mono">${Math.round(heading)}°</span>`);

  const t = (p.time + 6000) % 24000;
  const hh = Math.floor(t / 1000), mm = Math.floor((t % 1000) * 0.06);
  const night = p.time > 12800 && p.time < 23200;
  const dim = p.dim.replace(/^minecraft:/, '').replace(/_/g, ' ');
  setHTML($('#coords', main), `<div class="coords">
      <div class="xyz"><span><small>X</small>${p.x}</span><span><small>Y</small>${p.y}</span><span><small>Z</small>${p.z}</span></div>
      <div class="cmeta">${esc(p.biome)} · ${night ? '☾' : '☀'} ${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}${dim !== 'overworld' ? ' · ' + esc(dim) : ''}</div>
      <button class="btn small share" data-tap="sharecoords">Share</button>
    </div>`);

  setHTML($('#hquick', main), `<div class="grid g3" style="margin-top:var(--gap)">${QUICK_ACTIONS.map(q => `<button class="tile ${q.on && q.on(st) ? 'lit' : ''}" data-tap="${q.a}"><span class="ic">${gi(q.item)}</span>${q.n}</button>`).join('')}
    ${S.pins.map(pin => `<button class="tile pin" data-tap="key:${esc(pin.id)}" data-hold="unpin:${esc(pin.id)}"><span class="ic">${gi(pinItem(pin.id))}</span>${esc(pin.name)}</button>`).join('')}
  </div>
  ${S.pins.length ? '' : '<p class="muted" style="font-size:.72rem;margin:.4rem .1rem">Tip: hold any key in the Keys tab to pin it here (Xaero\'s map, Cobblemon keys, emotes…).</p>'}`);
  if (mapData) drawMap();
};
/** A fitting game item for a pinned key. */
function pinItem(id) {
  const k = id.toLowerCase();
  const map = [['map', 'minecraft:filled_map'], ['waypoint', 'minecraft:lodestone'], ['pokedex', 'cobblemon:pokedex_red'], ['summary', 'cobblemon:poke_ball'],
    ['cobblemon', 'cobblemon:poke_ball'], ['emote', 'minecraft:player_head'], ['zoom', 'minecraft:spyglass'], ['advancement', 'minecraft:knowledge_book'],
    ['playerlist', 'minecraft:name_tag'], ['sprint', 'minecraft:sugar'], ['jump', 'minecraft:rabbit_foot'], ['attack', 'minecraft:iron_sword'], ['use', 'minecraft:stick']];
  for (const [w, item] of map) if (k.includes(w)) return item;
  return 'minecraft:lever';
}
const roman = n => ['', 'I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X'][n] || n;
const fmtTicks = t => { const s = Math.floor(t / 20); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; };

function slotHTML(it, attrs = '', cls = '') {
  if (!it) return `<div class="slot ${cls}" ${attrs}></div>`;
  const dur = it.d != null ? `<span class="dur"><i style="width:${it.d * 100}%;background:hsl(${it.d * 120},90%,50%)"></i></span>` : '';
  return `<div class="slot ${cls} ${it.g ? 'glint' : ''}" ${attrs}>${icon(it.i)}${it.c > 1 ? `<span class="n">${it.c}</span>` : ''}${dur}</div>`;
}

// ---------------- CONTAINER (inventories, chests, server menus, modded machines)
function showAsPicture(sc) {
  const key = sc.cls + ':' + sc.syncId;
  if (S.viewChoice && S.viewChoice.key === key) return S.viewChoice.picture;   // you switched by hand for this menu
  return !!sc.painted && S.settings.autoPicture;
}
VIEWS.container = (main, st) => {
  if (showAsPicture(st.screen)) return VIEWS.mirror(main, st);
  const sc = st.screen;
  const menu = sc.menu;
  const container = sc.slots.filter(s => !s.p);
  const inv = sc.slots.filter(s => s.p);
  const fillerish = s => s.it && /glass_pane$/.test(s.it.id) && plain(s.it.name).trim().length <= 1;
  const showInv = !menu || S.settings.menuInv;
  const cs = getComputedStyle(main);
  const width = main.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight) - 2;

  const layout = (slots, scale) => {
    if (!slots.length) return '';
    const minX = Math.min(...slots.map(s => s.x)), minY = Math.min(...slots.map(s => s.y));
    const maxX = Math.max(...slots.map(s => s.x)), maxY = Math.max(...slots.map(s => s.y));
    const w = (maxX - minX + 18) * scale, hgt = (maxY - minY + 18) * scale;
    return `<div class="abs" style="width:${w}px;height:${hgt}px">${slots.map(s => {
      const filler = menu && S.settings.hideFillers && fillerish(s);
      const cls = (filler ? 'filler ' : '') + (S.picked === s.id ? 'picked' : '');
      const style = `left:${(s.x - minX) * scale}px;top:${(s.y - minY) * scale}px;width:${16.5 * scale}px;height:${16.5 * scale}px`;
      return slotHTML(s.it, `style="${style}" data-tap="slot:${s.id}" data-hold="slothold:${s.id}"`, cls);
    }).join('')}</div>`;
  };
  // one scale for both parts so slots line up nicely
  const spanX = arr => arr.length ? Math.max(...arr.map(s => s.x)) - Math.min(...arr.map(s => s.x)) + 18 : 0;
  const scale = Math.min(3.2, width / Math.max(spanX(container), showInv ? spanX(inv) : 0, 162));

  const picked = sc.slots.find(s => s.id === S.picked);
  const info = picked && picked.it
    ? `<div class="nm">${segs(picked.it.name)}${picked.it.c > 1 ? ` <span class="muted">×${picked.it.c}</span>` : ''}</div>${picked.it.lore ? `<div class="lore">${picked.it.lore.map(l => segs(l) || '&nbsp;').join('<br>')}</div>` : ''}`
    : `<span class="muted">${menu ? (S.settings.menuPreview ? 'Menu: tap a button to read it, tap it again to use it. Your inventory works normally — tap to pick up, tap a menu slot to put it in.' : 'Tap = click · hold = right-click.') : 'Tap: pick up / place · Hold: right-click (half / one)'}</span>`;

  const modes = menu
    ? `<div class="row"><span class="muted" style="font-size:.7rem;white-space:nowrap">Tap does</span><div class="modes grow"><button class="btn small ${S.slotMode === 'pick' ? 'on' : ''}" data-tap="mode:pick">Left-click</button><button class="btn small ${S.slotMode === 'right' ? 'on' : ''}" data-tap="mode:right">Right-click</button><button class="btn small ${S.slotMode === 'shift' ? 'on' : ''}" data-tap="mode:shift">Shift-click</button></div><button class="btn small" data-tap="set:menuInv">${S.settings.menuInv ? 'Hide inv' : 'Show inv'}</button></div>`
    : `<div class="modes"><button class="btn small ${S.slotMode === 'pick' ? 'on' : ''}" data-tap="mode:pick">Move</button><button class="btn small ${S.slotMode === 'shift' ? 'on' : ''}" data-tap="mode:shift">Quick ⇅</button><button class="btn small ${S.slotMode === 'drop' ? 'on' : ''}" data-tap="mode:drop">Drop</button><button class="btn small" data-tap="mirror:on">Picture</button></div>`;

  const cursor = sc.cursor ? `<div class="row" style="margin-top:var(--gap)"><span class="muted" style="font-size:.75rem">Holding</span><div style="width:2.4rem">${slotHTML(sc.cursor)}</div><span class="grow"></span><button class="btn small danger" data-tap="dropheld">Drop held</button></div>` : '';

  const h = `${modes}${cursor}
    <div style="margin-top:var(--gap)">${layout(container, scale)}</div>
    ${showInv && inv.length ? `<div class="inv-sep"></div>${layout(inv, scale)}` : ''}
    <div class="info">${info}</div>
    ${menu && picked && picked.it && !picked.p ? `<div class="grid g3" style="margin-top:var(--gap)"><button class="btn primary" data-tap="clickslot:${picked.id}:0:PICKUP">Left-click</button><button class="btn" data-tap="clickslot:${picked.id}:1:PICKUP">Right-click</button><button class="btn" data-tap="clickslot:${picked.id}:0:QUICK_MOVE">Shift-click</button></div>
      <p class="muted" style="font-size:.68rem;margin:.3rem .1rem 0">Servers decide what each click does — the item's description usually says (e.g. "Left-click: buy 1 · Right-click: sell").</p>` : ''}`;
  setHTML(main, h);
};

function slotTap(id, hold) {
  const sc = S.st && S.st.screen;
  if (!sc || sc.kind !== 'container') return;
  const slot = sc.slots.find(s => s.id === id);
  if (sc.menu && slot && !slot.p && !sc.cursor) {
    if (hold) { S.picked = id; schedule(); return cmd({ t: 'click', slot: id, button: 1, act: 'PICKUP' }); }
    if (S.settings.menuPreview && S.picked !== id && slot && slot.it) { S.picked = id; buzz(8); return schedule(); }
    S.picked = id;
    const act = S.slotMode === 'shift' ? ['QUICK_MOVE', 0] : S.slotMode === 'right' ? ['PICKUP', 1] : ['PICKUP', 0];
    buzz(15);
    schedule();
    return cmd({ t: 'click', slot: id, button: act[1], act: act[0] });
  }
  S.picked = id;
  schedule();
  buzz(10);
  if (S.slotMode === 'shift') return cmd({ t: 'click', slot: id, button: 0, act: 'QUICK_MOVE' });
  if (S.slotMode === 'drop') return cmd({ t: 'click', slot: id, button: hold ? 1 : 0, act: 'THROW' });
  return cmd({ t: 'click', slot: id, button: hold ? 1 : 0, act: 'PICKUP' });
}

// ---------------- MIRROR (any other menu, live picture you can touch)
let mirrorRunning = false, mirrorImg = null, mirrorLast = 0;
function startMirror(img) {
  mirrorImg = img;
  if (mirrorRunning) return;
  mirrorRunning = true;
  const fps = (S.st && S.st.cfg && S.st.cfg.mirrorFps) || 10;
  const loop = () => {
    if (!mirrorRunning || !mirrorImg || !document.body.contains(mirrorImg)) { mirrorRunning = false; return; }
    const wait = Math.max(0, 1000 / fps - (Date.now() - mirrorLast));
    setTimeout(() => {
      mirrorLast = Date.now();
      const next = new Image();
      next.onload = () => { if (mirrorImg) { mirrorImg.src = next.src; const w = mirrorImg.parentNode && mirrorImg.parentNode.querySelector('.wait'); if (w) w.remove(); } loop(); };
      next.onerror = () => setTimeout(loop, 300);
      next.src = '/mirror.png?crop=' + mirrorCrop() + '&t=' + mirrorLast;
    }, wait);
  };
  loop();
}
function stopMirror() { mirrorRunning = false; mirrorImg = null; }

function mirrorCrop() {
  const sc = S.st && S.st.screen;
  return S.mirrorZoom || (sc && sc.zoom) || 1;
}
function mirrorHTML(aspect) {
  return `<div class="mirror-wrap" id="mirror" style="aspect-ratio:${aspect || 16 / 9}"><img alt=""><div class="wait dots">Loading picture</div></div>`;
}
function bindMirror(root) {
  const wrap = $('#mirror', root);
  if (!wrap || wrap._bound) { if (wrap) startMirror($('img', wrap)); return; }
  wrap._bound = true;
  startMirror($('img', wrap));
  let down = false, lastMove = 0, startY = 0, scrolled = false;
  // touch position on the (possibly zoomed) picture -> position on the full top screen
  const pos = e => {
    const r = wrap.getBoundingClientRect(), c = mirrorCrop();
    const nx = clamp((e.clientX - r.left) / r.width, 0, 1), ny = clamp((e.clientY - r.top) / r.height, 0, 1);
    return { x: 0.5 + (nx - 0.5) * c, y: 0.5 + (ny - 0.5) * c };
  };
  wrap.addEventListener('pointerdown', e => {
    e.preventDefault();
    wrap.setPointerCapture(e.pointerId);
    down = true; scrolled = false; startY = e.clientY;
    const p = pos(e);
    cmd({ t: 'touch', kind: 'down', x: p.x, y: p.y, button: e.button === 2 ? 1 : 0 });
    buzz(8);
  });
  wrap.addEventListener('pointermove', e => {
    if (Date.now() - lastMove < 33) return;
    lastMove = Date.now();
    const p = pos(e);
    cmd({ t: 'touch', kind: 'move', x: p.x, y: p.y, button: 0 });
  });
  const up = e => {
    if (!down) return;
    down = false;
    const p = pos(e);
    cmd({ t: 'touch', kind: 'up', x: p.x, y: p.y, button: e.button === 2 ? 1 : 0 });
  };
  wrap.addEventListener('pointerup', up);
  wrap.addEventListener('pointercancel', up);
  wrap.addEventListener('wheel', e => { e.preventDefault(); const p = pos(e); cmd({ t: 'touch', kind: 'scroll', x: p.x, y: p.y, amount: e.deltaY > 0 ? -1 : 1 }); }, { passive: false });
  wrap.addEventListener('contextmenu', e => e.preventDefault());
}

VIEWS.mirror = (main, st) => {
  const sc = st.screen || {};
  const widgets = sc.widgets || [];
  const typing = sc.typing;
  layout(main, 'mirror:' + Math.round((sc.aspect || 1.78) * 100), `${mirrorHTML(sc.aspect)}<div id="mctl"></div>`);
  const h = `<div class="row" style="margin-top:var(--gap)">
      <button class="btn small grow" data-tap="scroll:1">▲ Scroll</button>
      <button class="btn small grow" data-tap="scroll:-1">▼ Scroll</button>
      <button class="btn small grow ${mirrorCrop() < 1 ? 'on' : ''}" data-tap="mzoom:${mirrorCrop() < 1 ? 1 : 0.55}">${mirrorCrop() < 1 ? 'Zoom out' : 'Zoom in'}</button>
      ${sc.kind === 'container' ? '<button class="btn small grow" data-tap="mirror:off">Slots</button>' : ''}
      ${widgets.length ? `<button class="btn small grow ${S.showWidgets ? 'on' : ''}" data-tap="widgets">Buttons (${widgets.length})</button>` : ''}
    </div>
    ${typing ? typeBar() : ''}
    ${S.showWidgets && widgets.length ? `<div class="wlist" style="margin-top:var(--gap)">${widgets.map(w =>
      `<button class="btn" ${w.a ? '' : 'disabled'} data-tap="widget:${w.i}">${w.type === 'check' ? (w.checked ? '☑ ' : '☐ ') : ''}${esc(w.type === 'text' ? '✎ ' + (w.value || w.label || 'Text') : w.label || '•')}</button>`).join('')}</div>` : ''}
    <p class="muted" style="font-size:.7rem;margin:.5rem .1rem">Touch the picture just like the top screen: tap, drag, and use the scroll buttons for lists.</p>`;
  setHTML($('#mctl', main), h);
  bindMirror(main);
  bindTypeBar(main);
};

function typeBar() {
  return `<div class="chatin" style="position:static"><input id="typein" placeholder="Type here…" autocomplete="off" autocapitalize="off"><button class="btn small" data-tap="typekey:backspace">⌫</button><button class="btn small primary" data-tap="typesend">↵</button></div>`;
}
function bindTypeBar(root) {
  const inp = $('#typein', root);
  if (!inp || inp._bound) return;
  inp._bound = true;
  keyboardFocus(inp);
  inp.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); act('typesend'); } });
}
function keyboardFocus(inp) {
  inp.addEventListener('focus', () => { if (Thor && Thor.setKeyboard) Thor.setKeyboard(true); });
  inp.addEventListener('blur', () => { if (Thor && Thor.setKeyboard) Thor.setKeyboard(false); });
  inp.addEventListener('pointerdown', () => { if (Thor && Thor.setKeyboard) Thor.setKeyboard(true); });
}

// ---------------- CHAT
VIEWS.chat = (main, st) => {
  const log = $('#chatlog', main);
  if (!log) {
    main._html = null;
    main.innerHTML = `<div class="chips" id="quick"></div><div class="h">Chat</div><div class="chatlog" id="chatlog"></div>
      <div class="chatin"><input id="chatin" placeholder="Message or /command" autocomplete="off" autocapitalize="off" enterkeyhint="send"><button class="btn primary" data-tap="chatsend">Send</button></div>`;
    const inp = $('#chatin', main);
    keyboardFocus(inp);
    inp.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); act('chatsend'); } });
    if (S.focusChat) { S.focusChat = false; setTimeout(() => inp.focus(), 50); }
  }
  setHTML($('#quick', main), S.quick.map((q, i) => `<button class="chip" data-tap="quick:${i}" data-hold="unquick:${i}">${esc(q)}</button>`).join('') + `<button class="chip" data-tap="addquick">＋</button>`);
  const atBottom = main.scrollHeight - main.scrollTop - main.clientHeight < 60;
  if (setHTML($('#chatlog', main), S.chat.slice(-120).map(m => `<div class="ln">${segs(m.s)}</div>`).join('') || '<div class="muted">No messages yet.</div>') && atBottom) {
    main.scrollTop = main.scrollHeight;
  }
};

// ---------------- KEYS
VIEWS.keys = (main) => {
  if (!S.keys) {
    setHTML(main, '<div class="waiting dots">Loading keys</div>');
    fetch('/api/keys').then(r => r.json()).then(k => { S.keys = k; main._html = null; schedule(); }).catch(() => { });
    return;
  }
  let box = $('#keybox', main);
  if (!box) {
    main._html = null;
    main.innerHTML = `<input class="search" id="keysearch" placeholder="Search keys (e.g. map, cobblemon, emote)" value="${esc(S.keyFilter)}"><div id="keybox"></div>`;
    const inp = $('#keysearch', main);
    keyboardFocus(inp);
    inp.addEventListener('input', () => { S.keyFilter = inp.value; schedule(); });
    box = $('#keybox', main);
  }
  const f = S.keyFilter.trim().toLowerCase();
  const groups = {};
  for (const k of S.keys) {
    if (f && !(k.name + ' ' + k.cat + ' ' + k.id).toLowerCase().includes(f)) continue;
    (groups[k.cat] = groups[k.cat] || []).push(k);
  }
  const pinned = new Set(S.pins.map(p => p.id));
  setHTML(box, Object.entries(groups).map(([cat, ks]) => `<div class="h">${esc(cat)}</div>` + ks.map(k =>
    `<div class="keyrow ${pinned.has(k.id) ? 'pinned' : ''}" data-tap="key:${esc(k.id)}" data-hold="pin:${esc(k.id)}"><div class="grow">${esc(k.name)}<div class="k">${esc(k.bound)}</div></div><span class="muted">${pinned.has(k.id) ? '★' : '☆'}</span></div>`).join('')).join('')
    + '<p class="muted" style="font-size:.72rem">Tap = press · Hold = pin to Home</p>');
};

// ---------------- PARTY + SUMMARY (Cobblemon)
VIEWS.party = (main, st) => {
  const cb = st.cobble;
  if (!cb) return setHTML(main, `<div class="empty-state"><div class="big">◓</div><p>Cobblemon isn't installed.</p></div>`);
  if (S.summary) return renderSummary(main);
  const party = cb.party || [];
  const cards = [];
  for (let i = 0; i < 6; i++) {
    const p = party[i];
    if (!p) { cards.push(`<div class="pk empty">—</div>`); continue; }
    cards.push(`<div class="pk ${i === cb.selected ? 'sel' : ''} ${p.hp <= 0 ? 'faint' : ''}" data-tap="pk:${i}" data-hold="pksum:${i}">
      <div class="pic">${icon(p.icon)}</div>
      <div class="grow"><div class="nm">${esc(p.name)} ${genderIcon(p.gender)}</div>
        <div class="lv">Lv ${p.level} ${statusBadge(p.hp <= 0 ? 'fnt' : p.status)} ${p.held ? '· ' + esc(plain(p.held.name)) : ''}</div>
        ${hpBar(p.hp, p.maxHp)}<div class="hpn">${p.hp}/${p.maxHp}</div></div>
      ${p.shiny ? '<span class="star">✦</span>' : ''}</div>`);
  }
  const sel = party[cb.selected];
  setHTML(main, `<div class="party">${cards.join('')}</div>
    <div class="grid g3" style="margin-top:var(--gap)">
      <button class="btn primary" data-tap="sendout">${sel ? 'Send / recall' : 'Send out'}</button>
      <button class="btn" data-tap="pksum:${cb.selected}">Summary</button>
      <button class="btn" data-tap="pokedex">Pokédex</button>
    </div>
    <p class="muted" style="font-size:.72rem;margin:.5rem .1rem">Tap to select · hold for the summary.</p>
    <div class="h">Interact (battle · trade · team up · spectate)</div>
    ${(cb.nearby || []).length ? (cb.nearby || []).map(n => `<div class="li ${n.reach ? '' : 'no'}" data-tap="interact:${n.uuid}">
        <div class="pic">${n.kind === 'player' ? gi('minecraft:player_head') : icon(n.icon)}</div>
        <div class="grow"><b>${esc(n.name)}</b> <span class="muted">${n.kind === 'player' ? 'player' : 'your Pokémon'}</span></div>
        <span class="mono muted">${n.dist}m</span></div>`).join('') : '<p class="muted" style="font-size:.78rem">Nobody close by.</p>'}
    <button class="btn" style="width:100%;margin-top:var(--gap)" data-tap="interactLook">${gl('R')} Interact with what I'm looking at</button>`);
};
const genderIcon = g => g === 'MALE' ? '<span style="color:#6cb6ff">♂</span>' : g === 'FEMALE' ? '<span style="color:#ff8fc8">♀</span>' : '';
const statusBadge = s => {
  if (!s) return '';
  const k = String(s).toLowerCase().replace(/^.*:/, '');
  const map = { par: 'PAR', paralysis: 'PAR', brn: 'BRN', burn: 'BRN', psn: 'PSN', poison: 'PSN', tox: 'TOX', poisonbadly: 'TOX', slp: 'SLP', sleep: 'SLP', frz: 'FRZ', frozen: 'FRZ', fnt: 'FNT' };
  const label = map[k] || k.slice(0, 3).toUpperCase();
  return `<span class="st ${label.toLowerCase()}">${label}</span>`;
};
function hpBar(hp, max, ratio) {
  const r = ratio != null ? ratio : (max > 0 ? hp / max : 0);
  const cls = r > 0.5 ? '' : r > 0.2 ? 'mid' : 'low';
  return `<div class="bar ${cls}"><i style="width:${clamp(r * 100, 0, 100)}%"></i></div>`;
}

function openSummary(slot) {
  S.summary = { slot, data: null, tab: (S.summary && S.summary.tab) || 'info' };
  S.tab = 'party';
  S.dismissed = ctxSig();
  schedule();
  fetch('/api/summary?slot=' + slot).then(r => r.json()).then(d => { if (S.summary && S.summary.slot === slot) { S.summary.data = d; $('#main')._html = null; schedule(); } }).catch(() => { });
}
function renderSummary(main) {
  const sm = S.summary, d = sm.data;
  if (!d) return setHTML(main, '<div class="waiting dots">Loading summary</div>');
  const types = (d.types || []).map(typeBadge).join(' ');
  let body = '';
  if (sm.tab === 'info') {
    body = `<div class="card kv">
      <div><span>Dex</span><span>#${String(d.dex).padStart(4, '0')}</span></div>
      <div><span>Level</span><span>${d.level}</span></div>
      <div><span>Nature</span><span>${esc(d.nature || '—')}</span></div>
      <div><span>Ability</span><span>${esc(d.ability || '—')}</span></div>
      <div><span>Held</span><span>${d.held ? esc(plain(d.held.name)) : '—'}</span></div>
      <div><span>Friendship</span><span>${d.friendship ?? '—'}</span></div>
      <div><span>EXP</span><span>${d.exp ?? '—'}${d.expToNext != null ? ' (+' + d.expToNext + ')' : ''}</span></div>
      <div><span>OT</span><span>${esc(d.ot || '—')}</span></div>
      ${d.tera ? `<div><span>Tera</span><span>${esc(d.tera)}</span></div>` : ''}
      ${d.ball ? `<div><span>Ball</span><span>${esc(String(d.ball).replace(/^.*:/, '').replace(/_/g, ' '))}</span></div>` : ''}
    </div>
    ${d.abilityDesc ? `<div class="card" style="font-size:.8rem"><b>${esc(d.ability)}</b><br><span class="muted">${esc(d.abilityDesc)}</span></div>` : ''}
    ${d.desc ? `<div class="card muted" style="font-size:.8rem">${esc(d.desc)}</div>` : ''}`;
  } else if (sm.tab === 'stats') {
    const names = { hp: 'HP', attack: 'Atk', defence: 'Def', special_attack: 'SpA', special_defence: 'SpD', speed: 'Spe' };
    body = `<div class="card">${Object.entries(names).map(([k, n]) => {
      const s = (d.stats || {})[k] || {};
      return `<div class="stat"><b>${n}</b><span class="mono">${s.v ?? '?'}</span><div class="bar"><i style="width:${clamp((s.v || 0) / 2.55, 2, 100)}%;background:${statColor(s.v || 0)}"></i></div><span class="muted mono" style="font-size:.68rem">${s.iv ?? '?'}iv ${s.ev ?? '?'}ev</span></div>`;
    }).join('')}</div>`;
  } else {
    body = (d.moves || []).map(m => `<div class="card" style="border-left:4px solid ${typeColor(m.type)}">
      <div class="row"><b class="grow">${esc(m.name)}</b>${typeBadge(m.type)}</div>
      <div class="row muted" style="font-size:.72rem;margin-top:.2rem"><span>${esc(m.cat || '')}</span><span>Pow ${m.power || '—'}</span><span>Acc ${m.acc || '—'}</span><span class="grow" style="text-align:right">PP ${m.pp}/${m.maxPp}</span></div>
      ${m.desc ? `<div style="font-size:.74rem;margin-top:.25rem">${esc(m.desc)}</div>` : ''}</div>`).join('') || '<p class="muted">No moves.</p>';
  }
  setHTML(main, `<div class="sumhead"><div class="pic">${icon(d.icon)}</div>
      <div class="grow"><div style="font-weight:900;font-size:1.1rem">${esc(d.name)} ${genderIcon(d.gender)} ${d.shiny ? '<span style="color:#ffe066">✦</span>' : ''}</div>
      <div class="muted" style="font-size:.78rem">${esc(d.species)} · Lv ${d.level} ${statusBadge(d.status)}</div>
      <div style="margin-top:.3rem">${types}</div>
      <div style="margin-top:.35rem">${hpBar(d.hp, d.maxHp)}<div class="muted mono" style="font-size:.7rem;text-align:right">${d.hp}/${d.maxHp}</div></div></div></div>
    <div class="seg-tabs">${['info', 'stats', 'moves'].map(t => `<button class="${sm.tab === t ? 'on' : ''}" data-tap="sumtab:${t}">${t[0].toUpperCase() + t.slice(1)}</button>`).join('')}</div>
    ${body}
    <div class="grid g3" style="margin-top:var(--gap)"><button class="btn" data-tap="sumnav:-1">◀</button><button class="btn" data-tap="sumclose">Party</button><button class="btn" data-tap="sumnav:1">▶</button></div>
    <button class="btn" style="width:100%;margin-top:var(--gap)" data-tap="sumgame:${sm.slot}">Open Cobblemon summary on top screen</button>`);
}
const statColor = v => v >= 150 ? '#3ddc84' : v >= 90 ? '#b6e35a' : v >= 60 ? '#ffcc33' : '#ff7a4d';

// ---------------- BATTLE
const TYPE_COLORS = { normal: '#9fa19f', fire: '#e62829', water: '#2980ef', electric: '#fac000', grass: '#3fa129', ice: '#3dcef3', fighting: '#ff8000', poison: '#9141cb', ground: '#915121', flying: '#81b9ef', psychic: '#ef4179', bug: '#91a119', rock: '#afa981', ghost: '#704170', dragon: '#5060e1', dark: '#624d4e', steel: '#60a1b8', fairy: '#ef70ef', stellar: '#40b5a5' };
const typeColor = t => TYPE_COLORS[t] || '#555';
const typeBadge = t => t ? `<span class="type" style="background:${typeColor(t)}">${esc(t)}</span>` : '';
// attacking type -> defending type -> multiplier (only non-1 entries)
const CHART = {
  normal: { rock: .5, ghost: 0, steel: .5 },
  fire: { fire: .5, water: .5, grass: 2, ice: 2, bug: 2, rock: .5, dragon: .5, steel: 2 },
  water: { fire: 2, water: .5, grass: .5, ground: 2, rock: 2, dragon: .5 },
  electric: { water: 2, electric: .5, grass: .5, ground: 0, flying: 2, dragon: .5 },
  grass: { fire: .5, water: 2, grass: .5, poison: .5, ground: 2, flying: .5, bug: .5, rock: 2, dragon: .5, steel: .5 },
  ice: { fire: .5, water: .5, grass: 2, ice: .5, ground: 2, flying: 2, dragon: 2, steel: .5 },
  fighting: { normal: 2, ice: 2, poison: .5, flying: .5, psychic: .5, bug: .5, rock: 2, ghost: 0, dark: 2, steel: 2, fairy: .5 },
  poison: { grass: 2, poison: .5, ground: .5, rock: .5, ghost: .5, steel: 0, fairy: 2 },
  ground: { fire: 2, electric: 2, grass: .5, poison: 2, flying: 0, bug: .5, rock: 2, steel: 2 },
  flying: { electric: .5, grass: 2, fighting: 2, bug: 2, rock: .5, steel: .5 },
  psychic: { fighting: 2, poison: 2, psychic: .5, dark: 0, steel: .5 },
  bug: { fire: .5, grass: 2, fighting: .5, poison: .5, flying: .5, psychic: 2, ghost: .5, dark: 2, steel: .5, fairy: .5 },
  rock: { fire: 2, ice: 2, fighting: .5, ground: .5, flying: 2, bug: 2, steel: .5 },
  ghost: { normal: 0, psychic: 2, ghost: 2, dark: .5 },
  dragon: { dragon: 2, steel: .5, fairy: 0 },
  dark: { fighting: .5, psychic: 2, ghost: 2, dark: .5, fairy: .5 },
  steel: { fire: .5, water: .5, electric: .5, ice: 2, rock: 2, steel: .5, fairy: 2 },
  fairy: { fire: .5, fighting: 2, poison: .5, dragon: 2, dark: 2, steel: .5 },
};
function effectiveness(moveType, defTypes) {
  if (!moveType || !defTypes || !defTypes.length || !CHART[moveType]) return null;
  return defTypes.reduce((m, t) => m * (CHART[moveType][t] ?? 1), 1);
}
const effShort = m => m == null ? '?' : m === 0 ? '0×' : m >= 4 ? '4×' : m >= 2 ? '2×' : m <= 0.25 ? '¼×' : m < 1 ? '½×' : '1×';
const effLabel = m => m == null ? '' : m === 0 ? 'No effect' : m >= 4 ? '×4' : m >= 2 ? 'Super' : m <= 0.25 ? '×¼' : m < 1 ? 'Weak' : '';

function bmonHTML(m, active) {
  const hpTxt = m.hp != null ? `${m.hp}/${m.maxHp}` : Math.round(m.ratio * 100) + '%';
  return `<div class="bmon ${m.mine ? '' : 'foe'} ${active ? 'act' : ''}"><div class="pic">${icon(m.icon)}</div>
    <div class="grow"><div class="row"><span class="nm grow">${esc(m.name)}</span><span class="lvl">Lv${m.level}</span></div>
    <div class="meta">${(m.types || []).map(typeBadge).join('')}${statusBadge(m.status)}<span class="grow"></span><span class="mono">${hpTxt}</span></div>
    ${hpBar(m.hp, m.maxHp, m.ratio)}</div></div>`;
}
const gl = b => `<span class="gl">${b}</span>`;
/** Where each face button physically sits. AYN Thor ships with the Nintendo layout. */
const PAD_POS = {
  nintendo: { X: 'top', Y: 'left', A: 'right', B: 'bottom' },
  xbox: { Y: 'top', X: 'left', B: 'right', A: 'bottom' },
};
const padPos = btn => (PAD_POS[S.settings.padLayout] || PAD_POS.nintendo)[btn];

VIEWS.battle = (main, st) => {
  const b = st.cobble.battle;
  const foes = b.foes || [], allies = b.allies || [];
  let page = '';
  if (!b.choosing) {
    page = `<div class="waiting"><span class="dots">${b.page === 'WAIT' ? 'Waiting for the turn' : 'Waiting'}</span></div>`;
  } else if (b.page === 'MAIN' || b.page === 'RUN_CONFIRM') {
    const confirm = b.page === 'RUN_CONFIRM';
    page = `<div class="diamond">
      <button class="dbtn ${b.wild ? 'catch' : 'mons'} d-${padPos('X')}" data-tap="${b.wild ? 'bt:catch' : 'bt:switchPage'}">${gl('X')}${b.wild ? 'Catch' : 'Pokémon'}</button>
      <button class="dbtn bag d-${padPos('Y')}" data-tap="bt:bag">${gl('Y')}Bag</button>
      ${b.wild ? `<div class="d-mid"><button class="btn small" data-tap="bt:switchPage">${gl('R')} Pokémon</button></div>` : ''}
      <button class="dbtn fight d-${padPos('A')}" data-tap="bt:fight">${gl('A')}Fight</button>
      <button class="dbtn run d-${padPos('B')}" data-tap="${confirm ? 'bt:run' : 'btpress:B'}">${gl('B')}${confirm ? 'Run?' : 'Run'}${confirm ? '<small>tap / B again</small>' : ''}</button>
    </div>`;
  } else if (b.page === 'FIGHT') {
    page = `<div class="moves diamond2">${b.moves.map((m, i) => {
      const lbl = m.cat === 'status' ? '' : foes.length > 1
        ? foes.map(f => effShort(effectiveness(m.type, f.types))).join(' | ')
        : effLabel(effectiveness(m.type, foes[0] && foes[0].types));
      return `<button class="mv m-${padPos(m.btn)} ${m.disabled || m.pp === 0 ? 'dis' : ''}" style="background:linear-gradient(160deg, ${typeColor(m.type)}, ${shade(typeColor(m.type))})" data-tap="bt:move:${i}">
        ${lbl ? `<span class="eff">${lbl}</span>` : ''}
        <div class="top">${gl(m.btn)}<span class="nm">${esc(m.name)}</span></div>
        <div class="sub"><span>${esc(m.type || '')}${m.cat && m.cat !== 'status' ? ' · ' + (m.power || '—') : m.cat === 'status' ? ' · status' : ''}</span><span>PP ${m.pp}/${m.maxPp}</span></div></button>`;
    }).join('')}<button class="btn small mv-back" data-tap="bt:back">${gl('L')} Back</button></div>`;
  } else {
    const titles = { SWITCH: b.forceSwitch ? 'Choose your next Pokémon' : 'Switch Pokémon', TARGET: 'Choose a target', BALL: 'Throw which ball?' };
    page = `<div class="h">${titles[b.page] || ''}</div><div class="blist">${(b.list || []).map((o, i) => {
      const cur = i === b.cursor ? 'cur' : '';
      if (b.page === 'BALL') return `<div class="li ${cur}" data-tap="bt:pick:${i}"><div class="pic">${icon(o.icon)}</div><div class="grow"><b>${esc(o.name)}</b></div><span class="mono">×${o.count}</span></div>`;
      if (b.page === 'TARGET') return `<div class="li ${cur}" data-tap="bt:pick:${i}"><div class="pic">${icon(o.icon)}</div><div class="grow"><b>${esc(o.name)}</b> <span class="muted">${o.mine ? 'ally' : 'foe'}</span>${hpBar(o.hp, o.maxHp, o.ratio)}</div></div>`;
      return `<div class="li ${cur} ${o.ok ? '' : 'no'}" data-tap="bt:pick:${i}"><div class="pic">${icon(o.icon)}</div><div class="grow"><b>${esc(o.name)}</b> <span class="muted">Lv ${o.level}</span> ${statusBadge(o.hp <= 0 ? 'fnt' : o.status)}${hpBar(o.hp, o.maxHp)}</div><span class="mono muted">${o.hp}/${o.maxHp}</span></div>`;
    }).join('')}</div>
    <div class="hint">${gl('L')}${gl('R')} move · ${gl('A')} choose · ${b.page === 'SWITCH' && b.forceSwitch ? '' : `<button class="btn small" data-tap="bt:back">${gl('B')} Back</button>`}</div>`;
  }
  const showMirror = S.battleMirror || (b.bagMirror && b.guiOpen);
  const doubles = foes.length > 1 || allies.length > 1;
  const choosingFor = b.requestCount > 1 && b.activeName ? `Choosing for ${b.activeName} (${b.requestIndex}/${b.requestCount})` : '';
  const h = `<div class="bside ${doubles ? 'two' : ''}">${foes.map(m => bmonHTML(m)).join('')}</div>
    <div class="bside ${doubles ? 'two' : ''}" style="margin-top:.3rem">${allies.map(m => bmonHTML(m, doubles && m.pnx === b.activePnx)).join('')}</div>
    <div class="bt-toast">${esc(b.toast || choosingFor)}</div>
    ${page}
    ${b.pad ? '' : `<p class="muted" style="font-size:.7rem;text-align:center;margin-top:.6rem">Physical ABXY not set up yet — <button class="btn small" data-tap="calibrate">Controller setup</button></p>`}`;
  layout(main, 'battle', '<div id="bt"></div><div id="blog" class="blog" data-tap="blogsize"></div><div id="bmir"></div>');
  setHTML($('#bt', main), h);
  const logEl = $('#blog', main);
  const lines = (b.log || []).slice(-30);
  if (setHTML(logEl, lines.length ? lines.map(l => `<div class="ll">${logLine(l, foes, allies)}</div>`).join('')
      : '<div class="ll muted">The battle log will appear here.</div>')) logEl.scrollTop = logEl.scrollHeight;
  setHTML($('#bmir', main), showMirror ? `<div class="h">Battle screen — tap it like the top screen</div>${mirrorHTML(st.screen && st.screen.aspect)}` : '');
  if (showMirror) bindMirror(main); else stopMirror();
};
/** Battle log line: keep Cobblemon's own colours, and highlight names / effectiveness / crits / faints in plain text. */
function logLine(segsArr, foes, allies) {
  const names = [...foes.map(f => [f.name, 'lf']), ...allies.map(a => [a.name, 'la'])]
    .filter(([n]) => n && n.length > 1).sort((x, y) => y[0].length - x[0].length);
  const rx = s => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const rules = [
    [/(super effective)/gi, 'good'],
    [/(not very effective|doesn't affect|does not affect|had no effect|missed|avoided the attack)/gi, 'weak'],
    [/(critical hit)/gi, 'crit'],
    [/(fainted)/gi, 'faint'],
    [/(\bused )([^!.]+)/gi, 'move'],
  ];
  const whole = plain(segsArr);
  let cls = '';
  if (/fainted/i.test(whole)) cls = 'l-faint';
  else if (/super effective|critical hit/i.test(whole)) cls = 'l-hit';
  return `<span class="${cls}">` + segsArr.map(sg => {
    const colored = sg.c && sg.c.toUpperCase() !== '#FFFFFF';
    let t = esc(sg.t);
    if (!colored) {
      for (const [n, c] of names) t = t.replace(new RegExp('(' + rx(esc(n)) + ')', 'g'), `<b class="${c}">$1</b>`);
      for (const [re, c] of rules) t = c === 'move' ? t.replace(re, '$1<b class="mvn">$2</b>') : t.replace(re, `<b class="${c}">$1</b>`);
    }
    let st = '';
    if (sg.c) st += `color:${sg.c};`;
    if (sg.b) st += 'font-weight:900;';
    if (sg.it) st += 'font-style:italic;';
    return st ? `<span style="${st}">${t}</span>` : t;
  }).join('') + '</span>';
}
function shade(hex) {
  const n = parseInt(hex.slice(1), 16);
  const f = v => Math.max(0, Math.round(v * 0.6));
  return `rgb(${f(n >> 16)},${f((n >> 8) & 255)},${f(n & 255)})`;
}

// ---------------- MAP
let mapTimer = null, mapData = null;
VIEWS.map = (main, st) => {
  if (!$('#mapc', main)) {
    main._html = null;
    main.innerHTML = `<div class="mapbox"><canvas id="mapc"></canvas></div>
      <div class="legend"><span><i style="background:#fff"></i>You</span><span><i style="background:#4da3ff"></i>Player</span><span><i style="background:#3ddc84"></i>Pokémon</span><span><i style="background:#ffe066"></i>Shiny</span><span><i style="background:#ff4d4d"></i>Hostile</span></div>
      <div class="h">Nearby Pokémon</div><div id="nearby"></div>`;
  }
  ensureMapPolling();
  if (mapData) drawMap();
};
function ensureMapPolling() {
  if (mapTimer) return;
  const tick = () => {
    if (currentView() !== 'home') { mapTimer = null; return; }
    fetch('/api/map').then(r => r.json()).then(d => { mapData = d; drawMap(); }).catch(() => { }).finally(() => { mapTimer = setTimeout(tick, 1000); });
  };
  mapTimer = setTimeout(tick, 0);
}
function drawMap() {
  const big = $('#mapc'), small = $('#homemapc');
  if (big) drawMapInto(big, 1, true);
  if (small) drawMapInto(small, S.mapBig ? 1 : 1.6, !!S.mapBig);
}
/** zoom > 1 shows a closer area; the canvas may be wider than tall (map is cropped to fit). */
function drawMapInto(c, zoom, withList) {
  const d = mapData;
  if (!c || !d || !d.img) return;
  const size = d.size;
  const dpr = window.devicePixelRatio || 1;
  const W = Math.round(c.clientWidth * dpr), H = Math.round(c.clientHeight * dpr);
  if (!W || !H) return;
  c.width = W; c.height = H;
  const px = W * zoom; // size of the whole map image on the canvas
  const g = c.getContext('2d');
  const raw = atob(d.img);
  const img = g.createImageData(size, size);
  for (let i = 0, j = 0; i < size * size; i++, j += 3) {
    img.data[i * 4] = raw.charCodeAt(j); img.data[i * 4 + 1] = raw.charCodeAt(j + 1); img.data[i * 4 + 2] = raw.charCodeAt(j + 2); img.data[i * 4 + 3] = 255;
  }
  const off = document.createElement('canvas');
  off.width = size; off.height = size;
  off.getContext('2d').putImageData(img, 0, 0);
  g.imageSmoothingEnabled = false;
  const cx = W / 2, cy = H / 2;
  g.fillStyle = '#050506'; g.fillRect(0, 0, W, H);
  g.drawImage(off, cx - px / 2, cy - px / 2, px, px);
  const k = px / size;
  const nearby = [];
  for (const e of d.ents || []) {
    const x = cx + e.dx * k, y = cy + e.dz * k;
    const col = e.k === 'player' ? '#4da3ff' : e.k === 'hostile' ? '#ff4d4d' : e.shiny ? '#ffe066' : '#3ddc84';
    g.beginPath(); g.arc(x, y, Math.max(3, k * (e.shiny ? 1.4 : 0.9)), 0, Math.PI * 2);
    g.fillStyle = col; g.fill(); g.lineWidth = 1.5; g.strokeStyle = '#000'; g.stroke();
    if (e.k === 'player' || e.shiny) { g.fillStyle = '#fff'; g.font = `${Math.round(W / 30)}px system-ui`; g.fillText(e.n, x + 6, y - 6); }
    if (e.k === 'pokemon') nearby.push(e);
  }
  // player arrow (yaw 0 = south / down)
  g.save(); g.translate(cx, cy); g.rotate((d.yaw + 180) * Math.PI / 180);
  const a = W / 28;
  g.beginPath(); g.moveTo(0, -a); g.lineTo(a * 0.6, a * 0.7); g.lineTo(0, a * 0.4); g.lineTo(-a * 0.6, a * 0.7); g.closePath();
  g.fillStyle = '#fff'; g.fill(); g.strokeStyle = '#000'; g.lineWidth = 2; g.stroke(); g.restore();
  nearby.sort((a, b) => (a.dx * a.dx + a.dz * a.dz) - (b.dx * b.dx + b.dz * b.dz));
  const el = withList && $('#nearby');
  if (el) setHTML(el, nearby.slice(0, 12).map(e => `<div class="keyrow"><span style="color:${e.shiny ? '#ffe066' : '#3ddc84'}">${e.shiny ? '✦' : '●'}</span><div class="grow">${esc(e.n)} ${e.wild ? '' : '<span class="muted">(owned)</span>'}</div><span class="mono muted">${Math.round(Math.hypot(e.dx, e.dz))}m ${dirOf(e.dx, e.dz)}</span></div>`).join('') || '<p class="muted">None in range.</p>');
}
const dirOf = (dx, dz) => { const a = (Math.atan2(dx, -dz) * 180 / Math.PI + 360) % 360; return ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'][Math.round(a / 45) % 8]; };

// ---------------- SETTINGS / MORE
VIEWS.settings = (main, st) => {
  const cfg = st.cfg || {};
  const sw = (on, tap, label, sub) => `<div class="set" data-tap="${tap}"><div class="grow">${label}${sub ? `<small>${sub}</small>` : ''}</div><span class="sw ${on ? 'on' : ''}"></span></div>`;
  setHTML(main, `
    <div class="h">Game</div>
    ${sw(cfg.hideMenusOnTop, 'cfg:hideMenusOnTop', 'Menus only on bottom screen', 'Inventories, chests and server GUIs leave the top screen showing the world')}
    ${st.cobblemon ? `${sw(cfg.padInBattle, 'cfg:padInBattle', 'ABXY battle controls', 'Physical buttons drive the Cobblemon battle menu')}
    ${sw(cfg.interceptMouse, 'cfg:interceptMouse', 'Pad buttons that send mouse clicks', 'Only if your launcher maps A/B to mouse buttons in menus')}
    ${sw(cfg.catchCloseGui, 'cfg:catchCloseGui', 'Close battle UI when throwing', 'Turn on only if Catch does nothing')}
    <div class="set"><div class="grow">Controller setup<small>Teach ThorCraft your A B X Y L R (${esc(cfg.padButtons || 'not set')})</small></div><button class="btn small" data-tap="calibrate">Set up</button></div>
    <div class="set"><div class="grow">Face button layout<small>Where A B X Y sit on your device (AYN Thor = Nintendo)</small></div><button class="btn small ${S.settings.padLayout !== 'xbox' ? 'on' : ''}" data-tap="padlayout:nintendo">Nintendo</button><button class="btn small ${S.settings.padLayout === 'xbox' ? 'on' : ''}" data-tap="padlayout:xbox">Xbox</button></div>` : ''}
    <div class="set"><div class="grow">Menu picture FPS<small>Higher = smoother, more battery</small></div><button class="btn small" data-tap="cfgnum:mirrorFps:-2">−</button><b class="mono">${cfg.mirrorFps}</b><button class="btn small" data-tap="cfgnum:mirrorFps:2">＋</button></div>
    <div class="set"><div class="grow">Map radius<small>Blocks around you</small></div><button class="btn small" data-tap="cfgnum:mapRadius:-8">−</button><b class="mono">${cfg.mapRadius}</b><button class="btn small" data-tap="cfgnum:mapRadius:8">＋</button></div>
    <div class="h">Theme</div>
    <div class="themes">${THEMES.map(t => `<button class="theme ${S.settings.theme === t.id ? 'on' : ''}" data-tap="theme:${t.id}"><span class="sw4">${t.c.map(col => `<i style="background:${col}"></i>`).join('')}</span>${t.name}</button>`).join('')}</div>
    <div class="h">Panel</div>
    ${sw(S.settings.auto, 'set:auto', 'Follow the game', st.cobblemon ? 'Jump to battles, chests and menus automatically' : 'Jump to chests and menus automatically')}
    ${sw(S.settings.autoPicture, 'set:autoPicture', 'Server menus with custom art: show as picture', 'Menus a server resource pack paints over open as the real screen (tap Slots to switch)')}
    ${sw(S.settings.menuPreview, 'set:menuPreview', 'Server menus: preview first', 'First tap shows the item, second tap clicks it')}
    ${sw(S.settings.hideFillers, 'set:hideFillers', 'Dim glass-pane fillers in server menus')}
    ${sw(S.settings.haptics, 'set:haptics', 'Vibration', st.cobblemon ? 'Damage, battle start, shiny alerts' : 'When you take damage')}
    <div class="set"><div class="grow">Size</div><button class="btn small" data-tap="zoom:-0.1">−</button><b class="mono">${Math.round(S.settings.zoom * 100)}%</b><button class="btn small" data-tap="zoom:0.1">＋</button></div>
    <div class="grid g3" style="margin-top:var(--gap)">
      <button class="btn" data-tap="reload">Reload</button>
      <button class="btn" data-tap="debug">Debug</button>
      ${Thor && Thor.hide ? '<button class="btn danger" data-tap="hidepanel">Hide</button>' : '<span></span>'}
    </div>
    ${S.debug ? `<pre class="debug">${esc(JSON.stringify(S.debug, null, 1))}</pre>` : ''}
    <p class="muted" style="font-size:.7rem;text-align:center;margin-top:1rem">ThorCraft by Leymo</p>`);
};

// ------------------------------------------------------------------ actions
function act(a, hold) {
  const [name, ...rest] = a.split(':');
  const arg = rest.join(':');
  const st = S.st;
  switch (name) {
    case 'tab': S.tab = arg; store.set('tab', arg); S.summary = null; if (gameContext()) S.dismissed = ctxSig(); break;
    case 'ctx':
      if (arg === 'leave') S.dismissed = ctxSig();
      else { S.dismissed = null; S.summary = null; }
      break;
    case 'hotbar': cmd({ t: 'hotbar', i: +arg }); buzz(8); break;
    case 'key': cmd({ t: 'key', id: arg }); buzz(12); break;
    case 'toggle': cmd({ t: 'key', id: arg, mode: 'toggle' }); buzz(12); break;
    case 'inventory': case 'perspective': case 'hud': case 'screenshot': case 'pause': case 'sneak': cmd({ t: name }); buzz(12); break;
    case 'interact': cmd({ t: 'party', op: 'interact', uuid: arg }); buzz(15); toast('Opening the interaction wheel…'); break;
    case 'interactLook': cmd({ t: 'party', op: 'interactLook' }); buzz(15); break;
    case 'mzoom': S.mirrorZoom = +arg; break;
    case 'theme': S.settings.theme = arg; saveSettings(); buzz(15); break;
    case 'padlayout': S.settings.padLayout = arg; saveSettings(); break;
    case 'blogsize': { const el = $('#blog'); if (el) { el.classList.toggle('big'); el.scrollTop = el.scrollHeight; } return; }
    case 'mapbig': S.mapBig = !S.mapBig; buzz(10); break;
    case 'sharecoords': { const p = st.player; cmd({ t: 'chat', text: `${p.x} ${p.y} ${p.z}` }); toast('Coordinates sent to chat'); break; }
    case 'openChatTab': S.tab = 'chat'; S.focusChat = true; $('#main')._html = null; break;
    case 'pin': {
      const k = (S.keys || []).find(k => k.id === arg);
      if (S.pins.some(p => p.id === arg)) S.pins = S.pins.filter(p => p.id !== arg);
      else if (k) S.pins.push({ id: k.id, name: k.name });
      store.set('pins', S.pins); buzz(25); break;
    }
    case 'unpin': S.pins = S.pins.filter(p => p.id !== arg); store.set('pins', S.pins); buzz(25); break;
    case 'screen': if (arg === 'close') cmd({ t: 'close' }); S.viewChoice = null; S.picked = null; S.mirrorZoom = 0; break;
    case 'slot': slotTap(+arg, false); break;
    case 'slothold': slotTap(+arg, true); break;
    case 'clickslot': { const [id, btn, ac] = arg.split(':'); cmd({ t: 'click', slot: +id, button: +btn, act: ac }); buzz(15); break; }
    case 'mode': S.slotMode = arg; break;
    case 'dropheld': cmd({ t: 'click', slot: -999, button: 0, act: 'PICKUP' }); break;
    case 'mirror': { const sc = st.screen; S.viewChoice = { key: sc.cls + ':' + sc.syncId, picture: arg === 'on' }; $('#main')._html = null; $('#main').dataset.layout = ''; break; }
    case 'widgets': S.showWidgets = !S.showWidgets; break;
    case 'widget': cmd({ t: 'widget', i: +arg }); buzz(12); break;
    case 'scroll': cmd({ t: 'touch', kind: 'scroll', x: 0.5, y: 0.5, amount: +arg }); break;
    case 'typekey': cmd({ t: 'press', key: arg }); break;
    case 'typesend': {
      const inp = $('#typein');
      if (inp && inp.value) { cmd({ t: 'type', text: inp.value }); inp.value = ''; }
      else cmd({ t: 'press', key: 'enter' });
      break;
    }
    case 'chatsend': {
      const inp = $('#chatin');
      if (inp && inp.value.trim()) { cmd({ t: 'chat', text: inp.value }); inp.value = ''; buzz(15); }
      break;
    }
    case 'quick': cmd({ t: 'chat', text: S.quick[+arg] }); buzz(15); toast('Sent ' + S.quick[+arg]); break;
    case 'unquick': S.quick.splice(+arg, 1); store.set('quick', S.quick); buzz(25); break;
    case 'addquick': {
      const inp = $('#chatin');
      const v = inp && inp.value.trim();
      if (v) { S.quick.push(v); store.set('quick', S.quick); inp.value = ''; toast('Saved as a quick command'); }
      else toast('Type a command in the box first, then tap ＋');
      break;
    }
    case 'pk': cmd({ t: 'party', op: 'select', slot: +arg }); buzz(10); break;
    case 'pksum': openSummary(+arg); break;
    case 'sendout': cmd({ t: 'party', op: 'sendout', slot: st.cobble.selected }); buzz(20); break;
    case 'pokedex': cmd({ t: 'party', op: 'pokedex' }); break;
    case 'sumtab': S.summary.tab = arg; break;
    case 'sumclose': S.summary = null; break;
    case 'sumnav': {
      const party = (st.cobble && st.cobble.party) || [];
      let i = S.summary.slot;
      for (let n = 0; n < 6; n++) { i = (i + (+arg) + 6) % 6; if (party[i]) break; }
      openSummary(i); break;
    }
    case 'sumgame': cmd({ t: 'party', op: 'summary', slot: +arg }); break;
    case 'bt': {
      const [op, idx] = arg.split(':');
      cmd({ t: 'battle', op, index: idx != null ? +idx : 0 }); buzz(18);
      if (op === 'bag') S.battleMirror = true;
      break;
    }
    case 'btpress': cmd({ t: 'battle', op: 'press', btn: arg }); buzz(18); break;
    case 'battle': if (arg === 'mirror') S.battleMirror = !S.battleMirror; break;
    case 'calibrate': cmd({ t: 'calibrate' }); toast('Look at the top screen'); break;
    case 'cfg': { const cur = st.cfg[arg]; cmd({ t: 'config', values: { [arg]: !cur } }); st.cfg[arg] = !cur; break; }
    case 'cfgnum': { const [k, dv] = arg.split(':'); const v = (st.cfg[k] || 0) + (+dv); cmd({ t: 'config', values: { [k]: v } }); st.cfg[k] = v; break; }
    case 'set': S.settings[arg] = !S.settings[arg]; saveSettings(); break;
    case 'zoom': S.settings.zoom = clamp(Math.round((S.settings.zoom + (+arg)) * 10) / 10, 0.7, 1.6); saveSettings(); $('#main')._html = null; break;
    case 'reload': location.reload(); break;
    case 'debug': fetch('/api/debug').then(r => r.json()).then(d => { S.debug = d; $('#main')._html = null; schedule(); }); break;
    case 'hidepanel': Thor && Thor.hide && Thor.hide(); break;
  }
  schedule();
}

// One delegated touch handler: tap vs hold, works even if the element re-renders under the finger.
(() => {
  let start = null, holdTimer = null;
  document.addEventListener('pointerdown', e => {
    const el = e.target.closest('[data-tap],[data-hold]');
    if (!el || el.closest('#mirror')) return;
    start = { tap: el.dataset.tap, hold: el.dataset.hold, x: e.clientX, y: e.clientY, held: false };
    clearTimeout(holdTimer);
    if (start.hold) holdTimer = setTimeout(() => { if (start) { start.held = true; act(start.hold, true); } }, 450);
  });
  document.addEventListener('pointermove', e => {
    if (start && Math.hypot(e.clientX - start.x, e.clientY - start.y) > 14) { clearTimeout(holdTimer); start = null; }
  });
  document.addEventListener('pointerup', () => {
    clearTimeout(holdTimer);
    if (start && !start.held && start.tap) act(start.tap, false);
    start = null;
  });
  document.addEventListener('pointercancel', () => { clearTimeout(holdTimer); start = null; });
  document.addEventListener('contextmenu', e => e.preventDefault());
})();

window.addEventListener('resize', () => { $('#main')._html = null; schedule(); });
connect();
render();

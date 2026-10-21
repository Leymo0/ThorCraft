// Mock of the ThorCraft mod's panel server, for testing the UI without Minecraft.
const http = require('http');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const WEB = process.argv[2];
let scenario = 'home';

function png(r, g, b) {
  // 16x16 solid-ish square PNG
  const w = 16, h = 16;
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    for (let x = 0; x < w; x++) {
      const o = y * (w * 4 + 1) + 1 + x * 4;
      const edge = x < 2 || y < 2 || x > 13 || y > 13;
      raw[o] = edge ? r * 0.6 : r; raw[o + 1] = edge ? g * 0.6 : g; raw[o + 2] = edge ? b * 0.6 : b; raw[o + 3] = (x + y) % 15 === 0 ? 0 : 255;
    }
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td) >>> 0);
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}
function crc32(buf) { let c, crc = 0xFFFFFFFF; for (let n = 0; n < buf.length; n++) { c = (crc ^ buf[n]) & 0xFF; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; crc = (crc >>> 8) ^ c; } return crc ^ 0xFFFFFFFF; }
const ICONS = {};
function iconFor(key) {
  if (!ICONS[key]) { let h = 0; for (const ch of key) h = (h * 31 + ch.charCodeAt(0)) >>> 0; ICONS[key] = png(80 + h % 170, 80 + (h >> 8) % 170, 80 + (h >> 16) % 170); }
  return ICONS[key];
}
const it = (i, c, name, extra = {}) => Object.assign({ i, c, id: 'minecraft:' + i, name: [{ t: name }] }, extra);
const player = { name: 'Leymo', hp: 17, maxHp: 20, absorb: 0, food: 18, sat: 4, armor: 12, air: 300, maxAir: 300, xpLevel: 23, xp: 0.4, x: 412, y: 71, z: -1290, yaw: 35, facing: 'south', biome: 'Cherry Grove', dim: 'minecraft:overworld', time: 5300, day: 41, blockLight: 0, skyLight: 15, raining: false, mode: 'survival', server: 'Cobbleverse SMP', fps: 58, sneaking: false };
const hotbar = [it('diamond_sword', 1, 'Diamond Sword', { d: 0.72, g: 1 }), it('poke_ball', 23, 'Poké Ball', { id: 'cobblemon:poke_ball' }), it('great_ball', 9, 'Great Ball', { id: 'cobblemon:great_ball' }), it('cooked_beef', 32, 'Steak'), null, it('torch', 64, 'Torch'), null, it('potion', 3, 'Potion'), it('oak_log', 41, 'Oak Log')];
const party = [
  { uuid: 'a', name: 'Charizard', species: 'Charizard', dex: 6, level: 52, hp: 141, maxHp: 168, shiny: false, gender: 'MALE', status: null, types: ['fire', 'flying'], held: it('charcoal', 1, 'Charcoal'), icon: 'pk_char' },
  { uuid: 'b', name: 'Gardy', species: 'Gardevoir', dex: 282, level: 49, hp: 22, maxHp: 140, shiny: true, gender: 'FEMALE', status: 'par', types: ['psychic', 'fairy'], held: null, icon: 'pk_gard' },
  { uuid: 'c', name: 'Lucario', species: 'Lucario', dex: 448, level: 50, hp: 0, maxHp: 150, shiny: false, gender: 'MALE', status: null, types: ['fighting', 'steel'], held: null, icon: 'pk_luc' },
  { uuid: 'd', name: 'Swampert', species: 'Swampert', dex: 260, level: 48, hp: 170, maxHp: 170, shiny: false, gender: 'FEMALE', status: null, types: ['water', 'ground'], held: it('leftovers', 1, 'Leftovers'), icon: 'pk_swa' },
  null, null];
const cfg = { hideMenusOnTop: true, padInBattle: true, interceptMouse: false, catchCloseGui: false, mirrorFps: 10, mapRadius: 48, padButtons: 'A,B,X,Y,L,R' };
const chestSlots = () => {
  const s = [];
  let id = 0;
  for (let r = 0; r < 3; r++) for (let c = 0; c < 9; c++) s.push({ id: id++, x: 8 + c * 18, y: 18 + r * 18, it: (r * 9 + c) % 4 === 0 ? it('iron_ingot', 1 + c * 3, 'Iron Ingot') : (r * 9 + c) % 7 === 0 ? it('ultra_ball', 5, 'Ultra Ball') : undefined });
  for (let r = 0; r < 3; r++) for (let c = 0; c < 9; c++) s.push({ id: id++, x: 8 + c * 18, y: 84 + r * 18, p: 1, it: c === 2 && r === 1 ? it('bread', 12, 'Bread') : undefined });
  for (let c = 0; c < 9; c++) s.push({ id: id++, x: 8 + c * 18, y: 142, p: 1, it: hotbar[c] || undefined });
  return s;
};
const menuSlots = () => {
  const s = [];
  let id = 0;
  for (let r = 0; r < 3; r++) for (let c = 0; c < 9; c++) {
    const edge = r !== 1 || c === 0 || c === 8;
    s.push({ id: id++, x: 8 + c * 18, y: 18 + r * 18, it: edge ? it('black_stained_glass_pane', 1, ' ', { id: 'minecraft:black_stained_glass_pane' }) :
      c === 2 ? it('grass_block', 1, 'Spawn', { name: [{ t: 'Spawn', c: '#55FF55', b: 1 }], lore: [[{ t: 'Teleport to the server spawn', c: '#AAAAAA' }], [], [{ t: 'Click to travel', c: '#FFFF55' }]] }) :
      c === 4 ? it('poke_ball', 1, 'Shop', { name: [{ t: 'Poké Mart', c: '#FF5555', b: 1 }], lore: [[{ t: 'Buy balls, potions and TMs', c: '#AAAAAA' }]] }) :
      c === 6 ? it('ender_chest', 1, 'Vault', { name: [{ t: 'Your Vault', c: '#AA00AA', b: 1 }], lore: [[{ t: '2 pages unlocked', c: '#AAAAAA' }]] }) : undefined });
  }
  for (let r = 0; r < 4; r++) for (let c = 0; c < 9; c++) s.push({ id: id++, x: 8 + c * 18, y: r === 3 ? 142 : 84 + r * 18, p: 1 });
  return s;
};
const battle = (page) => ({
  page, cursor: 1, wild: true, choosing: page !== 'WAIT', guiOpen: true, bagMirror: false, toast: page === 'RUN_CONFIRM' ? 'Press B again to run' : '',
  foes: [{ pnx: 'p2a', trainer: '', name: 'Garchomp', level: 48, ratio: 0.63, status: 'brn', types: ['dragon', 'ground'], species: 'Garchomp', icon: 'pk_garc', mine: false }],
  allies: [{ pnx: 'p1a', trainer: 'Leymo', name: 'Gardy', level: 49, ratio: 0.85, hp: 119, maxHp: 140, status: null, types: ['psychic', 'fairy'], species: 'Gardevoir', icon: 'pk_gard', mine: true }],
  activeName: 'Gardy', forceSwitch: false, trapped: false,
  moves: [
    { id: 'moonblast', btn: 'A', pp: 12, maxPp: 15, disabled: false, type: 'fairy', cat: 'special', power: 95, acc: 100, name: 'Moonblast' },
    { id: 'psychic', btn: 'B', pp: 9, maxPp: 10, disabled: false, type: 'psychic', cat: 'special', power: 90, acc: 100, name: 'Psychic' },
    { id: 'thunderbolt', btn: 'X', pp: 15, maxPp: 15, disabled: false, type: 'electric', cat: 'special', power: 90, acc: 100, name: 'Thunderbolt' },
    { id: 'calmmind', btn: 'Y', pp: 0, maxPp: 20, disabled: false, type: 'psychic', cat: 'status', name: 'Calm Mind' }],
  list: page === 'SWITCH' ? party.filter(Boolean).map(p => Object.assign({}, p, { ok: p.hp > 0 && p.uuid !== 'b', why: '' })) :
    page === 'BALL' ? [{ name: 'Poké Ball', icon: 'poke_ball', count: 23, slot: 1 }, { name: 'Great Ball', icon: 'great_ball', count: 9, slot: 2 }, { name: 'Ultra Ball', icon: 'ultra_ball', count: 4, slot: 14 }] : [],
  pad: 'A,B,X,Y,L,R',
  log: [
    [{ t: 'A wild Garchomp appeared!' }],
    [{ t: 'Go! Gardy!' }],
    [{ t: 'Garchomp used Earthquake!' }],
    [{ t: "It's not very effective..." }],
    [{ t: 'Garchomp was hurt by its burn!' }],
    [{ t: 'Gardy used Moonblast!' }],
    [{ t: 'A critical hit!' }],
    [{ t: "It's super effective!" }],
    [{ t: 'Garchomp', c: '#FF5555' }, { t: ' fell to 63% HP.', c: '#AAAAAA' }],
  ],
});
function state() {
  const base = { v: 1, tick: Date.now() / 50 | 0, cfg, cobblemon: true, inWorld: true, player, hotbar, offhand: it('shield', 1, 'Shield'), selected: 1,
    effects: [{ name: 'Speed', amp: 1, dur: 2400, good: true }, { name: 'Night Vision', amp: 0, dur: -1, good: true }],
    cobble: { party, selected: 1, battle: null, nearby: [{ uuid: 'k1', name: 'Key', kind: 'player', dist: 4.2, reach: true }, { uuid: 'p9', name: 'Charizard', kind: 'pokemon', dist: 2.1, reach: true, icon: 'pk_char' }, { uuid: 'x2', name: 'Steve', kind: 'player', dist: 14.8, reach: false }] }, screen: { kind: 'none' } };
  const sc = (o) => Object.assign({ cls: 'x', aspect: 16 / 9, typing: false }, o);
  switch (scenario) {
    case 'chest': base.screen = sc({ kind: 'container', title: [{ t: 'Chest' }], syncId: 3, bgW: 176, bgH: 166, rows: 3, slots: chestSlots(), cursor: null, menu: false }); break;
    case 'painted': base.screen = sc({ kind: 'container', title: [{ t: '\uE001\uE002 Shop' }], syncId: 9, bgW: 176, bgH: 166, rows: 3, slots: menuSlots(), cursor: null, menu: true, painted: true }); break;
    case 'menu': base.screen = sc({ kind: 'container', title: [{ t: '✦ ', c: '#FFAA00' }, { t: 'Server Menu', c: '#FFAA00', b: 1 }], syncId: 4, bgW: 176, bgH: 166, rows: 3, slots: menuSlots(), cursor: null, menu: true }); break;
    case 'mirror': base.screen = sc({ kind: 'mirror', title: [{ t: 'PC' }], widgets: [{ i: 0, label: 'Done', a: true, type: 'button' }, { i: 1, label: 'Box 1', a: true, type: 'button' }, { i: 2, label: 'Search', a: true, type: 'text', value: '' }], typing: true }); break;
    default:
      if (scenario === 'vanilla') { base.cobblemon = false; delete base.cobble; }
      if (scenario.startsWith('dbl')) {
        const bt = battle(scenario.split('-')[1] || 'MAIN');
        bt.foes.push({ pnx: 'p2b', trainer: '', name: 'Gyarados', level: 47, ratio: 0.9, status: null, types: ['water', 'flying'], species: 'Gyarados', icon: 'pk_gya', mine: false });
        bt.allies.unshift({ pnx: 'p1a', trainer: 'Leymo', name: 'Charizard', level: 52, ratio: 0.84, hp: 141, maxHp: 168, status: null, types: ['fire', 'flying'], icon: 'pk_char', mine: true });
        bt.allies[1].pnx = 'p1b'; bt.activePnx = 'p1b'; bt.requestIndex = 2; bt.requestCount = 2; bt.wild = false;
        base.cobble.battle = bt; base.screen = sc({ kind: 'battle', title: [] });
      }
      if (scenario.startsWith('battle')) { base.cobble.battle = battle(scenario.split('-')[1] || 'MAIN'); base.screen = sc({ kind: 'battle', title: [] }); }
  }
  return base;
}
const clients = new Set();
setInterval(() => { const d = 'event: state\ndata: ' + JSON.stringify(state()) + '\n\n'; for (const c of clients) c.write(d); }, 200);

http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  if (u.pathname.startsWith('/scenario/')) { scenario = u.pathname.slice(10); res.end('ok'); return; }
  if (u.pathname === '/events') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store' });
    res.write('retry: 1000\n\n');
    clients.add(res); req.on('close', () => clients.delete(res)); return;
  }
  if (u.pathname === '/cmd') { let b = ''; req.on('data', d => b += d); req.on('end', () => { console.log('CMD', b); res.writeHead(204); res.end(); }); return; }
  if (u.pathname.startsWith('/itemicon/')) { res.writeHead(302, { Location: '/icon/' + u.pathname.slice(10) }); res.end(); return; }
  if (u.pathname.startsWith('/tex/')) { res.writeHead(200, { 'Content-Type': 'image/png' }); res.end(u.pathname.includes('dirt') ? png(134, 96, 67) : u.pathname.includes('oak_planks') ? png(168, 132, 80) : png(220, 40, 40)); return; }
  if (u.pathname.startsWith('/icon/')) { res.writeHead(200, { 'Content-Type': 'image/png' }); res.end(iconFor(u.pathname)); return; }
  if (u.pathname === '/mirror.png') { res.writeHead(200, { 'Content-Type': 'image/png' }); res.end(png(40, 60, 90)); return; }
  if (u.pathname === '/api/chat') { res.end(JSON.stringify([{ id: 1, k: 'chat', s: [{ t: '<Key> ' }, { t: 'shiny gardevoir?? no way' }] }, { id: 2, k: 'game', s: [{ t: '[Server] ', c: '#FFAA00' }, { t: 'Welcome to Cobbleverse!' }] }])); return; }
  if (u.pathname === '/api/keys') { res.end(JSON.stringify([{ id: 'key.inventory', name: 'Open/Close Inventory', cat: 'Inventory', bound: 'E' }, { id: 'key.cobblemon.throwpartypokemon', name: 'Throw Party Pokémon', cat: 'Cobblemon', bound: 'R' }, { id: 'key.xaero_open_map', name: "Open World Map", cat: "Xaero's World Map", bound: 'M' }])); return; }
  if (u.pathname === '/api/summary') {
    const p = party[+u.searchParams.get('slot')] || party[0];
    res.end(JSON.stringify(Object.assign({}, p, { nature: 'Modest', ability: 'Trace', abilityDesc: 'Copies the opposing Pokémon\'s Ability.', friendship: 214, exp: 117649, expToNext: 4502, ot: 'Leymo', ball: 'cobblemon:great_ball', desc: 'It has the power to predict the future.',
      stats: { hp: { v: 140, iv: 31, ev: 4 }, attack: { v: 70, iv: 12, ev: 0 }, defence: { v: 75, iv: 25, ev: 0 }, special_attack: { v: 156, iv: 31, ev: 252 }, special_defence: { v: 131, iv: 28, ev: 0 }, speed: { v: 118, iv: 30, ev: 252 } },
      moves: [{ name: 'Moonblast', pp: 12, maxPp: 15, type: 'fairy', cat: 'special', power: 95, acc: 100, desc: 'Borrowing the power of the moon, the user attacks the target.' }, { name: 'Psychic', pp: 9, maxPp: 10, type: 'psychic', cat: 'special', power: 90, acc: 100 }] })));
    return;
  }
  if (u.pathname === '/api/map') {
    const size = 96; const buf = Buffer.alloc(size * size * 3);
    for (let z = 0; z < size; z++) for (let x = 0; x < size; x++) { const o = (z * size + x) * 3; const water = Math.hypot(x - 70, z - 30) < 18; buf[o] = water ? 50 : 90 + (x * z) % 30; buf[o + 1] = water ? 90 : 150 + (x + z) % 40; buf[o + 2] = water ? 200 : 60; }
    res.end(JSON.stringify({ size, img: buf.toString('base64'), yaw: 35, x: 0, z: 0, ents: [{ dx: 10, dz: -6, k: 'pokemon', n: 'Eevee', wild: true }, { dx: -20, dz: 14, k: 'pokemon', n: 'Ralts', shiny: true, wild: true }, { dx: 5, dz: 22, k: 'player', n: 'Key' }, { dx: -30, dz: -25, k: 'hostile', n: 'Zombie' }] }));
    return;
  }
  if (u.pathname === '/api/debug') { res.end('{"ok":true}'); return; }
  let f = u.pathname === '/' ? '/index.html' : u.pathname;
  const fp = path.join(WEB, f);
  if (!fs.existsSync(fp)) { res.writeHead(404); res.end(); return; }
  const type = f.endsWith('.woff2') ? 'font/woff2' : f.endsWith('.js') ? 'application/javascript' : f.endsWith('.css') ? 'text/css' : 'text/html';
  res.writeHead(200, { 'Content-Type': type }); res.end(fs.readFileSync(fp));
}).listen(47999, () => console.log('mock on 47999'));

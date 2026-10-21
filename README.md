# ThorCraft — Minecraft & Cobbleverse on the AYN Thor's two screens

by Leymo

ThorCraft makes Java Minecraft (Fabric 1.21.1) feel like a DS game on the AYN Thor:
the world stays on the top screen, and everything you'd normally open a menu for lives on the
bottom touch screen.

```
ThorCraft/
├── mod/        Fabric 1.21.1 client mod (the brain, also serves the panel UI)
└── companion/  Android app that shows the panel on the Thor's bottom screen
```

---

## 1. Best way to run Cobbleverse on the Thor

1. **Launcher: Zalith Launcher 2.** Download it **only** from its GitHub releases
   (`github.com/ZalithLauncher/ZalithLauncher2`). The developers warn that look-alike "Zalith"
   websites are not theirs and are ad-filled.
2. **Get the exact same pack you play on PC.** In the Modrinth App on your PC: COBBLEVERSE 1.7.42
   instance → ⋯ → *Export modpack* → `.mrpack`. Copy it to the Thor and import it in Zalith
   (Versions → Import modpack). This keeps Fabric 0.18.4 / Cobblemon / configs identical to your PC, which matters for servers.
3. **Java:** Zalith's built-in Java 21.
4. **Memory:** 4–6 GB for Cobbleverse (leave the rest for Android). If it crashes on load, raise it; if Android kills the game, lower it.
5. **Renderer:** try **MobileGlues** first, **Zink** (Vulkan) as fallback. Keep Sodium on, shaders off at first.
6. **In-game:** render distance 8–10, simulation distance 6, entity distance ~75 % (Pokémon are entities), clouds off, smooth lighting on.
7. Fan on Smart/Max in the Thor's quick settings for long sessions.
8. Drop `thorcraft-0.1.0.jar` into the instance's `mods` folder (Zalith: version → Manage → Mods).

## 2. Download (easiest)

Every push builds both files automatically. Grab the newest from the repo's **Releases** page:
- `ThorCraft-Panel.apk` → install on the Thor
- `thorcraft-*.jar` → put in your Cobbleverse **mods** folder

## 2b. Build it yourself

**Mod** (PC, JDK 21):
```
cd mod
./gradlew build          # Windows: gradlew.bat build
# -> mod/build/libs/thorcraft-0.1.0.jar
```
Cobblemon isn't needed to build — ThorCraft talks to it through reflection, so the same jar works in
vanilla, other Fabric packs, and Cobbleverse.

**Companion app:** open `companion/` in Android Studio → Run on the Thor (or Build → Build APK).

## 3. First run

1. Open **ThorCraft Panel** on the Thor → *Allow drawing over other apps* → *Start bottom-screen panel*.
   The bottom screen shows "Waiting for Minecraft…".
2. Launch Cobbleverse in Zalith on the top screen. The panel connects by itself.
3. **Controller setup:** on the panel go to *More → Controller setup* (or bind the
   "Controller setup (ABXY)" key). Press A, B, X, Y, L, R when asked — ThorCraft records whatever your launcher
   sends for each button, so battles work with any gamepad mapping.
   Tip: in Zalith's gamepad mapping, give **X** and **Y** their own keyboard keys while in menus
   (A/B usually already click/escape).

Testing on PC: run the game with the mod and open `http://127.0.0.1:47123/` in a browser.
UI work without Minecraft: `node mod/panel-mock-server.js mod/src/main/resources/assets/thorcraft/web` then open
`http://127.0.0.1:47999/` (switch scenes with `/scenario/chest`, `/scenario/menu`, `/scenario/battle-FIGHT`…).

## 4. What's on the bottom screen

**Every menu, automatically**
- **Inventories, chests, furnaces, modded machines** → real slots you tap. Tap = pick up/place,
  hold = right-click (split / place one). Modes: Move, Quick ⇅ (shift-click), Drop. Holding item + "Drop held".
- **Server GUIs made of chests** are detected (glass-pane fillers, custom names) and switch to *menu mode*:
  first tap on a menu button shows its name and description, second tap uses it. Left-click / Right-click /
  Shift-click buttons exist because servers give each click a different job (e.g. buy 1 / sell / buy 64).
  Your inventory stays open underneath so you can put items in when a menu asks for them; holding an item
  and tapping a menu slot places it straight away.
- **Top screen stays clean:** while the panel is connected, inventories/chests/server menus are drawn only on
  the bottom screen (toggle in More, or with the "Toggle menus on top screen" key).
- **Server menus with custom art:** when a server's resource pack paints a background over a chest menu
  (special font / private-use glyphs in the title), it opens as the live picture automatically so it looks
  exactly like the top screen. Tap **Slots** to switch to tappable slots (toggle in More).
- **Everything else** (Cobblemon PC, Pokédex, mod settings, the title screen…) shows as a **live picture you
  can touch**, with scroll buttons, a keyboard bar for text boxes, and a list of the screen's buttons.

**Cobbleverse battles**
- Foe and ally cards with HP, status, and types.
- **ABXY:** A Fight · X Catch (wild) / Pokémon · Y Bag · B Run (press twice) · R Pokémon.
- **Fight:** the four moves are laid out as a diamond in the same spots as the Thor's X/Y/A/B buttons
  (Nintendo layout; switch to Xbox in More if you swapped yours), with type colours, PP, power/accuracy, and a *Super / Weak / No effect*
  hint against the foe's types. L = back.
- **Catch:** pick a ball (L/R + A, or tap). ThorCraft swaps it into your hand, aims at the wild Pokémon and throws.
- **Battle log:** the last 4 lines of the battle under the buttons (tap to make it taller). The lines are
  Cobblemon's own battle messages, so the wording, translations and colours match the game and any
  lang/resource packs in the modpack; ThorCraft adds highlights — your Pokémon in blue, foes in red, moves
  in bold, *super effective* green, *not very effective / missed* orange, critical hits yellow, faints grey.
- **Double battles:** both foes and both of yours show side by side; the Pokémon you're choosing for is
  outlined ("Choosing for Gardy 2/2"). Move tiles show effectiveness against each foe (e.g. `2× | ½×`),
  and moves that need a target ask which one (you can't target yourself; the second Pokémon can't switch
  to the one the first already picked).
- **Switch / targets:** L/R cursor, A choose, B back. Fainted / active Pokémon are greyed out.
- **No Cobblemon installed:** the Party tab, battle screens, Pokémon on the map and battle settings are hidden.
- **Bag:** opens Cobblemon's bag and shows the battle screen below so you can tap items.

**Real game art everywhere**
- Every item, block, head and Pokémon is drawn by the game itself, so resource packs and modded items look
  exactly like they do in-game. Tab and button icons are real items too (chest, compass, Poké Ball…),
  hearts/food/armour are the game's own HUD sprites, and effects use their real icons.
  Switch resource packs and the panel updates.

**Cobblemon interactions (the R wheel)**
- Party tab → *Interact* lists players and your sent-out Pokémon nearby. Tap one and ThorCraft turns you to
  face them and presses Cobblemon's R, so Cobblemon's own wheel opens (Battle / Trade / Team up / Spectate,
  or the Pokémon options). The wheel appears zoomed in on the bottom screen to tap.
- *Interact with what I'm looking at* = pressing R.
- Any picture view has *Zoom in / Zoom out* for small menus.

**Tabs**
- **Home:** kept simple — a scrolling **compass** strip, a **mini map** (tap it to make it big with a nearby-Pokémon list, tap again to shrink), big
  **X / Y / Z** with biome and time, and a *Share* button that posts your coordinates in chat.
  Under that: quick actions (Inventory, Chat, Camera view F5, Drop item, Swap offhand, **Sneak (toggle)**,
  Hide HUD F1, Screenshot, Pause menu) and any keys you pinned.
- **Party:** your six with HP/status/held item, send out/recall, Pokédex; hold a Pokémon for a full
  **summary** (Info / Stats with IVs & EVs / Moves with descriptions).
- **Chat:** full chat log, Android keyboard input, saved quick commands (hold to remove).
- **Keys:** every keybinding from every mod — tap to press, hold to pin to Home (Xaero map, emotes…).
- **Vibration** on damage, battle start, and **shiny alerts** ("✨ Shiny Ralts! 31 blocks NW").

## 5. Themes

More → Theme (9 to pick from):
- **Minecraft** is the default.
- **Night** — black background with dark-grey panels and orange.
- **OLED Black** — truly black everywhere (pixels off saves battery), hairline outlines, white accents.
- **Minecraft** — options-screen dirt background, grey bevelled inventory panels, stone buttons, sunken slots,
  purple tooltip boxes.
- **Matcha & Oak** — matcha-green panels on oak planks, darker oak bars and trim.
- **Neon Purple** — deep violet with glowing purple/cyan edges.
- **Cloudy** — soft sky with clouds and frosted-glass white panels.
- **SNES** — grey console body, blue 16-bit RPG menu windows with white frames, concave purple buttons,
  Super Famicom red/yellow/blue/green battle buttons, retro title font.
- **Game Boy Advance** — pixel font, chunky pixel-cornered message boxes like the GBA Pokémon games,
  indigo console colours, ▶ cursor on the selected item.
- **Diamond & Pearl** — Sinnoh DS menus: dotted slate backdrop, blue bars, white rounded panels,
  white-rimmed battle buttons, red-bordered battle text box.

The dirt and oak planks are read from the game, so they follow your resource pack. Item descriptions and chat
stay in a dark box on the light themes so server colours remain readable.
Fonts: Pixelify Sans and Press Start 2P (SIL Open Font License, see `licenses/`).

## 6. How it works

```
 Minecraft (Zalith, top screen)                  ThorCraft Panel app (bottom screen)
 ┌─────────────────────────────┐   127.0.0.1    ┌────────────────────────────────┐
 │ ThorCraft mod               │ ── SSE state ─▶ │ non-focusable overlay WebView  │
 │  • tiny HTTP server :47123  │ ◀── commands ── │  (touch works, pad focus stays │
 │  • renders item/Pokémon PNGs│                 │   on the game)                 │
 │  • captures menu pictures   │                 │ keyboard only while typing     │
 └─────────────────────────────┘                 └────────────────────────────────┘
```
The panel UI (`mod/src/main/resources/assets/thorcraft/web/`) ships inside the mod jar, so UI changes
only need a new jar. Config: `config/thorcraft.json` (`lan: true` + `?token=` lets you open the panel from
another device on Wi-Fi for testing).

## 7. If something doesn't work

- **Panel stuck on "Waiting":** check the mod is in the mods folder and nothing else uses port 47123 (change both
  `config/thorcraft.json` and the port in the app).
- **Battle buttons do nothing / names missing:** open *More → Debug* (or `http://127.0.0.1:47123/api/debug`).
  It lists which Cobblemon members ThorCraft found and any errors. Cobblemon internals can change between
  versions; the fix is usually renaming one string in `cobble/CobbleBridge.java` or `BattleController.java`.
- **Catch throws but nothing happens:** turn on *Close battle UI when throwing* in More.
- **Icons blank:** they render in the background a few per frame; they fill in within a second. If they never
  appear, your renderer can't read textures back — try the other renderer (MobileGlues ↔ Zink).
- **Physical A clicks things in battle when you didn't want it to:** leave *Pad buttons that send mouse clicks* off.

## 8. Known limits (v0.1)

- Built against Yarn 1.21.1 without a test compile in this environment — expect to fix a handful of compile
  errors on first build (the code was written carefully, but nothing replaces the compiler).
- Creative inventory uses the live picture, not native slots.
- The picture view sends single-finger touches; menus needing right-click there need a mouse mapping.
- Tera / Mega / Z-moves aren't selectable from the panel yet — use the battle screen picture for those turns.

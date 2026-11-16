# Settings rules

How a setting earns its place in Godnotic, where it goes, what it is called, and what
its help has to say. Written from a read of the two settings menus as they stand on
2026-11-16: the bridge menu (`qcsrc/menu/xonotic/touch_uib_settings.qc`,
`touch_uib_touchcontrols.qc`) and the classic Mobile tab
(`qcsrc/menu/xonotic/dialog_settings_touch.qc`, `dialog_touch_controls.qc`,
`dialog_touch_esp.qc`, `dialog_touch_privacy.qc`, `touch_menu_settings.qc`). Every
example below is a real row from one of those files. The tree itself, and what should
move, is in [SETTINGS_MAP.md](SETTINGS_MAP.md).

The one-line version: **a setting is a question we could not answer for the player.**
If we can answer it, it is a default. If only a developer can answer it, it is a cvar
and stays out of the menu.

## 1. When something deserves a setting

A control goes in the menu only when all four hold:

1. **Players disagree about the right value, and the game cannot tell which player it
   has.** Swipe sensitivity (`touch_sens_base`), left-handedness (`touch_handedness`),
   invert look (`touch_invert_y`): hands differ and nothing in the device says which hand
   is holding it. Those are settings.
2. **The player can judge the result.** Turn it, play ten seconds, know whether it was
   right. Stick dead zone (`touch_stick_deadzone`, "How far the thumb moves before you
   do") passes. `cl_touch_aimassist_hysteresis` ("stays the target unless another is this
   many degrees nearer the crosshair", 0 to 10) fails: nobody can feel the difference
   between 2 and 3 degrees of hysteresis, so nobody can set it.
3. **The player can know the right value.** `cl_touch_autoshoot_head_rifle` asks for "the
   server's Rifle headshot multiplier, which the game cannot read". If the game cannot
   read it, the player certainly cannot. This is not a setting; it is a guess with a
   slider on it.
4. **It is not already answered by another control.** `touch_weplist_x` and
   `touch_weplist_y` ("Where the weapon bar sits across the screen") position a HUD
   element with two sliders when "Edit layout" (`touch_customize 1`) lets the player
   drag it where they want. One of those is the setting; the sliders are the long way
   round.

What is **not** a reason for a setting:

- "Someone might want it." Someone might. They have the console. Every switch in the
  menu is read by every player who opens that page, and a page of forty switches is a
  page nobody reads. The classic Tactics tab is nineteen checkboxes
  (`cl_touch_autoshoot_alt` through `cl_touch_aimassist_lastseen`); every one defaults
  on in `touch/profiles/helpers.cfg`, and the only decision a player ever makes about
  them is "do I want the game to shoot for me", which is one switch
  (`cl_touch_autoshoot`).
- "We were not sure of the default." Measure, decide, ship the default, and keep the
  cvar for the next tuning pass. `touch_stick_range` ("How easily the move stick
  reaches full speed", 0.6 to 1.5) is a tuning constant that escaped.
- "It exposes a feature." Features are exposed by working. The Light / Medium / Strong
  aim-assist buttons on the classic Aim tab are the right shape; the four sliders under
  them (`cl_touch_aimassist_friction`, `_strength`, `_cone`, `_maxrate`) show the
  mechanism and are the wrong shape.
- "Developers need it." Developers have `cl_touch_aim_debug`, `cl_touch_loser` and the
  console. A whole tab for `cl_touch_loser` labelled "(Development) Loser mode ... For
  development and testing only" is a development tool shipped to every player.

When something fails the test, the fix is a **better default**, a **preset** that bundles
it with its friends, or a **console cvar** with a good description string. Not a hidden
"advanced" page: that only moves the problem.

## 2. Where it belongs

- **One menu.** The bridge menu (`menu_touch_uib 1`) is the menu; the classic Mobile tab
  is the escape hatch. A setting is placed once, in the bridge, and the classic tab is
  allowed to keep showing it only until the bridge row exists. Today eleven cvars appear
  in two or three places with different ranges (section 6); each of those is a bug, not a
  convenience.
- **Each cvar on exactly one page.** `dialog_settings_touch.qc` states this rule in its
  own header comment ("each cvar is on exactly one sub-tab") and the bridge then put
  `touch_sens_base`, `touch_scale`, `touch_opacity`, `touch_handedness`,
  `touch_invert_y`, `cl_touch_aimassist` and `touch_preset` on two sub-tabs of the same
  section. If a setting seems to belong in two places, one place is wrong; put it in the
  one a player would look first and let search (SETTINGS_MAP.md section 4) cover the
  other.
- **Group by the player's question, not by the code that reads the cvar.** The engine
  reads `cl_touch_aimassist` in `touch_aim.c` and both HUDs read `touch_hop_mode` from
  the layout table in `qcsrc/client/touch_init.qc`; the player does not care. "Aim assist" sits under Assists because
  "is the game helping me aim" is one question. It does not sit under "Buttons", where
  the bridge's Touch controls tab has it today, between HOP and the spectator bar.
- **A section is a noun the player already has.** Controls, HUD, Video, Audio, Player.
  Not "Dynamics" (the classic tab for frame rate and battery), not "Loser", not a
  section called "Controls" with a sub-tab called "Controls" and another called "Touch
  controls" (the bridge's `touch` section today).
- **Status is not a setting.** Updates (version, Check now, Download) and Data (is the
  game data there) are status pages with one or two switches on them. They may live in
  Settings for want of a better home, but they go last and they do not get a sub-tab per
  platform.
- **Gated controls appear only where the gate is open.** The haptics switch
  (`touch_haptics`) is built only `if (cvar("_touch_haptics_available"))` in the bridge
  and `setDependent(e, "_touch_haptics_available", 1, 1)` in the classic tab. That is
  right. ESP (`cl_touch_esp_enabled`, offline only) is a whole section whose help says
  "on other servers it stays off": better to show it, with that note, than to hide a
  switch the player set and now cannot find.

## 3. Naming

The label is what the player reads. The cvar is what the code reads. They are not the
same text and must not try to be.

**Labels**

- Say what the player gets, in the player's words. "Aim while firing"
  (`touch_fire_drag_look`) is right. "Look speed (DPI-normalized)" (the classic Aim
  tab's tooltip for `touch_sens_base`) is wrong on both counts: "look speed" is one
  thing, "DPI-normalized" is a sentence from a code review.
- One idea per label, under about 40 characters, no colon at the end in the bridge
  (the classic tab's "Swipe sensitivity:" convention is the stock menu's and stays
  there).
- A switch's label is the **on** state, as a statement: "Invert up and down", "Switch to
  better weapons you pick up", "Move at any angle". Never a question, never "Enable X",
  never "Allow X" unless it is a permission.
- A slider's label is the quantity: "Size", "Opacity", "Dead zone", "Field of view". Not
  "Scale:" with a tooltip "Size of move/fire/jump widgets" (the classic Controls tab):
  "widgets" is our word.
- A choice's label is the thing being chosen ("HOP", "Fire button", "Frame rate limit")
  and its option labels are short and parallel: "Hold" / "Toggle"; "Off" / "On" /
  "Strong". Not "+ KEEP switch" / "Plain" / "Hold to keep" / "Auto-hop" (the classic
  HOP row), where the first option is a symbol and the others are three different
  grammatical shapes.
- Use the on-screen names of controls in capitals as they are drawn: FIRE, HOP, DUCK,
  ALT, MENU. "Drag your thumb off FIRE" is a real instruction; "the fire button" makes
  the player look for lowercase text that is not there.
- The same thing has the same name everywhere. Today `touch_sens_base` is "Swipe
  sensitivity" (Touch controls), "Look speed" (Aim) and "Sensitivity" (classic Aim);
  `touch_scale` is "Size", "Control size" and "Scale". Pick one.
- No developer words in labels or help: cvar names, "widget", "overlay", "DPI", "CSQC",
  "engine", "units" (see section 5 for the one exception), "normalized", "mode" as a
  suffix. One row of the classic Aim tab breaks this twice: the Ledge guard option "Any
  long drop" has the help "deeper than cl_touch_ledgeguard_drop units (192 unless
  changed)".

**Cvars**

- Touch-layer cvars are `touch_*` when the CSQC HUD and the engine HUD both read them
  (the layout table in `qcsrc/client/touch_init.qc`, exported to `touch_defaults.cfg`
  by `touch/gen-defaults.py`) and `cl_touch_*` when only the engine reads them
  (`engine/darkplaces/touch_*.c`). Menu-only state is `menu_touch_*`. Do not invent a
  fourth prefix.
- A leading underscore means **not a setting**: transient state the engine writes
  (`_touch_act_join`, `_touch_haptics_available`, `_cl_touch_aim_lock`). A menu control
  never binds to one. `_touch_asset_fetch_background` on the Data page is bound to a
  user switch and should either lose its underscore and be archived, or stop being a
  switch.
- A cvar that holds a magic value for a different meaning is two settings in one
  variable and needs splitting. `menu_touch_size` is a size from 0.6 to 1.6, and also
  "-1 = the stock desktop layout" (the "Use desktop layout" checkbox in
  `touch_menu_settings.qc`). The desktop layout wants its own boolean.
- A string cvar with an enumerated vocabulary (`cl_touch_esp_enabled`: "local" or
  "off") is bound only by a control that writes that vocabulary. The bridge binds it to
  a `switch`, which writes "1"/"0"; `touch_tactics.c` tolerates "0" and treats "1" as
  "local" after printing a warning to the console once. It works by accident. Use a
  `choice` with the real values, or make the cvar a boolean.

## 4. Help text

Every control gets help unless the label is the whole story. "Master" under "Volume"
needs none. "Faster for quick swipes" needs the one it has: "A slow drag aims exactly;
a quick swipe turns further, enough to turn around in one".

Help answers, in this order, as many of these as the label leaves open:

1. **What changes on screen or in hand** when it is on or moved. `touch_stick_analog`:
   "Off: eight directions, like keys". `touch_turn_angle`: "How far one tap turns you,
   in degrees. 180: face the other way".
2. **What the other state does**, when the off state is not just "nothing".
   `touch_specbar_visible`: "Off: no FOLLOW control on screen. Turn it on to follow
   players; MENU still joins and spectates".
3. **Where it does not apply.** ESP: "only in matches on this device". Aim assist: "In
   matches on this device, and on servers that allow it". `cl_touch_hook_rescue`: "Only
   with a hook, only in touch mode".
4. **When it takes effect**, if not at once. `menu_touch_size`: "Applies the next time
   the menu opens". Privacy: "A change applies the next time you join a server". In the
   bridge, prefer the `apply` hint (`UIB_Apply("video")`, `"reconnect"`) so every
   renderer shows the note the same way.
5. **The default, when a number has one worth knowing.** "1 is sized from the screen's
   density" (`menu_touch_size`).

Help must not:

- Restate the label. "Opacity" with help "Control overlay opacity" (classic Controls)
  says nothing twice. Empty help is better than that.
- Explain the mechanism. "The 1 euro filter smooths the turn still owed" belongs in
  `docs/TOUCH_CONTROLS_REVIEW.md`, and that is where it is. Good.
- Name a cvar, a file, a source directory or another tab by its internal name.
  "(Targets: Radius, Settings > Auto-shoot)" in the catch-cone help is a cross-reference
  to a tab that will move; the help then lies.
- Sell. "Coolest and longest on battery" (the 30 fps profile) is fine because it is a
  fact; "best experience" would not be.
- Run past two sentences. The longest help in the menus today is `touch_desk_pills`'s
  three clauses; anything longer is a paragraph on a phone row.

One help string per control, written once, shown in both menus (the bridge `help`
property and the classic `_T` tooltip take the same `_()` string; `touch_fire_drag_look`
does this already).

## 5. Units and ranges

**Units**

The bridge slider `format` values are the whole allowed vocabulary (docs/UI_BRIDGE.md
section 4.5): `percent` (a 0..1 cvar shown as 0 to 100 %), `x` (a multiplier shown as
1.0x), `deg`, `ms`, `dp`, `db`, `int`, `float1`, `float2`. Rules:

- A fraction of full is `percent`: opacity, volume, dead zone, resolution scale.
- A size relative to the default is `x`: `touch_scale`, `touch_hud_scale`,
  `menu_touch_size`, sensitivity. 1.0x must be the shipped default or the format lies.
- Angles are `deg`, times are `ms` or seconds spelled out in the help ("in seconds").
  Never both styles on one page: Auto-shoot has delay and release "in seconds" and
  radius "in degrees" in the help text instead of the format. Use the format.
- `float1` / `float2` are for quantities with no natural unit (contrast, brightness,
  crosshair size). If a setting is `float2` ask whether a player can tell 0.85 from 0.90;
  if not, it is a tuning constant (section 1).
- Game units ("qu", "units", "within 1600 units") never reach a label or help. The one
  exception is a cvar nobody but a modder sets, and then it is not in the menu. The
  pickup magnet radius (`cl_touch_practice_magnet_radius`, "in game units", 32 to 512)
  and `cl_touch_aim_range` (500 to 6000 "units") both break this; show them as Near /
  Medium / Far / Unlimited or do not show them.

**Ranges**

- The range brackets what players use, not what the code survives. `touch_sens_base`
  defaults to 3.6; the bridge Aim tab offers 0.2 to 4 (default at 90 % of the slider),
  the Touch controls tab 1 to 8, the classic tab 0.1 to 50. One of these was measured
  and it is 1 to 8 (docs/TOUCH_CONTROLS_REVIEW.md). The 0.1 to 50 range exists because
  the stock mouse slider has it; a touch setting does not inherit a mouse range.
- The default sits inside the range, ideally near the middle, and on a step.
  `touch_hud_scale` defaults to 0.85 with step 0.05 on 0.6 to 1.6: fine.
  `touch_weplist_x` defaults to 0.962 on a 0.01-step slider to 0.97: the comment admits
  the range was stretched to reach the default. That is the slider telling you it is the
  wrong control.
- Steps are what a thumb can hit: no more than about 40 steps across a phone-width
  slider. `touch_sens_base` 0.1 to 50 in 0.1 is 499 steps and needs the text box beside
  it to be usable, which is how the classic Aim tab is built. A control that needs a
  second control to be usable is the wrong control.
- One range per cvar. If two pages show the same slider with different ends (section 6
  lists seven of these), the player drags it to the end on one page and finds it past
  the end on the other.
- A choice replaces a slider when there are five or fewer values anyone picks: frame
  rate limit 30 / 60 / 120 / None; acceleration Off / On / Strong. Do not offer both the
  classic 20-to-2048 mixed slider and the bridge's four-way choice for `cl_maxfps`.
- A range must not contain a value that breaks the game. Opacity 0 is invisible
  controls; the floor is 0.2 to 0.3 everywhere and that is right. Menu size has the
  cvar's own documented range (0.6 to 1.6) in the classic tab and 0.7 to 1.5 in the
  bridge; take the cvar's.

## 6. What must never become a setting

- **Developer and debug switches.** `cl_touch_loser`, `cl_touch_aim_debug`,
  `cl_touch_aim_dumptracks`, `vid_touchscreen_notify` ("Show a message when the controls
  switch by themselves"). Console only.
- **Transient state** (any `_`-prefixed cvar), and anything the engine writes.
- **Tuning constants of a feature** that has an on/off switch or a strength preset:
  filter cutoffs (`touch_look_fcmin`, `touch_look_beta`), dead zones in pixels
  (`touch_look_deadzone_px`, `touch_edge_deadzone_px`), latch times
  (`touch_hop_latch_ms`), aim-assist friction / cone / max rate / hysteresis, auto-shoot
  delay / release / radius. These live in the profiles (`touch/profiles/*.cfg`) and in
  `touch_init.qc`'s table.
- **Decisions a feature should make itself.** The nineteen Tactics switches are a
  description of how an aimbot thinks (lead moving targets, aim explosives at feet, fly
  rockets around walls). A player who turned auto-shoot on wants it to shoot well; a
  player who turned it off wants nothing. Neither wants to configure its ballistics.
- **Values the player cannot know** (`cl_touch_autoshoot_head_rifle`, section 1).
- **Positions of on-screen elements as numbers.** The layout editor exists.
- **Features that do not work.** `touch_gyro_enabled`, `touch_gyro_sens`,
  `touch_gyro_ads_only` are registered and nothing reads a sensor
  (docs/TOUCH_CONTROLS_REVIEW.md, "Not done"). `touch_stick_float` and
  `touch_stick_follow` are marked legacy in `touch_init.qc` ("the CSQC stick is always
  fixed"); `docs/CONTROLS.md` still lists "Stick appears under your thumb" as a row of
  the Touch controls settings, and neither menu shows it. They stay out of the menu, and
  the docs row goes.
- **A duplicate of a setting that is already on screen.** The "Left-handed" switch and
  a "Left-handed" layout preset card (`touch_preset` option `left`, whose profile is
  `touch_handedness 1` and nothing else) are the same setting twice.
- **Anything that changes game rules on a server.** The pickup magnet's help says it
  "Changes the game's rules, so off by default", and ESP is gated to local matches in
  the engine (`TouchTactics_EspGate`). The gate stays in the engine, never in a switch.
- **A switch that turns off the menu you are standing in** without a way back from the
  same place. "New menu" (`menu_touch_uib`) is allowed only because its help gives the
  way back ("Turn it on again in its Settings > Mobile > Menu, or with menu_touch_uib 1
  in the console"). That help is the exception to the no-cvar-names rule, and it is the
  only one.

## 7. Reviewer checklist

Apply to every new or changed row. "No" to any of the first five means the change is
not a setting.

- [ ] Players disagree about the value, and the game cannot tell which player it has.
- [ ] A player can feel the result within a minute of play.
- [ ] A player can know the right value without reading code or asking a server admin.
- [ ] No other control already decides it (including the layout editor and the presets).
- [ ] It is not a tuning constant, a debug switch, a transient `_` cvar, or a dead
      feature.
- [ ] It appears on exactly one page of the bridge menu, under the section a player
      would look in first; the classic tab shows the same row or none.
- [ ] Its label is the player's words, is the on-state for a switch and the quantity
      for a slider, and matches the name used anywhere else it is mentioned.
- [ ] Its help says what changes, when it does not apply, and when it takes effect, in at
      most two sentences, with no cvar names, file names or mechanisms.
- [ ] Its format is one of the bridge formats; the default is inside the range, on a
      step, and the range is one players use (at most about 40 steps).
- [ ] If it is a choice, the options are parallel, five or fewer, and one of them is the
      shipped default.
- [ ] If it has a default, the default is in `touch_init.qc`'s table (and
      `touch/gen-defaults.py` has been run) or in the engine's `Cvar_RegisterVariable`,
      and the profiles that should override it do.
- [ ] If a bind needs a side effect (a profile to exec, a restart), the side effect is
      wired: a `cvar:` bind alone only sets the cvar. `touch_preset` in the bridge sets
      the cvar and applies nothing; the only thing that execs the profile is the separate
      "Apply the preset again" button on a different sub-tab.
- [ ] `tests/uibridge/fixtures/settings-*.json` was regenerated for the changed section,
      and `docs/CONTROLS.md` "Touch controls settings" (or the page this document says
      owns the row) was updated.
- [ ] Search (SETTINGS_MAP.md section 4) can find it from the label, the help and at
      least one word a player would type that appears in neither.

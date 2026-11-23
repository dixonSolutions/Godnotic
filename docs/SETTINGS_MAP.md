# Settings map

The Settings tree as it is on 2026-11-16, read from the menu QuakeC; a proposed tree;
and what the settings search has to index. The rules this applies are in
[SETTINGS_RULES.md](SETTINGS_RULES.md). Nothing here is implemented; this is the plan.

Sources, all under `engine/data/xonotic-data.pk3dir/qcsrc/menu/xonotic/`:

| File | What it builds |
|---|---|
| `touch_uib_settings.qc` | the bridge menu's Settings screen: twelve sections, their sub-tabs, actions and value handlers |
| `touch_uib_touchcontrols.qc` | the bridge's Controls > Touch controls sub-tab |
| `touch_uib_screens.qc` | where Settings is reached from (the Home bar's Settings tab, the Pause screen's Settings button, `UIB_BuildPause`) |
| `dialog_settings_touch.qc` | the classic menu's Settings > Mobile page and its Controls, HUD, Aim, Auto-shoot, Tactics, Dynamics and Loser tabs |
| `dialog_touch_controls.qc` | the classic Mobile > Touch tab |
| `dialog_touch_esp.qc`, `dialog_touch_privacy.qc`, `touch_menu_settings.qc` | the classic Mobile > ESP, Privacy and Menu tabs |
| `dialog_touch_gamemenu.qc` | the classic in-match menu, with its "Settings" and "Mobile settings" buttons |

Defaults come from `touch_defaults.cfg` (generated from `qcsrc/client/touch_init.qc`) for
`touch_*`, from `engine/darkplaces/touch_*.c` for `cl_touch_*`, and from
`touch/profiles/*.cfg` for what the presets override.

## 1. The tree today

There are two menus. The bridge menu is the default (`menu_touch_uib 1`). The classic
menu is what a player gets with `menu_touch_uib 0`, and what "Advanced settings" on the
bridge's Menu page opens (`m_goto("settings")`). In a match, the classic game menu
(`dialog_touch_gamemenu.qc`) has both a "Settings" button (stock Settings) and a "Mobile
settings" button (the Mobile page), so a player on the classic menu sees two settings
entries side by side.

### 1.1 Bridge menu: Settings

Twelve vertical tabs (`UIB_SecKey`), each with the subtitle shown in the list.

**Video** (Resolution, effects, field of view). Sub-tabs Display / Effects / View.

| Row | Cvar | Control |
|---|---|---|
| Resolution | `r_viewscale` | slider 0.5 to 1, percent |
| Lower the resolution when the frame rate drops | `r_viewscale_fpsscaling` | switch |
| Frame rate limit | `cl_maxfps` | choice 30 / 60 / 120 / None |
| Vertical sync | `vid_vsync` | switch, apply "video" |
| Effects | `vm:video.preset` (execs `effects-<low|med|high|ultra>.cfg`) | choice |
| Field of view | `fov` | slider 60 to 130, deg |
| Contrast | `v_contrast` | slider 0.5 to 3 |
| Brightness | `v_brightness` | slider 0 to 0.5 |
| Show the frame rate | `showfps` | switch |

**Audio** (Volume, music, hit sounds). No sub-tabs.

| Row | Cvar | Control |
|---|---|---|
| Master / Music / Ambient | `volume`, `bgmvolume`, `snd_staticvolume` | sliders 0 to 1, percent |
| Hit sound | `cl_hitsound` | choice Off / On / Lower / Higher |
| Mute when the game is in the background | `snd_mutewhenidle` | switch |

**Game** (Crosshair, weapons, content).

| Row | Cvar | Control |
|---|---|---|
| Crosshair Size / Opacity | `crosshair_size`, `crosshair_alpha` | sliders |
| Switch to better weapons you pick up | `cl_autoswitch` | switch |
| Gentle mode | `cl_gentle` | switch |
| Close the address popup when someone joins | `touch_lan_popup_autoclose` | switch, section "LAN games" |

**Input** (Mouse, keyboard and keys).

| Row | Cvar | Control |
|---|---|---|
| Sensitivity | `sensitivity` | slider 0.5 to 20 |
| Invert vertical look | `vm:input.invert` (sign of `m_pitch`) | switch |
| Forward, Back, Left, Right, Jump, Fire, Secondary fire | binds for `+forward` ... `+fire2` | keybind rows |

**Controls** (key `touch`; subtitle "Presets, HUD, aim assist, auto-shoot, tactics").
Six sub-tabs: Touch controls, Controls, HUD, Aim, Auto-shoot, Tactics.

Touch controls (`touch_uib_touchcontrols.qc`), sections Look / Move stick / Buttons / Layout:

| Row | Cvar | Control |
|---|---|---|
| Swipe sensitivity | `touch_sens_base` | slider 1 to 8, x |
| Up and down, compared with sideways | `touch_sens_y_mult` | slider 0.4 to 1.4, x |
| Faster for quick swipes | `touch_look_accel` | choice Off 0 / On 0.8 / Strong 1.4 |
| Invert up and down | `touch_invert_y` | switch |
| Size (move stick) | `touch_move_size` | slider 0.2 to 0.45, x |
| Dead zone | `touch_stick_deadzone` | slider 0 to 0.35, percent |
| Move at any angle | `touch_stick_analog` | switch |
| Size (buttons) | `touch_scale` | slider 0.7 to 1.4, x |
| Opacity | `touch_opacity` | slider 0.3 to 1, percent |
| Aim while firing | `touch_fire_drag_look` | switch |
| Aim from HOP, DUCK and ALT too | `touch_button_drag_look` | switch |
| HOP | `touch_hop_mode` | choice 3 "Hold, and a KEEP switch" / 0 "Hold" / 2 "Jumps while you move" |
| Aim assist | `cl_touch_aimassist` | switch |
| Spectator bar (FOLLOW / NEXT) | `touch_specbar_visible` | switch |
| Vibrate on button presses | `touch_haptics` | switch, only when `_touch_haptics_available` |
| Preset | `touch_preset` | choice standard / casual / competitive / minimal (sets the cvar only; nothing execs the profile) |
| Left-handed | `touch_handedness` | switch |
| Edit layout | action `touch.edit` (`touch_customize 1`, in a match) | button |
| Reset to defaults | action `touch.reset` (`exec touch_defaults.cfg; exec touch/profiles/standard.cfg; exec touch/profiles/helpers.cfg; touch_topbar_auto 1`) | destructive button, asks first |

Controls:

| Row | Cvar | Control |
|---|---|---|
| Preset | `touch_preset` | cards balanced / casual / competitive / minimal / left |
| Apply the preset again | action `touch.preset` (`exec touch/profiles/<touch_preset>.cfg`, plus `helpers.cfg` unless competitive) | button |
| Control size | `touch_scale` | slider 0.6 to 1.6, x |
| Fire button | `touch_fire_mode` | choice Hold / Toggle |
| Handedness | `touch_handedness` | choice Right / Left |
| Dodge button | `touch_dodge_visible` | switch |

HUD:

| Row | Cvar | Control |
|---|---|---|
| Opacity | `touch_opacity` | slider 0.2 to 1, percent |
| HUD size | `touch_hud_scale` | slider 0.6 to 1.6, x |
| Weapon bar | `touch_weplist_visible` | switch |

Aim:

| Row | Cvar | Control |
|---|---|---|
| Look speed | `touch_sens_base` | slider 0.2 to 4, x |
| Invert vertical look | `touch_invert_y` | switch |
| Aim assist | `cl_touch_aimassist` | switch |
| Pull | `cl_touch_aimassist_strength` | slider 0 to 1, percent |
| Show the target ring | `cl_touch_aim_ring` | switch |

Auto-shoot:

| Row | Cvar | Control |
|---|---|---|
| Fire automatically at enemies | `cl_touch_autoshoot` | switch |
| Switch weapons for the situation | `cl_touch_autoswitch` | switch |
| Dodge incoming fire | `cl_touch_autododge` | switch |

Tactics (caption "Tactics play for you in matches on this device only."):

| Row | Cvar | Control |
|---|---|---|
| Move to good positions | `cl_touch_practice_position` | switch |
| Play the objective | `cl_touch_practice_objectives` | switch |
| Watch the biggest threat | `cl_touch_practice_threat` | switch |

**ESP** (Offline matches only).

| Row | Cvar | Control |
|---|---|---|
| ESP in local matches | `cl_touch_esp_enabled` (string: "local" / "off") | switch, writes "1" / "0" |
| Outline enemies behind walls | `cl_touch_practice_seethrough` | switch |

**Player** (Name, character, colours). One page, no sub-tabs
(`qcsrc/menu/xonotic/touch_uib_player.qc`).

| Row | Cvar | Control |
|---|---|---|
| Name | `_cl_name` | field, apply "reconnect"; shows the colour codes themselves (`^` is written `^^` for the bridge, which strips codes from display text) |
| Name colours | appends `^0`..`^9` to `_cl_name` via `vm:player.namecolour` | `colour` with a 10-entry palette and `labels` "0".."9" |
| Character | `_cl_playermodel` + `_cl_playerskin` | the model's preview picture, `<` and `>` to flip through stock and uploaded models in one cycle, its name and "n of N" under it; Delete only on an uploaded one |
| Shirt / Pants | `_cl_color` via `vm:player.shirt`, `vm:player.pants` | `colour` pickers |
| Your own model | `uib_pm_import`, `godnotic-playermodels.txt` | file picker; a model that passes the check becomes the character, one that does not says why |

**Privacy** (Statistics and identity).

| Row | Cvar | Control |
|---|---|---|
| Statistics | `cl_allow_uidtracking` | choice Off / On |
| Rankings | `cl_allow_uidranking` | choice Off / On |
| Show my name in records | `cl_allow_uid2name` | choice Off / On |

**Updates** (Version, checks and downloads). One of three pages by platform: AndySU on
Android, the app's own updater on a plain APK, or a text for Flatpak / Click / web. The
settings on it are the three updater switches `vm:updates.auto`, `vm:updates.wifi`,
`vm:updates.live` (requests to the updater, not cvars). Everything else is status and
buttons (Check now, Download now, Apply now, What is new).

**Data** (Game data and downloads). A status card with Download / Check for new data,
and one switch: Download in the background, bound to `_touch_asset_fetch_background`.

**Menu** (Size and layout).

| Row | Cvar | Control |
|---|---|---|
| Menu size | `menu_touch_size` | slider 0.7 to 1.5, x |
| New menu | `menu_touch_uib` | switch |
| Ask before quitting | `menu_showquitdialog` | switch |
| Classic menu: Advanced settings / Media / Player model / Xonotic credits | `m_goto` to the stock dialogs | buttons |

**Mods** (Client and server mods, pages from mods). Install from file / link, How to make
a mod, sub-tabs Client mods / Server mods / Pages from mods. Not settings; listed for
completeness.

### 1.2 Classic menu: Settings > Mobile

`dialog_settings_touch.qc`, dialog name `touchsettings`. Eleven sub-tabs; on a touch
screen two rows of them (Controls, HUD, Aim, Auto-shoot, Tactics, ESP / Dynamics, Loser,
Privacy, Menu, Touch), on a desktop one row in a different order. Rows with the same cvar
as a bridge row are marked **dup**.

**Controls** (`XonoticMobileControlsTab`)

| Row | Cvar | Control |
|---|---|---|
| Input mode | `vid_touchscreen_mode` | radio Dynamic 1 / Touch 2 / Keyboard & mouse 0 |
| Switch mid-game | `vid_touchscreen_live` | checkbox |
| Announce switches | `vid_touchscreen_notify` | checkbox |
| Touch-only devices | `vid_touchscreen_touchonly` | checkbox |
| Rescan hardware | `vid_touchscreen_rescan` | button |
| Layout preset: Standard / Casual / Competitive / Left hand / Minimal | `exec touch/profiles/<x>.cfg` | buttons **dup** (`touch_preset`) |
| Opacity | `touch_opacity` | slider 0.3 to 1 **dup** |
| Scale | `touch_scale` | slider 0.7 to 1.4 **dup** |
| Glass tint | `touch_glass_hollow` | slider 0 to 1 |
| Left-handed | `touch_handedness` | checkbox **dup** |
| Dead zone | `touch_stick_deadzone` | slider 0 to 0.5 **dup** |
| Reach | `touch_stick_range` | slider 0.6 to 1.5 |
| FIRE button | `touch_fire_mode` | radio Hold / Toggle **dup** |
| Dodge button | `touch_dodge_visible` | checkbox **dup** |
| HOP button | `touch_hop_mode` | radio 3 / 0 / 1 / 2 **dup** (the bridge lacks mode 1) |
| Customize layout / Save layout / Reset preset / Apply & refresh | commands | buttons |

**HUD** (`XonoticMobileHudTab`)

| Row | Cvar | Control |
|---|---|---|
| Weapon bar Size | `cl_touch_weplist_scale` | slider 0.7 to 2.4 |
| Show the weapon bar | `touch_weplist_visible` | checkbox **dup** |
| Across / Down | `touch_weplist_x` 0.04 to 0.97, `touch_weplist_y` 0.15 to 0.85 | sliders |
| Readout size | `touch_hud_scale` | slider 0.6 to 1.4 **dup** |
| Hook button | `touch_hook_visible` | radio Off 0 / When available 2 / Always 1 |
| Catch me with the hook when I fall off the map | `cl_touch_hook_rescue` | checkbox |
| TURN button | `touch_turn_visible` | checkbox |
| Turn by | `touch_turn_angle` | slider 90 to 360 step 45 |
| NADE row | `cl_touch_nade_row` | checkbox |
| Throw before it blows | `cl_touch_nade_autothrow` | checkbox |
| Keep MENU, CHAT and SCORE on screen with a keyboard | `touch_desk_pills` | checkbox |

**Aim** (`XonoticMobileAimTab`)

| Row | Cvar | Control |
|---|---|---|
| Sensitivity | `touch_sens_base` | slider 0.1 to 50 plus text box **dup** |
| Vertical | `touch_sens_y_mult` | slider 0.5 to 1.5 **dup** |
| Invert look Y | `touch_invert_y` | checkbox **dup** |
| Aim assist | `cl_touch_aimassist` | checkbox **dup** |
| Light / Medium / Strong / Off | set `cl_touch_aimassist_friction`, `_strength`, `_maxrate`, `_cone` together | buttons |
| Slowdown | `cl_touch_aimassist_friction` | slider 0 to 0.8 |
| Pull | `cl_touch_aimassist_strength` | slider 0 to 1 **dup** |
| Catch cone | `cl_touch_aimassist_cone` | slider 1 to 60 |
| Max pull | `cl_touch_aimassist_maxrate` | slider 0 to 180 |
| Show target ring | `cl_touch_aim_ring` | checkbox **dup** |
| Keep tracking the enemy I am shooting | `cl_touch_aimassist_track` | checkbox |
| Auto dodge | `cl_touch_autododge` | checkbox **dup** |
| Servers and local / Local only | `cl_touch_autododge_scope` | radio |
| Shots from behind too | `cl_touch_autododge_rear` | checkbox |
| Show an arrow when it dodges | `cl_touch_autododge_cue` | checkbox |
| Ledge guard | `cl_touch_ledgeguard` | radio Off / Deadly falls / Any long drop |

**Auto-shoot** (`XonoticMobileAutoShootTab`)

| Row | Cvar | Control |
|---|---|---|
| Fire automatically at enemies | `cl_touch_autoshoot` | checkbox **dup** |
| Reaction delay | `cl_touch_autoshoot_delay` | slider 0 to 0.5 s |
| Shoot radius | `cl_touch_autoshoot_radius` | slider 0 to 15 deg |
| Keep firing | `cl_touch_autoshoot_release` | slider 0 to 0.5 s |
| Weapons | `cl_touch_autoshoot_weapons` | radio Every weapon / Instant-hit weapons only |
| Only while a finger is on the aim area or FIRE | `cl_touch_autoshoot_aimonly` | checkbox |
| Maximum range | `cl_touch_aim_range` | text slider 500 ... 6000 / Unlimited |
| Targets | `cl_touch_aim_mode` | radio Radius / Field of view / Full 360 (offline only) |
| Keep the target | `cl_touch_aimassist_hysteresis` | slider 0 to 10 |
| Pick the best of several enemies | `cl_touch_aimassist_priority` | checkbox |
| Never with a teammate in the way | `cl_touch_autoshoot_teamsafe` | checkbox |

**Tactics** (`XonoticMobileTacticsTab`): nineteen checkboxes and one text slider, all
defaulting on in `touch/profiles/helpers.cfg`:
`cl_touch_autoshoot_alt`, `_lead`, `_steer`, `_safe`, `_air`, `_predict`, `_feet`,
`_group`, `_clear`, `_volley`, `_head`, `_head_rifle` (No bonus / x1.25 / x1.5 / x2),
`_combo`; `cl_touch_autoswitch` **dup**, `_smart`, `_combo`, `_lastresort`;
`cl_touch_aimassist_lastseen`.

**ESP** (`dialog_touch_esp.qc`)

| Row | Cvar | Control |
|---|---|---|
| Enable ESP and practice helpers | `cl_touch_esp_enabled` | string checkbox "local" / "off" **dup** |
| See enemies through walls | `cl_touch_practice_seethrough` | checkbox **dup** |
| Enemy health, armour and weapon | `cl_touch_practice_intel` | checkbox |
| Full 360 targets / Instant turn / Exact lead | `cl_touch_practice_360`, `_instant`, `_exact` | checkboxes |
| Burst splash beside hidden enemies | `cl_touch_practice_burst` | checkbox |
| Move for me / Dodge aimed shots / Size up enemies | `cl_touch_practice_pilot`, `_aimdodge`, `_threat` **dup** | checkboxes |
| Play the objective / Grab pickups nearby / Best spot to fight | `_objectives` **dup**, `_detour`, `_position` **dup** | checkboxes |
| Hide from the others | `cl_touch_practice_exposure` | checkbox |
| Pickup magnet, radius | `cl_touch_practice_magnet`, `_magnet_radius` 32 to 512 | checkbox, slider |

**Dynamics** (`XonoticMobileDynamicsTab`)

| Row | Cvar | Control |
|---|---|---|
| 30 fps saver / 60 fps / Best quality | `exec touch/profiles/battery.cfg` / `balanced.cfg` / `quality.cfg` | buttons |
| Frame cap | `cl_maxfps` | mixed slider 20 to 2048 / Unlimited **dup** |
| Vertical sync | `vid_vsync` | checkbox **dup** |
| Lower the resolution to hold the target frame rate | `r_viewscale_fpsscaling` | checkbox **dup** |
| Target fps / Lowest scale | `r_viewscale_fpsscaling_target` 20 to 120, `r_viewscale_fpsscaling_min` 0.25 to 1 | sliders |
| Render scale | `r_viewscale` | slider 0.4 to 1 **dup** |

**Loser** (`XonoticMobileLoserTab`): one checkbox, `cl_touch_loser`, "(Development)
Loser mode ... For development and testing only."

**Privacy** (`dialog_touch_privacy.qc`): `cl_allow_uidtracking` **dup**,
`cl_allow_uid2name` **dup**, `cl_allow_uidranking` **dup** (the last two greyed out
while tracking is off), `_cl_name` with an Apply button **dup**, a Player ID
explanation, and "Read Xonotic's terms of service".

**Menu** (`touch_menu_settings.qc`): `menu_touch_size` 0.6 to 1.6 with an Apply button
and a size preview **dup**; "Use desktop layout" (`menu_touch_size` = -1); a "Screen: N
dpi, rows N dp" label; Keyboard for text fields `touch_menu_keyboard` Automatic / Game
keys / System keyboard.

**Touch** (`dialog_touch_controls.qc`): the bridge's Touch controls rows again
(`touch_sens_base` 1 to 8, `touch_sens_y_mult`, `touch_look_accel`, `touch_invert_y`,
`touch_move_size`, `touch_stick_deadzone` 0 to 0.35, `touch_stick_analog`,
`touch_scale` 0.7 to 1.4, `touch_opacity` 0.3 to 1, `touch_fire_drag_look`,
`touch_button_drag_look`, `cl_touch_aimassist`, `touch_haptics`,
`touch_specbar_visible`, `touch_handedness`, Edit layout, Reset to defaults), all
**dup**, minus `touch_hop_mode` (a label says "Its modes are on the Controls tab") and
`touch_preset`.

### 1.3 What is wrong with it, in one list

Numbered so the tables below can refer to them.

1. **The same cvar on several pages, with different ranges and names.** Inside the
   bridge's own Controls section: `touch_sens_base` (1 to 8 "Swipe sensitivity" on Touch
   controls, 0.2 to 4 "Look speed" on Aim; default 3.6), `touch_scale` (0.7 to 1.4
   "Size", 0.6 to 1.6 "Control size"), `touch_opacity` (0.3 to 1, 0.2 to 1),
   `touch_handedness` (a switch, a Right / Left choice), `touch_invert_y`,
   `cl_touch_aimassist`, `touch_preset`. Across the two menus add `touch_sens_y_mult`
   (0.4 to 1.4, 0.5 to 1.5), `touch_stick_deadzone` (0 to 0.35, 0 to 0.5),
   `menu_touch_size` (0.7 to 1.5, 0.6 to 1.6), `r_viewscale` (0.5 to 1, 0.4 to 1),
   `cl_maxfps` (a four-way choice, a 20 to 2048 slider).
2. **Two preset lists that disagree, one of them wrong.** Touch controls offers
   standard / casual / competitive / minimal; the Controls sub-tab offers balanced /
   casual / competitive / minimal / left. `touch/profiles/balanced.cfg` is not a layout:
   it is the 60 fps performance profile (`touch_performance_profile "balanced"`,
   `cl_maxfps 60`, texture and shadow cvars). Picking "Balanced" then "Apply the preset
   again" execs that file and changes nothing about the controls, and leaves
   `touch_preset "balanced"`, which the layout migration in `touch_init.qc`
   (`exec touch/profiles/<preset>.cfg`) will exec again on the next schema bump. The
   shipped default `standard` (`touch_defaults.cfg`) has no card on that sub-tab, so a
   fresh install shows no preset selected. `left` is `touch_handedness 1` and nothing
   else: the Left-handed switch again.
3. **A preset control that does nothing.** The Touch controls `touch_preset` choice is a
   plain `cvar:` bind. The engine has no watcher on `touch_preset`; the CSQC reads it
   only on migration. The profile is exec'd only by the "Apply the preset again" button
   on the other sub-tab, or by `Touch_ApplyPreset` from the console.
4. **A section named for its code.** The bridge section is keyed `touch`, labelled
   "Controls", and has sub-tabs "Touch controls" and "Controls". The stock-style
   "Input" section (mouse, keys) sits next to it. The classic page has a "Controls" tab
   and a "Touch" tab.
5. **Assists spread over four tabs.** Aim assist is on Touch controls (under "Buttons"),
   Aim, and the classic Aim and Touch tabs. Auto-dodge is on Auto-shoot (bridge) and Aim
   (classic). Ledge guard, which is a movement assist, is on the classic Aim tab.
6. **ESP's children outside ESP.** The bridge Tactics sub-tab holds three
   `cl_touch_practice_*` switches (gated to offline matches by `TouchTactics_EspGate`)
   with the caption "Tactics play for you in matches on this device only"; the ESP
   section, one tab over, holds two more and the master switch.
7. **A boolean switch on a string cvar.** `cl_touch_esp_enabled` takes "local" or
   "off"; the bridge switch writes "1" / "0". `touch_tactics.c` accepts "0" as off and
   prints "only local or off; using local" to the console once for "1".
8. **Developer controls shipped.** The Loser tab (`cl_touch_loser`); `vid_touchscreen_notify`
   ("Show a message when the controls switch by themselves"); `vid_touchscreen_live` and
   `vid_touchscreen_touchonly` (detection policy).
9. **Tuning constants as sliders.** `cl_touch_aimassist_friction`, `_cone`, `_maxrate`,
   `_hysteresis`; `cl_touch_autoshoot_delay`, `_radius`, `_release`;
   `touch_stick_range`; `cl_touch_practice_magnet_radius` in game units;
   `cl_touch_aim_range` in game units.
10. **Nineteen Tactics switches** describing how auto-shoot aims, every one on by
    default. Plus `cl_touch_autoshoot_head_rifle`, a value "the game cannot read".
11. **Positions as sliders.** `touch_weplist_x` / `touch_weplist_y`, when Edit layout
    drags the bar.
12. **Status pages as sections.** Updates and Data each take a top-level tab for a card
    and one to three switches; Updates has three platform variants of the page.
13. **Two identical performance profiles.** `touch/profiles/thermal.cfg` (the default,
    exec'd by `touch/startup.cfg`) and `battery.cfg` set the same cvars to the same
    values; only the comments and the `touch_performance_profile` name differ. The
    Dynamics tab offers "30 fps saver" (battery), the startup chain applies thermal, and
    the bridge offers neither.
14. **Buried on a phone, where it matters most.** Only the classic menu has: the
    performance profiles; `vid_touchscreen_mode` (show the controls, or hide them with a
    keyboard); `touch_menu_keyboard` (the system keyboard for Chinese, Japanese, Korean,
    Cyrillic, Greek); `cl_touch_ledgeguard`; `cl_touch_hook_rescue`; `touch_turn_visible`;
    `touch_hook_visible`; `cl_touch_nade_row`; HOP mode 1 "Hold to keep";
    `cl_touch_autododge_scope`. A player on the default menu reaches them only through
    Menu > Advanced settings > Mobile.
15. **Controls that need a second control.** The classic Aim sensitivity slider (0.1 to
    50) with a text box beside it; the classic Menu size slider with an Apply button; the
    classic Controls tab's "Apply & refresh" (`menu_cmd sync`) and "Save layout".
16. **A switch bound to a transient cvar.** Data > Download in the background binds
    `_touch_asset_fetch_background`; underscore cvars are engine state, not settings.
17. **Booleans drawn as choices.** Privacy's three Off / On segmented choices for
    `cl_allow_uidtracking`, `cl_allow_uidranking`, `cl_allow_uid2name`, with no
    dependency between them (the classic tab greys the last two out while tracking is
    off).
18. **Stale docs.** `docs/CONTROLS.md` "Touch controls settings" lists "Stick appears
    under your thumb" (`touch_stick_float`), which `touch_init.qc` marks legacy ("the
    CSQC stick is always fixed") and neither menu shows. The same table omits
    `touch_hop_mode`'s absence from the classic Touch tab.

## 2. The proposed tree

One menu. The bridge Settings keeps its vertical tabs and horizontal sub-tabs; the
classic Mobile page is reduced to what the bridge does not show, reachable from
Menu > Advanced settings as today, and is not a second home for anything in the bridge.

Nine sections, in this order. Each sub-tab lists its rows with the cvar and the control.
"Default" means a value the row no longer exposes and the cvar keeps.

### Controls
Subtitle: "Look, move stick, buttons, layout".

- **Look**: Swipe sensitivity `touch_sens_base` (1 to 8, x); Up and down, compared with
  sideways `touch_sens_y_mult` (0.4 to 1.4, x); Faster for quick swipes
  `touch_look_accel` (Off / On / Strong); Invert up and down `touch_invert_y`.
- **Move**: Stick size `touch_move_size`; Dead zone `touch_stick_deadzone` (0 to 0.35,
  percent); Move at any angle `touch_stick_analog`; Ledge guard `cl_touch_ledgeguard`
  (Off / Deadly falls / Any long drop; from the classic Aim tab); Quick turn
  `touch_turn_visible` with Turn by `touch_turn_angle` (90 to 360 step 45, deg; from the
  classic HUD tab); Dodge button `touch_dodge_visible`.
- **Buttons**: Size `touch_scale` (0.7 to 1.4, x); Opacity `touch_opacity` (0.3 to 1,
  percent); Hollow controls `touch_glass_hollow` as a switch at 0 / 1 (today a 0 to 1
  slider; the two ends are the only values anyone picks); Fire button `touch_fire_mode`
  (Hold / Toggle); Aim while firing `touch_fire_drag_look`; Aim from HOP, DUCK and ALT
  too `touch_button_drag_look`; HOP `touch_hop_mode` with all four modes, labelled
  "Hold" 0 / "Hold, and a KEEP switch" 3 / "Keeps going after a long press" 1 / "Jumps
  while you move" 2; Hook button `touch_hook_visible` (Off / When available / Always);
  Grenade row `cl_touch_nade_row` (with `cl_touch_nade_autothrow` as a default, on);
  Spectator bar `touch_specbar_visible`; Vibrate on button presses `touch_haptics`
  (gated as today).
- **Layout**: Preset `touch_preset` as cards, one list: Standard / Casual / Competitive /
  Minimal, and choosing a card applies the profile (the bind gets the `touch.preset`
  side effect, or the cards become actions); Left-handed `touch_handedness` switch
  (the `left` profile and card go); Edit layout; Reset to defaults.
- **Keyboard and mouse** (the old Input section, folded in): On-screen controls
  `vid_touchscreen_mode` as a choice "On a touchscreen" 1 / "Always" 2 / "Never" 0;
  Keep MENU, CHAT and SCORE with a keyboard `touch_desk_pills`; Mouse sensitivity
  `sensitivity`; Invert mouse `vm:input.invert`; the seven keybinds.

### Assists
New section. Subtitle: "Aim assist, auto-shoot, auto-dodge, hook rescue". Help on the
page: "Assists work in matches on this device, and on servers that allow them."

- **Aim assist**: Aim assist `cl_touch_aimassist`; Strength as a choice Light / Medium /
  Strong writing the four tuning cvars exactly as the classic buttons do today
  (`cl_touch_aimassist_friction`, `_strength`, `_maxrate`, `_cone`; a `vm:` bind that
  reads back which preset matches, "Custom" when none); Show the target ring
  `cl_touch_aim_ring`. Defaults: `_track`, `_hysteresis`, `_priority`, `_lastseen`,
  `cl_touch_aim_mode`, `cl_touch_aim_range`.
- **Auto-shoot**: Fire automatically at enemies `cl_touch_autoshoot`; Weapons
  `cl_touch_autoshoot_weapons` (Every weapon / Instant-hit only); Only while a finger is
  on the aim area or FIRE `cl_touch_autoshoot_aimonly`; Switch to the best weapon for the
  fight `cl_touch_autoswitch`. Defaults: `_delay`, `_radius`, `_release`, `_teamsafe`
  and every Tactics cvar, with `_head` off and `_head_rifle` removed from the menu.
- **Auto-dodge**: Dodge incoming fire `cl_touch_autododge`; Where
  `cl_touch_autododge_scope` (Servers and local / Local only). Defaults: `_rear`, `_cue`.
- **Hook**: Catch me with the hook when I fall off the map `cl_touch_hook_rescue`.

### Practice
The ESP section renamed; the icon stays. Subtitle: "Offline matches against bots only".
The note at the top stays. One master switch then the helpers, greyed while it is off.

- Practice helpers `cl_touch_esp_enabled` as a choice On ("local") / Off ("off"), or the
  cvar becomes a boolean; today's switch is wrong either way (1.3 item 7).
- **See**: See enemies through walls `_seethrough`; Enemy health, armour and weapon
  `_intel`.
- **Aim**: Full 360 targets `_360`; Instant turn `_instant`; Exact lead `_exact`; Burst
  splash beside hidden enemies `_burst`.
- **Autopilot**: Move for me `_pilot`; Dodge aimed shots `_aimdodge`; Size up enemies
  `_threat`; Play the objective `_objectives`; Grab pickups nearby `_detour`; Best spot
  to fight `_position`; Hide from the others `_exposure`; Pickup magnet `_magnet`
  (radius becomes a default).

The bridge Tactics sub-tab goes; its three switches are here.

### HUD
Subtitle: "Readouts, weapon bar, crosshair".

- Readout size `touch_hud_scale` (0.6 to 1.6, x); Weapon bar `touch_weplist_visible`;
  Weapon bar size `cl_touch_weplist_scale` (0.7 to 2.4, x; from the classic HUD tab);
  Crosshair size and opacity `crosshair_size`, `crosshair_alpha` (from Game); Show the
  frame rate `showfps` (from Video > View). `touch_weplist_x` / `_y` leave the menu: Edit
  layout owns positions.

### Video and battery
Video renamed; subtitle "Performance profile, frame rate, effects, view".

- **Performance**: Profile as cards Battery saver / Balanced / Quality, each exec'ing its
  `touch/profiles/*.cfg` (thermal.cfg merged into battery.cfg, 1.3 item 13;
  `touch_performance_profile` reads back which is current); Frame rate limit `cl_maxfps`
  (30 / 60 / 120 / None); Vertical sync `vid_vsync`; Lower the resolution when the frame
  rate drops `r_viewscale_fpsscaling` (target and minimum become defaults); Resolution
  `r_viewscale` (0.5 to 1, percent).
- **Effects**: as today.
- **View**: Field of view, Contrast, Brightness as today.

### Audio
As today.

### Game
Subtitle: "Weapons, content". Switch to better weapons you pick up `cl_autoswitch`;
Gentle mode `cl_gentle`. "Close the address popup when someone joins"
(`touch_lan_popup_autoclose`) moves to the Create screen's LAN box, which already has
the same switch (docs/UI_BRIDGE.md, Create), and leaves Settings.

### Player
Subtitle: "Name, character, colours". One page (see the Player table above). Privacy is its own section.

- **Privacy**: the three statistics switches as switches, Rankings and Show my name
  greyed while Statistics is off (as the classic tab does), the "Nothing is shared"
  caption, and "Read Xonotic's terms of service" from the classic tab.

### App
The Menu section renamed; subtitle "Menu size, keyboard, updates, game data".

- **Menu**: Menu size `menu_touch_size` (0.6 to 1.6, x, the cvar's documented range);
  Keyboard for text fields `touch_menu_keyboard` (Automatic / Game keys / System
  keyboard; from the classic Menu tab); Ask before quitting `menu_showquitdialog`;
  Classic menu `menu_touch_uib` with the help it has; the Advanced settings / Media /
  Player model / Xonotic credits row.
- **Updates**: today's Updates page as a sub-tab.
- **Game data**: today's Data page as a sub-tab, with the switch bound to an archived
  `touch_asset_fetch_background` (no underscore) or removed in favour of a default.

### Mods
As today.

### What leaves the menu entirely

Console and profiles only, with their cvar description strings as the documentation:
`cl_touch_loser`; `vid_touchscreen_live`, `vid_touchscreen_notify`,
`vid_touchscreen_touchonly`, the Rescan hardware button; `touch_stick_range`;
`cl_touch_aimassist_friction`, `_cone`, `_maxrate`, `_hysteresis`, `_priority`,
`_track`, `_lastseen` (set by the Light / Medium / Strong choice); `cl_touch_aim_mode`,
`cl_touch_aim_range`; `cl_touch_autoshoot_delay`, `_radius`, `_release`, `_teamsafe`,
and all of Tactics (`_alt`, `_lead`, `_steer`, `_safe`, `_air`, `_predict`, `_feet`,
`_group`, `_clear`, `_volley`, `_head`, `_head_rifle`, `_combo`,
`cl_touch_autoswitch_smart`, `_combo`, `_lastresort`); `cl_touch_autododge_rear`,
`_cue`; `cl_touch_nade_autothrow`; `touch_weplist_x`, `touch_weplist_y`;
`r_viewscale_fpsscaling_target`, `_min`; `cl_touch_practice_magnet_radius`; the
classic "Save layout", "Apply & refresh" and "Reset preset" buttons (the layout saves
itself; the bridge applies at once; Reset to defaults covers the last).

## 3. Current location to proposed location

Every row that moves, merges or goes. Rows not listed stay where they are. "Bridge" and
"Classic" name the menu; the number in the reason column is the item in 1.3.

| Cvar or row | Current location | Proposed location | Reason |
|---|---|---|---|
| `touch_sens_base` | Bridge Controls > Touch controls (1 to 8); Bridge Controls > Aim (0.2 to 4); Classic Mobile > Aim (0.1 to 50); Classic Mobile > Touch | Controls > Look, 1 to 8 only | 1: one cvar, three ranges, three names |
| `touch_sens_y_mult` | Bridge Touch controls; Classic Aim (0.5 to 1.5); Classic Touch | Controls > Look | 1 |
| `touch_invert_y` | Bridge Touch controls; Bridge Aim; Classic Aim; Classic Touch | Controls > Look | 1 |
| `touch_look_accel` | Bridge Touch controls; Classic Touch | Controls > Look | stays; the classic copy goes |
| `touch_move_size`, `touch_stick_analog` | Bridge Touch controls; Classic Touch | Controls > Move | stays; the classic copy goes |
| `touch_stick_deadzone` | Bridge Touch controls (0 to 0.35); Classic Controls (0 to 0.5); Classic Touch | Controls > Move, 0 to 0.35 | 1 |
| `touch_stick_range` | Classic Controls ("Reach") | default | 9: a tuning constant |
| `cl_touch_ledgeguard` | Classic Aim | Controls > Move | 14: buried; it is about walking, not aiming |
| `touch_turn_visible`, `touch_turn_angle` | Classic HUD | Controls > Move | 14: a control, not a readout |
| `touch_dodge_visible` | Bridge Controls > Controls; Classic Controls | Controls > Move | 1 |
| `touch_scale` | Bridge Touch controls (0.7 to 1.4); Bridge Controls (0.6 to 1.6); Classic Controls; Classic Touch | Controls > Buttons, 0.7 to 1.4 | 1 |
| `touch_opacity` | Bridge Touch controls (0.3 to 1); Bridge HUD (0.2 to 1); Classic Controls; Classic Touch | Controls > Buttons, 0.3 to 1 | 1 |
| `touch_glass_hollow` | Classic Controls ("Glass tint" slider) | Controls > Buttons, as a switch | 14; a slider whose ends are the only values |
| `touch_fire_mode` | Bridge Controls; Classic Controls | Controls > Buttons | 4: one Controls page |
| `touch_fire_drag_look`, `touch_button_drag_look` | Bridge Touch controls; Classic Touch | Controls > Buttons | stays |
| `touch_hop_mode` | Bridge Touch controls (3 modes); Classic Controls (4 modes) | Controls > Buttons, 4 modes | 1, 14: mode 1 missing from the bridge |
| `touch_hook_visible` | Classic HUD | Controls > Buttons | 14 |
| `cl_touch_nade_row` | Classic HUD | Controls > Buttons | 14 |
| `cl_touch_nade_autothrow` | Classic HUD | default (on) | 9: nobody wants a grenade to blow up in their hand |
| `touch_specbar_visible`, `touch_haptics` | Bridge Touch controls; Classic Touch | Controls > Buttons | stays |
| `touch_preset` | Bridge Touch controls (segmented, inert); Bridge Controls (cards, wrong list); Classic Controls (buttons) | Controls > Layout, cards that apply the profile | 2, 3 |
| "Apply the preset again" | Bridge Controls | gone | 3: choosing the card applies it |
| `touch_handedness` | Bridge Touch controls (switch); Bridge Controls (choice); Classic Controls; Classic Touch; `left` preset | Controls > Layout, one switch | 1, 2 |
| Edit layout, Reset to defaults | Bridge Touch controls; Classic Touch; Classic Controls ("Customize layout", "Reset preset") | Controls > Layout | stays |
| Save layout, Apply & refresh | Classic Controls | gone | 15 |
| `vid_touchscreen_mode` | Classic Controls | Controls > Keyboard and mouse | 14: the headline setting for a tablet with a keyboard |
| `vid_touchscreen_live`, `_notify`, `_touchonly`, Rescan hardware | Classic Controls | console | 8 |
| `touch_desk_pills` | Classic HUD | Controls > Keyboard and mouse | 14; it is about having a keyboard |
| `sensitivity`, `vm:input.invert`, keybinds | Bridge Input | Controls > Keyboard and mouse | 4: one place for how you steer |
| `cl_touch_aimassist` | Bridge Touch controls (under Buttons); Bridge Aim; Classic Aim; Classic Touch | Assists > Aim assist | 1, 5 |
| Light / Medium / Strong / Off | Classic Aim (buttons) | Assists > Aim assist, a Strength choice | the right shape, promoted |
| `cl_touch_aimassist_strength` | Bridge Aim ("Pull"); Classic Aim | set by Strength | 9 |
| `cl_touch_aimassist_friction`, `_cone`, `_maxrate` | Classic Aim | set by Strength | 9 |
| `cl_touch_aimassist_track`, `_hysteresis`, `_priority`, `_lastseen` | Classic Aim, Auto-shoot, Tactics | default | 9, 10 |
| `cl_touch_aim_ring` | Bridge Aim; Classic Aim | Assists > Aim assist | 5 |
| `cl_touch_aim_mode`, `cl_touch_aim_range` | Classic Auto-shoot | default | 9: game units and a cone nobody can picture |
| `cl_touch_autoshoot` | Bridge Auto-shoot; Classic Auto-shoot | Assists > Auto-shoot | 5 |
| `cl_touch_autoshoot_weapons`, `_aimonly` | Classic Auto-shoot | Assists > Auto-shoot | 14: the two choices a player does make |
| `cl_touch_autoshoot_delay`, `_radius`, `_release`, `_teamsafe` | Classic Auto-shoot | default | 9 |
| `cl_touch_autoswitch` | Bridge Auto-shoot; Classic Tactics | Assists > Auto-shoot | 5 |
| the 18 other Tactics cvars, `cl_touch_autoshoot_head_rifle` | Classic Tactics | default; `_head_rifle` out of the menu for good | 10 |
| `cl_touch_autododge` | Bridge Auto-shoot; Classic Aim | Assists > Auto-dodge | 5 |
| `cl_touch_autododge_scope` | Classic Aim | Assists > Auto-dodge | 14 |
| `cl_touch_autododge_rear`, `_cue` | Classic Aim | default | 9 |
| `cl_touch_hook_rescue` | Classic HUD | Assists > Hook | 14; it is an assist, not a readout |
| `cl_touch_esp_enabled` | Bridge ESP (switch writing 1/0); Classic ESP (local/off) | Practice, a choice with the real values | 7 |
| `cl_touch_practice_position`, `_objectives`, `_threat` | Bridge Controls > Tactics; Classic ESP | Practice > Autopilot | 6 |
| `cl_touch_practice_seethrough` | Bridge ESP; Classic ESP | Practice > See | stays |
| the other `cl_touch_practice_*` | Classic ESP | Practice | 14 |
| `cl_touch_practice_magnet_radius` | Classic ESP | default | 9: game units |
| `cl_touch_loser` | Classic Loser tab | console | 8 |
| `touch_hud_scale` | Bridge HUD (0.6 to 1.6); Classic HUD (0.6 to 1.4) | HUD, 0.6 to 1.6 | 1 |
| `touch_weplist_visible` | Bridge HUD; Classic HUD | HUD | stays |
| `cl_touch_weplist_scale` | Classic HUD | HUD | 14 |
| `touch_weplist_x`, `touch_weplist_y` | Classic HUD | gone; Edit layout | 11 |
| `crosshair_size`, `crosshair_alpha` | Bridge Game | HUD | the crosshair is drawn on the HUD |
| `showfps` | Bridge Video > View | HUD | a readout |
| Performance profiles | Classic Dynamics (battery / balanced / quality) | Video and battery > Performance, cards; thermal.cfg merged into battery.cfg | 13, 14 |
| `cl_maxfps` | Bridge Video (choice); Classic Dynamics (slider) | Video and battery > Performance, the choice | 1 |
| `vid_vsync`, `r_viewscale_fpsscaling`, `r_viewscale` | Bridge Video; Classic Dynamics | Video and battery > Performance | 1 |
| `r_viewscale_fpsscaling_target`, `_min` | Classic Dynamics | default | 9 |
| `touch_lan_popup_autoclose` | Bridge Game > LAN games | the Create screen's LAN box only | already there; a hosting option, not a game setting |
| `cl_allow_uidtracking`, `_uidranking`, `_uid2name` | Bridge Privacy (choices); Classic Privacy | Player > Privacy, switches with the dependency | 17 |
| `_cl_name` | Bridge Player > Profile; Classic Privacy | Player > Profile | 1 |
| Terms of service | Classic Privacy | Player > Privacy | 14 |
| `menu_touch_size` | Bridge Menu (0.7 to 1.5); Classic Menu (0.6 to 1.6, Apply, preview) | App > Menu, 0.6 to 1.6 | 1, 15 |
| "Use desktop layout" (`menu_touch_size -1`) | Classic Menu | its own boolean, or gone (the bridge has no desktop layout) | a magic value |
| `touch_menu_keyboard` | Classic Menu | App > Menu | 14: the setting that makes non-Latin text entry work |
| `menu_touch_uib`, `menu_showquitdialog`, classic-menu row | Bridge Menu | App > Menu | stays |
| Updates page | Bridge Updates (section) | App > Updates (sub-tab) | 12 |
| Data page, `_touch_asset_fetch_background` | Bridge Data (section) | App > Game data (sub-tab); archived cvar without the underscore, or a default | 12, 16 |
| Classic "Settings" and "Mobile settings" buttons | `dialog_touch_gamemenu.qc` | one "Settings" | two entries for one thing |

### Flags, by kind

- **Meaningless to a player:** `cl_touch_autoshoot_head_rifle`, `cl_touch_aimassist_hysteresis`,
  `cl_touch_aim_range`, `cl_touch_practice_magnet_radius`, `touch_stick_range`,
  `touch_weplist_x` / `_y`, "Screen: N dpi, rows N dp".
- **Duplicated:** everything in 1.3 item 1; `touch_handedness` against the `left`
  preset; "Settings" against "Mobile settings"; thermal.cfg against battery.cfg.
- **Developer-only:** `cl_touch_loser`, `vid_touchscreen_notify`, `vid_touchscreen_live`,
  `vid_touchscreen_touchonly`, Rescan hardware, "Apply & refresh".
- **Better default instead of a control:** the Tactics tab; aim-assist tuning behind
  Light / Medium / Strong; auto-shoot delay / radius / release; auto-dodge rear / cue;
  `cl_touch_nade_autothrow`; dynamic-resolution target and minimum;
  `_touch_asset_fetch_background`.
- **Wrong control for the cvar:** `cl_touch_esp_enabled` as a switch; `touch_preset` as an
  inert bind; the Privacy choices; `touch_glass_hollow` as a slider; `menu_touch_size`
  carrying -1.
- **Buried and should surface:** performance profiles, `vid_touchscreen_mode`,
  `touch_menu_keyboard`, `cl_touch_ledgeguard`, `cl_touch_hook_rescue`,
  `touch_turn_visible`, `touch_hook_visible`, `cl_touch_nade_row`, HOP mode 1,
  `cl_touch_autododge_scope`, `cl_touch_autoshoot_weapons`, `cl_touch_autoshoot_aimonly`,
  the terms of service.

## 4. Settings search

The search is built over the bridge Settings. It has to work on the proposed tree and on
today's, so nothing below depends on section 2 landing first.

### 4.1 What is indexed

One entry per **control**, never per section or sub-tab on its own. A control is any
node of type `switch`, `slider`, `stepper`, `choice`, `field`, `colour`, `keybind`, or a
`button` whose action changes settings (Edit layout, Reset to defaults, the preset and
performance cards). Status text, progress bars, the Updates version line and the Mods
lists are not entries.

Each entry carries:

| Field | Source | Example |
|---|---|---|
| `node` | the bridge node id | `settings/split/touch/tabs/feel/look/sens` |
| `section`, `tab` | the pane keys on the path | `touch`, `feel` |
| `path` | the labels of the section, sub-tab and enclosing `section` nodes, joined with " > " | Controls > Touch controls > Look |
| `label` | the control's `label` | Swipe sensitivity |
| `help` | the control's `help`, or "" | How far the view turns for a swipe ... |
| `options` | for a `choice`, every option label | Off, On, Strong |
| `cvar` | the `cvar:` bind's name, or the `vm:` key | `touch_sens_base` |
| `aliases` | words a player types that appear in none of the above | sensitivity, look speed, turn speed, swipe |
| `kind` | the node type | slider |
| `gate` | why it may be absent on this device, if gated | `_touch_haptics_available` |

The index is built from the same builders that draw the pages (`UIB_SecContent` and
the `UIB_Sec*` functions), not from a hand-written list, so a new row is searchable the
day it is added. Two things make that harder than it sounds:

- **Only the selected section has content.** `UIB_BuildSettingsRoot` fills one pane and
  `UIB_SubTab` fills one sub-tab. The indexer needs a build pass that runs every
  `UIB_Sec*` with every sub-tab selected, into a scratch root that is never shown
  (`uib_begin` with a private root id, walked with `uib_count` / `uib_child` /
  `uib_type` / `uib_get`, then `uib_remove`). Platform-dependent pages (Updates) are
  indexed in the variant this install shows.
- **Gated rows.** Haptics is built only when `_touch_haptics_available` is set. The index
  pass runs with the real cvar values, so a gated row is absent on a device that lacks it
  and present where it exists. That is correct: a result the page cannot show is useless
  (4.3).

Aliases are the one hand-written part: a table keyed by cvar (or node id for `vm:`
binds), in one file, in `_()` so they are translated with the labels. The first entries
write themselves from the duplicates in 1.3 item 1: every name a cvar has ever had on
any page is an alias of its one remaining row ("Look speed", "Sensitivity" for
`touch_sens_base`; "Control size", "Scale" for `touch_scale`; "Handedness" for
`touch_handedness`; "Dynamics", "Battery", "Frame cap" for the performance rows;
"Joystick" for the move stick; "Bunny hop", "Jump" for HOP; "Wallhack" for Practice;
"Aimbot" for auto-shoot; "Mobile settings" for Controls). A word the search misses in
testing is added here, not to a label.

Matching is case- and accent-insensitive, prefix on every word, over `label`, then
`options`, then `help`, then `aliases`, then `path`, then `cvar`. A hit in `label` ranks
above a hit in `help`; a hit in `cvar` alone ranks last and is shown only when nothing
else matched (a player who types `touch_sens_base` should find it; one who types
"base" should not see it first). Ties keep page order.

### 4.2 How a result opens its row

A result is an action, not a navigation: `settings.goto` with the entry's `section`,
`tab` and `node`. In `UIB_SettingsAction` it does what the `settings.<section>` prefix
does today, and then the two things that prefix does not:

1. `UIB_VMSet("settings.section", section)`, `UIB_VMSet("settings.<section>.tab", tab)`,
   `UIB_VMSet("settings.open", "1")`; push `settings` if it is not the top screen, else
   rebuild. This selects the vertical tab and the horizontal sub-tab, which is as deep as
   today's view model goes.
2. Set a `reveal` property on the Settings screen node: `{"node": <id>, "until": <real
   time + 3 s>}`. The renderer scrolls so the node is in view (under the sticky sub-tab
   bar, not behind it) and highlights it for the duration; the engine clears `reveal` on
   the next rebuild after `until`, or on the next `value` event from that node. On a
   compact screen the pane opens as its own page with the back arrow, exactly as
   choosing a section does; `back` goes to the section list, not to the search.
3. The rows the result sits inside stay where they are: no collapsing of other sections,
   no filtering of the page down to the one row. The player asked where a setting is;
   show it in place, so the next time they can walk there.

A result for a gated row that has since gone (haptics after the engine reports no
vibrator) shows a toast, "Not available on this device", and opens the sub-tab anyway.

The search field itself is a `field` with `kind: "search"` at the top of the Settings
screen, above the split, bound to `vm:settings.query`; results replace the split while
the query is non-empty and the split comes back when it is cleared or a result is
chosen. Nothing about this needs a new node type; `field`, `list` and `reveal` are the
only additions, and `reveal` is a property.

### 4.3 What makes a result useless

A result the player cannot act on is worse than no result, because it costs a tap to
find that out. The index and the ranking must not produce:

- **A section or sub-tab as a result.** "Controls" is not an answer to "sensitivity";
  the row is. Sections appear only as the `path` under a row.
- **The same cvar twice.** While the duplicates of 1.3 item 1 exist, the indexer keeps
  one entry per cvar: the row in the section this document names as its home (section
  3), or failing that the first built. Two results that open two sliders with two ranges
  for one setting would show the player the bug.
- **A row whose text is only its label.** "Size" with no help and no path is three
  rows in this menu. The result always shows `path` and the first sentence of `help`,
  and a `help`-less row whose label is one generic word (Size, Opacity, Pull) must get
  help before it ships (SETTINGS_RULES.md section 4).
- **A row that is not on this device.** Gated rows absent from the build pass, the
  Updates variants this platform does not show, Mods pages from mods not installed.
- **A match on an internal name when the player typed words.** `cvar` is the last field
  searched and never the reason a result ranks first. Node ids (`feel`, `sens`, `esp`)
  are never searched.
- **A button that is not a setting.** Check now, Download now, Install from file, Open
  on a mod page: actions that do a thing do not answer "where do I change X". Buttons
  are indexed only when their action sets settings (presets, profiles, Edit layout,
  Reset to defaults), and their result text says what they do, not "Button".
- **A result that opens the classic menu.** Advanced settings, Media, Player model and
  Xonotic credits open stock dialogs the bridge cannot scroll to a row in. They are not
  indexed; a query for something only the classic menu has (while section 2 is pending)
  gets one fixed footer line, "More in Advanced settings", that opens that dialog.
- **A help string that is wrong for the row it is on.** Help that cross-references a
  tab by name ("Targets: Radius, Settings > Auto-shoot") will be read in the search list
  out of context; when the tab moves the result lies twice.
- **Dead features.** `touch_gyro_*`, `touch_stick_float`, `touch_stick_follow` have no
  row and must not get an alias either, or a player searching "gyro" is told the game
  has it.

### 4.4 Test

`tests/uibridge/` gets a fixture `settings-search.json` built the same way as
`settings-touch.json`: the index as the engine emits it on the 1280x720 desktop caps,
and one `seq-` sequence that types "sens", takes the first result, and checks that the
snapshot after it has `settings.section` = `touch`, the right sub-tab, and `reveal`
naming `settings/split/touch/tabs/feel/look/sens`. The fixture is regenerated whenever a
row is added, moved or renamed; a diff in it is the review's cue that the search changed.

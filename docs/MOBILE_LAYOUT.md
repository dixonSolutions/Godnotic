# Mobile layout: fixed-stick arena preset

This is the default two-thumb landscape layout. Coordinates below are fractions of the **full HUD viewport**, not safe-area-relative: the current HUD uses viewport fractions; the automatically positioned top bar separately observes platform safe insets. This distinction matters on notched devices. The diagram is a 16:9 illustration, not a promise that controls are inset from cutouts. Existing per-control layout editing remains available. Mirror (`touch_handedness 1`) reflects horizontal positions and zones.

Sizes are fractions of the shorter viewport dimension, before `touch_scale`; circles give diameter, pills give height × aspect. Existing density-independent floors apply (48 dp action targets, 128 dp stick diameter), so actual sizes may be larger. This is a layout hypothesis pending phone playtests, not a measured universal reach envelope.

| Control | Centre x,y | Size | Purpose |
|---|---|---|---|
| Move stick | .170,.680 | .400 diameter, minimum 128 dp | Fixed centre, analog knob; left thumb, larger than .300. Move zone x 0–.32 claims a finger; landing off-centre immediately moves relative to the fixed centre. |
| Primary fire | .855,.680 | .160 circle | Right thumb; hold and drag to aim. |
| Alternate fire | .770,.755 | .100 circle | Near fire; context sensitive. |
| Jump / hop | .855,.885 | .072 × 2.6 pill | Below fire; hold to jump. Hop-lock chip .675,.760, .052 square enables continuous jumping. |
| Crouch | .700,.885 | .080 circle | Lower inner arc. |
| Zoom | .860,.480 | .100 circle | Above fire; contextual. |
| Hook | .765,.615 | .100 circle | Contextual. |
| 180° turn | .300,.840 | .085 circle | Left inner edge. |
| Weapon strip | .962,.360 | 46×26 reference units per row, scale 1.3 | Tap owned weapon; scroll/resize as supported. Fallback weapon button .540,.860, .100 circle. |
| Reload / dodge | .720,.480 / .165,.480 | .100 circles | Optional, hidden by default. |
| Menu / score / chat | auto top bar; fallback .655/.772/.889, y .088 | .060 × 2.4 pills | Infrequent, away from combat; bar uses safe insets. Chat opens existing sheet. |
| Console | optional .090,.948 | .060 × 2.9 pill | Hidden by default. |
| Vitals | .150,.115 | HUD scale .85 | Health and armor at upper left, clear of central aim. |
| Spectator | .815,.885 | .085 bar | Existing context-specific spectator controls replace play actions. |

The left thumb has continuous movement; the right has fire, hop and secondary actions on a short arc. The centre is reserved for view and look swipes (x .32–1, except controls); fire can also drag-look, mitigating the two-thumb trade-off of aiming while firing. The strip and top bar are deliberately less immediate. A phone's dimensions and hands vary; users should reposition controls and test reach, particularly around notches and overlapping hit targets.

## Settings and behavior

Existing settings include: `touch_sens_base 3.6`, `touch_sens_y_mult 0.85`, `touch_look_accel 0.8` (speed ramp 150–900), filter 1; `touch_scale 1`, `touch_opacity 0.90`, `touch_handedness 0`, `touch_fire_mode 0` (hold; 1 toggle), `touch_auto_fire 0`, `cl_touch_aimassist 1`, and `touch_hop_mode 3`. Stick deadzone .12, run threshold .6, range 1, analog 1. The stick centre never follows or moves to the touch, including with old floating settings; its knob follows the finger and clamps at the rim. The size can be changed with the existing stick size slider. Existing layouts, modes, spectator and chat behavior otherwise remain intact. Auto-fire is off by default; aim assist is enabled by default and can be disabled. Intentional fire suits varied arena weapons, while assist can help touch aiming. Speed-dependent gain allows fine slow aim and quick turns; tune sensitivity and acceleration to taste. No claim of a universally correct sensitivity is made.

## Evidence and borrowing

[Quad Touch](https://opentouchgaming.com/quad-touch/) advertises customizable controls; we retain the editor and weapon access rather than claim its proprietary layout numbers. [Call of Duty Mobile](https://blog.activision.com/call-of-duty/2019-10/Getting-a-Grip-on-the-Call-of-Duty-Mobile-Controls) documents fixed joystick and simple/advanced fire choices; this design fixes the stick as requested and retains optional fire modes. [Microsoft's touch layout guide](https://learn.microsoft.com/en-us/gaming/gdk/_content/gc/system/overviews/game-streaming/building-touch-layouts/game-streaming-tak-designers-guide) motivates action arcs and room to drag aim. [Android accessibility guidance](https://support.google.com/accessibility/android/answer/7101858) recommends 48 dp targets; the HUD already supplies floors. [Thumb reach research](https://pubmed.ncbi.nlm.nih.gov/23516793/) shows reach varies, so positions are starting points, not measured reach guarantees. The repo's [touch research](TOUCH_UX_RESEARCH.md) surveys Delta Touch, sensitivity and assist. The precise numbers above derive from the existing Godnotic preset, except the enlarged stick; they require device testing.

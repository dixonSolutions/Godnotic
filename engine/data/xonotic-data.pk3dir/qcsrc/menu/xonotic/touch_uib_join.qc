// SPDX-License-Identifier: GPL-3.0-or-later
#include "touch_uib_touch.qh"
#include "dialog_touch_join.qh"

// Godnotic UI bridge: the join popup. Xonotic's join dialog (auto-select, the
// teams the server offers, spectate) as a description, so a native renderer
// shows it as its own dialog: id "join", a modal in the game context.
// UIB_JoinOpen is what "menu_cmd directmenu TeamSelect" becomes when a native
// renderer draws the menu (TouchGameMenu_Route); the engine sends that for an
// observer when a match starts and from Join game / Change team. Without a
// native renderer the dialog is dialog_touch_join.qc.
// Each button's action is "join.<choice>" and runs "touch_join <choice>".

bool uib_join_watching;   // opened for a player who was watching: closes once they play
string uib_join_seen;     // what the popup was built from (teams, counts): rebuilt when it changes

// Players in a team ("red blue yellow pink" from the engine's scoreboard read).
float UIB_JoinTeamCount(float bit)
{
	tokenize_console(cvar_string("_touch_team_players"));
	return stof(argv(bit == 1 ? 0 : bit == 2 ? 1 : bit == 4 ? 2 : 3));
}

string UIB_JoinPlayers(float n)
{
	if (n == 1) return _("1 player");
	return sprintf(_("%d players"), n);
}

// One team: Xonotic's team colour as a swatch the renderer paints the button
// with, and how many are in it.
void UIB_JoinTeam(float bit, string key, string label, string swatch)
{
	uib_node(key, "button");
	uib_s("label", label);
	uib_j("action", UIB_A(strcat("join.", key), label, "team"));
	uib_s("style", "secondary");
	uib_s("team", key);
	uib_s("swatch", swatch);
	uib_s("subtitle", UIB_JoinPlayers(UIB_JoinTeamCount(bit)));
	uib_end();
}

string UIB_JoinState()
{
	return strcat(cvar_string("_teams_available"), ":", cvar_string("_touch_team_players"), ":",
		cvar_string("_touch_match_players"), ":", ftos(cvar("_touch_hud_flags") & 4));
}

// Xonotic's Team Selection, in our style: auto-select first (what the game
// recommends), the teams it offers in their colours with how many play in
// each, Spectate last. Without teams: Join and Spectate. Only a choice or
// Back closes it: a tap beside it does nothing (it opens by itself, often
// while the player is still tapping at the match), and the first moments it
// is up take no taps at all (guardMs).
void UIB_BuildJoin()
{
	if (!UIB_Screen("join", _("Join the match")))
		return;
	uib_s("presentation", "dialog");
	uib_b("dismissable", 0);
	uib_f("guardMs", 450);
	float teams = cvar("_teams_available");
	bool teamgame = (cvar("_touch_hud_flags") & 4) || teams;
	float playing = cvar("_touch_match_players");
	if (teamgame)
		uib_s("subtitle", _("Pick a team, or let the game balance them"));
	else
		uib_s("subtitle", (playing > 0) ? sprintf(_("%s in the match"), UIB_JoinPlayers(playing)) : _("Nobody is playing yet"));
	uib_j("primary", UIB_AS("join.auto", (teamgame ? _("Auto-select team") : _("Join")), "play", "primary"));
	if (teams)
	{
		UIB_Open("teams", "grid");
			uib_f("minItem", 140);
			if (teams & 1) UIB_JoinTeam(1, "red", _("Red"), "#e5413b");
			if (teams & 2) UIB_JoinTeam(2, "blue", _("Blue"), "#3a7dff");
			if (teams & 4) UIB_JoinTeam(4, "yellow", _("Yellow"), "#f0c419");
			if (teams & 8) UIB_JoinTeam(8, "pink", _("Pink"), "#e055c8");
		UIB_Close();
	}
	UIB_Button("spectate", _("Spectate"), UIB_A("join.spectate", _("Spectate"), "esp"), "secondary");
	UIB_Done();
	strcpy(uib_join_seen, UIB_JoinState());
}

bool UIB_JoinUp()
{
	return strstrofs(strcat(" ", uibn_modal, " "), " join ", 0) >= 0;
}

void UIB_JoinClose()
{
	if (UIB_TopScreen() == "join")
	{
		UIB_DropLast(true);
		UIB_Nav();
	}
	// back to the match, not to the pause menu under the popup
	if (uibn_context == "game" && uibn_modal == "")
		m_hide();
}

// True when a bridge renderer shows the menu and the popup is now up.
bool UIB_JoinOpen()
{
	// A native renderer shows it as its own dialog. The QuakeC renderer leaves
	// it to the finger-sized stock dialog (dialog_touch_join.qc), which opens
	// over the bridge menu like the game menu does: its popups sit on the
	// full-screen pause page, and a join question belongs over the match.
	if (!UIB_EngineHasBridge() || !uib_native())
		return false;
	if (!uib_inited)
		UIB_Init();
	UIB_Tick();   // the context may still read "menu" or "loading" right after a join
	if (!uib_inited || uibn_context != "game")
		return false;
	uib_join_watching = TouchJoin_Watching();
	if (!UIB_JoinUp())
		UIB_Modal("join");
	else
		UIB_Build("join");
	if (!Menu_Active)
		m_display();
	return true;
}

bool UIB_JoinAction(string a)
{
	if (a == "join.open")
	{
		localcmd("\ntouch_join_dialog\n");
		return true;
	}
	if (substring(a, 0, 5) != "join." || a == "join.address")
		return false;
	localcmd(sprintf("\ntouch_join %s\n", substring(a, 5, -1)));
	UIB_JoinClose();
	return true;
}

// Back (the only way out besides a choice; a tap beside the popup does
// nothing): as Spectate while watching (the player stays where they are),
// and never the pause menu under the popup.
bool UIB_JoinBack()
{
	if (UIB_TopScreen() != "join")
		return false;
	UIB_JoinClose();
	return true;
}

// The game's Welcome dialog (a newer server's game code opens it on connect:
// the server's name and message, Join! and Spectate) is a stock dialog, which a
// native renderer cannot draw. There the join popup asks instead: the engine
// keeps it owed (no touch_join_answered) and opens it once the player is an
// observer with the screen free, as on any other server.
bool UIB_WelcomeNative()
{
	return UIB_EngineHasBridge() && uib_native();
}

// Every tick: the popup goes once the player is in the game, and follows
// the teams' player counts while it is up.
void UIB_JoinTick()
{
	if (!UIB_JoinUp())
		return;
	if (uibn_context != "game" || (uib_join_watching && cvar_string("_touch_hud_mode") == "playing"))
	{
		uib_join_watching = false;
		UIB_JoinClose();
		return;
	}
	if (UIB_JoinState() != uib_join_seen)
		UIB_Build("join");
}

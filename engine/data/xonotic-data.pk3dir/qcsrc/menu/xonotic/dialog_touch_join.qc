#include "dialog_touch_join.qh"

#include "touch_menu_scale.qh"

bool TouchJoin_Watching()
{
	string m = cvar_string("_touch_hud_mode");
	return m == "spectating" || m == "following";
}

void GodnoticTeamButton_draw(entity me)
{
	if (me.disabled)
		return;
	SUPER(GodnoticTeamButton).draw(me);
}

entity makeGodnoticTeamButton(string theText, vector theColor, string theCommand)
{
	entity me = NEW(GodnoticTeamButton);
	me.configureXonoticCommandButton(me, theText, theColor, theCommand, COMMANDBUTTON_CLOSE, string_null);
	return me;
}

void GodnoticJoinDialog_configureDialog(entity me)
{
	// rows two units tall: 60 dp buttons with the touch layer's row height
	if (touch_menu_on)
		me.rowHeight = TouchMenuScale_dpToUnits(30 * TouchMenuScale_size());
	SUPER(GodnoticJoinDialog).configureDialog(me);
}

// "Red · 3": a team and how many play in it.
string GodnoticJoin_TeamLabel(string name, float n)
{
	return sprintf(_("%s · %d"), name, n);
}

void GodnoticJoinDialog_updateCounts(entity me)
{
	string c = strcat(cvar_string("_touch_team_players"), ":", cvar_string("_touch_match_players"));
	if (c == me.countsSeen)
		return;
	strcpy(me.countsSeen, c);
	tokenize_console(cvar_string("_touch_team_players"));
	// Label.setText keeps the pointer: a sprintf result would be gone next frame
	strcpy(me.label1, GodnoticJoin_TeamLabel(_("Red"), stof(argv(0))));
	strcpy(me.label2, GodnoticJoin_TeamLabel(_("Blue"), stof(argv(1))));
	strcpy(me.label3, GodnoticJoin_TeamLabel(_("Yellow"), stof(argv(2))));
	strcpy(me.label4, GodnoticJoin_TeamLabel(_("Pink"), stof(argv(3))));
	me.team1.setText(me.team1, me.label1);
	me.team2.setText(me.team2, me.label2);
	me.team3.setText(me.team3, me.label3);
	me.team4.setText(me.team4, me.label4);
}

// The first moments it is up, a tap goes nowhere: it opened by itself, and
// the finger may have been on its way to the match.
bool GodnoticJoinDialog_mousePress(GodnoticJoinDialog me, vector pos)
{
	if (time < me.armedAt)
		return true;
	return SUPER(GodnoticJoinDialog).mousePress(me, pos);
}

float GodnoticJoinDialog_mouseRelease(entity me, vector pos)
{
	if (time < me.armedAt)
		return 1;
	return SUPER(GodnoticJoinDialog).mouseRelease(me, pos);
}

void GodnoticJoinDialog_showNotify(entity me)
{
	SUPER(GodnoticJoinDialog).showNotify(me);
	me.armedAt = time + 0.45;
	strfree(me.countsSeen);
	GodnoticJoinDialog_updateCounts(me);
	float teams = cvar("_teams_available");
	bool teamgame = (cvar("_touch_hud_flags") & 4) || teams;
	me.team1.disabled = !(teams & 1);
	me.team2.disabled = !(teams & 2);
	me.team3.disabled = !(teams & 4);
	me.team4.disabled = !(teams & 8);
	me.autoButton.setText(me.autoButton, (teamgame ? _("Auto-select team") : _("Join")));
	me.wasWatching = TouchJoin_Watching();
}

// Closes itself once the player is in the game (a choice here, the server's
// own auto-join, another way in): it was up to get them there.
void GodnoticJoinDialog_draw(entity me)
{
	if (me.wasWatching && cvar_string("_touch_hud_mode") == "playing")
	{
		me.wasWatching = false;
		me.close(me);
		return;
	}
	GodnoticJoinDialog_updateCounts(me);
	SUPER(GodnoticJoinDialog).draw(me);
}

void GodnoticJoinDialog_fill(entity me)
{
	entity e;
	me.TR(me);
		me.TD(me, 2, 4, e = me.autoButton = makeGodnoticCommandButton(_("Join"), '0 0 0', "touch_join auto", COMMANDBUTTON_CLOSE));
			e.preferredFocusPriority = 1;
	me.TR(me);
	me.TR(me);
	me.gotoRC(me, 2.5, 0);
		me.TD(me, 2, 1, me.team1 = makeGodnoticTeamButton(_("Red"), '1 0.5 0.5', "touch_join red"));
		me.TD(me, 2, 1, me.team2 = makeGodnoticTeamButton(_("Blue"), '0.5 0.5 1', "touch_join blue"));
		me.TD(me, 2, 1, me.team3 = makeGodnoticTeamButton(_("Yellow"), '1 1 0.5', "touch_join yellow"));
		me.TD(me, 2, 1, me.team4 = makeGodnoticTeamButton(_("Pink"), '1 0.5 1', "touch_join pink"));
	me.gotoRC(me, 5.5, 0);
		me.TD(me, 2, 4, makeGodnoticCommandButton(_("Spectate"), '0 0 0', "touch_join spectate", COMMANDBUTTON_CLOSE));
}

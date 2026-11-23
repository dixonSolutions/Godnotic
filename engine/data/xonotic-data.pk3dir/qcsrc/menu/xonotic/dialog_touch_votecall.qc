// SPDX-License-Identifier: GPL-3.0-or-later
#include "dialog_touch_votecall.qh"

#include "touchbutton.qh"
#include "touch_menu_scale.qh"
#include "textlabel.qh"
#include "inputbox.qh"
#include "util.qh"

// ---------------------------------------------------------------------------
// What a vote takes, from lists the game knows. The engine publishes them from
// the server's own replies (touch_vote.c): _touch_vote_cmds ("command<TAB>kind"
// per line, from "vote help"), _touch_vote_maps (from "lsmaps") and
// _touch_vote_players (the scoreboard). Nothing here asks for typing.
// ---------------------------------------------------------------------------

// What a server allows when it has not answered "vote help" yet: the stock
// sv_vote_commands.
string TouchVoteCall_DefaultCommands()
{
	return "restart\tnone\nfraglimit\tnumber\ngotomap\tmap\nnextmap\tmap\nendmatch\tnone\nreducematchtime\tnone\nextendmatchtime\tnone\n"
		"allready\tnone\nresetmatch\tnone\nkick\tplayer\ncointoss\tnone\nmovetoauto\tplayer\nshuffleteams\tnone\nbalanceteams\tnone\nbots\tnone\nnobots\tnone\n";
}

bool TouchVoteCall_CommandsKnown() { return cvar_string("_touch_vote_cmds") != ""; }

string TouchVoteCall_Commands()
{
	string c = cvar_string("_touch_vote_cmds");
	return (c != "") ? c : TouchVoteCall_DefaultCommands();
}

// none, map, player, number or other: from the list the server gave
string TouchVoteCall_KindOf(string cmd)
{
	string all = TouchVoteCall_Commands();
	int n = tokenizebyseparator(all, "\n");
	for (int i = 0; i < n; ++i)
	{
		string line = argv(i);
		int tab = strstrofs(line, "\t", 0);
		if (tab > 0 && substring(line, 0, tab) == cmd)
			return substring(line, tab + 1, -1);
	}
	return "other";
}

string TouchVoteCall_Label(string cmd)
{
	switch (cmd)
	{
		case "gotomap": case "map": case "chmap": return _("Change map");
		case "nextmap": return _("Set the next map");
		case "restart": return _("Restart match");
		case "resetmatch": return _("Soft restart");
		case "allready": return _("Start now");
		case "endmatch": return _("End match");
		case "extendmatchtime": return _("More time");
		case "reducematchtime": return _("Less time");
		case "timelimit": return _("Time limit");
		case "fraglimit": return _("Frag limit");
		case "kick": return _("Kick a player");
		case "kickban": return _("Kick and ban a player");
		case "movetoauto": return _("Move a player to auto team");
		case "movetored": return _("Move a player to red");
		case "movetoblue": return _("Move a player to blue");
		case "movetoyellow": return _("Move a player to yellow");
		case "movetopink": return _("Move a player to pink");
		case "movetospec": return _("Move a player to spectators");
		case "cointoss": return _("Flip a coin");
		case "shuffleteams": return _("Shuffle teams");
		case "balanceteams": return _("Balance teams");
		case "bots": return _("Add bots");
		case "nobots": return _("Remove bots");
		case "timein": return _("End the timeout");
		case "timeout": return _("Call a timeout");
	}
	return cmd;
}

// Common values to pick from, space separated
string TouchVoteCall_Values(string cmd)
{
	if (cmd == "timelimit")
		return "5 10 15 20 30 45 60";
	if (cmd == "fraglimit")
		return "10 20 30 50 75 100 150";
	return "";
}

bool TouchVoteCall_ValidMap(string map)
{
	if (map == "")
		return false;
	for (int i = 0; i < strlen(map); ++i)
		if (strstrofs("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-.", substring(map, i, 1), 0) < 0)
			return false;
	return true;
}

bool TouchVoteCall_ValidWord(string w, string allowed)
{
	if (w == "")
		return false;
	for (int i = 0; i < strlen(w); ++i)
		if (strstrofs(allowed, substring(w, i, 1), 0) < 0)
			return false;
	return true;
}

// The console line for a vote: "" when the command or its argument is not
// something that is safe to send. Only characters a vote can carry get in, so
// a picked value can never end the line early or add a command.
string TouchVoteCall_Line(string cmd, string arg)
{
	string word = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-";
	if (!TouchVoteCall_ValidWord(cmd, word))
		return "";
	if (arg == "")
		return strcat("cmd vote call ", cmd);
	string kind = TouchVoteCall_KindOf(cmd);
	if (kind == "map" && !TouchVoteCall_ValidMap(arg))
		return "";
	if (kind == "player" && !(substring(arg, 0, 1) == "#" && TouchVoteCall_ValidWord(substring(arg, 1, -1), "0123456789")))
		return "";
	if (kind == "number" && !TouchVoteCall_ValidWord(arg, "0123456789"))
		return "";
	// a command the game cannot describe: plain words only
	if (kind == "other" && !TouchVoteCall_ValidWord(arg, strcat(word, "#. ")))
		return "";
	return strcat("cmd vote call ", cmd, " ", arg);
}

void TouchVoteCall_Send(string cmd, string arg)
{
	string line = TouchVoteCall_Line(cmd, arg);
	if (line == "")
		return;
	localcmd("\n", line, "\n");
}

// Ask the server what it allows; the engine publishes the answer
void TouchVoteCall_AskCommands()
{
	localcmd("\ncmd vote help\n");
}

// ... and which maps it has, once a map is wanted
void TouchVoteCall_AskMaps()
{
	localcmd("\ncmd lsmaps\n");
}

// ---------------------------------------------------------------------------
// The dialog: one list that is first what the server lets players vote on and,
// once a command with an argument is tapped, that argument's own list (maps,
// players, numbers). A tap on a row calls the vote.
// ---------------------------------------------------------------------------
string touch_vote_lines[64];
string touch_vote_vals[12];
string touch_vote_stage;        // "" = the commands; else the command whose argument is being picked
entity touch_vote_list;
entity touch_vote_head;

entity makeGodnoticVoteList()
{
	entity me = NEW(GodnoticVoteList);
	me.configureXonoticListBox(me);
	me.vkey = "?";
	return me;
}

// the rows of the list now showing: label in even slots, value in odd ones
void GodnoticVoteList_fillRows(entity me)
{
	if (me.rowbuf)
		buf_del(me.rowbuf);
	me.rowbuf = buf_create();
	int n = 0;
	if (touch_vote_stage == "")
	{
		int c = tokenizebyseparator(TouchVoteCall_Commands(), "\n");
		int k = 0;
		for (int i = 0; i < c && k < 48; ++i)
			if (argv(i) != "")
				touch_vote_lines[k++] = argv(i);
		for (int i = 0; i < k; ++i)
		{
			int tab = strstrofs(touch_vote_lines[i], "\t", 0);
			if (tab <= 0)
				continue;
			string cmd = substring(touch_vote_lines[i], 0, tab);
			bufstr_set(me.rowbuf, 2 * n, TouchVoteCall_Label(cmd));
			bufstr_set(me.rowbuf, 2 * n + 1, cmd);
			++n;
		}
	}
	else
	{
		string kind = TouchVoteCall_KindOf(touch_vote_stage);
		if (kind == "map")
		{
			string maps = cvar_string("_touch_vote_maps");
			if (maps != "")
			{
				int c = tokenizebyseparator(maps, "\n");
				for (int i = 0; i < c; ++i)
					if (argv(i) != "")
					{
						bufstr_set(me.rowbuf, 2 * n, argv(i));
						bufstr_set(me.rowbuf, 2 * n + 1, argv(i));
						++n;
					}
			}
			else
			{
				// not learned from the server: the maps this device has for the current mode
				float c = MapInfo_count;
				for (int i = 0; i < c; ++i)
				{
					string m = MapInfo_BSPName_ByID(i);
					if (m != "")
					{
						bufstr_set(me.rowbuf, 2 * n, m);
						bufstr_set(me.rowbuf, 2 * n + 1, m);
						++n;
					}
				}
			}
		}
		else if (kind == "player")
		{
			int c = tokenizebyseparator(cvar_string("_touch_vote_players"), "\n");
			int k = 0;
			for (int i = 0; i < c && k < 64; ++i)
				if (argv(i) != "")
					touch_vote_lines[k++] = argv(i);
			for (int i = 0; i < k; ++i)
			{
				tokenizebyseparator(touch_vote_lines[i], "\t");
				string no = argv(0), ps = argv(1), mine = argv(2), name = argv(3);
				if (mine == "m" && (touch_vote_stage == "kick" || touch_vote_stage == "kickban"))
					continue;
				bufstr_set(me.rowbuf, 2 * n, ((ps == "s") ? strcat(name, " (", _("spectator"), ")") : name));
				bufstr_set(me.rowbuf, 2 * n + 1, strcat("#", no));
				++n;
			}
		}
		else if (kind == "number")
		{
			int c = tokenize_console(TouchVoteCall_Values(touch_vote_stage));
			int k = 0;
			for (int i = 0; i < c && k < 12; ++i)
				touch_vote_vals[k++] = argv(i);
			for (int i = 0; i < k; ++i)
			{
				bufstr_set(me.rowbuf, 2 * n, touch_vote_vals[i]);
				bufstr_set(me.rowbuf, 2 * n + 1, touch_vote_vals[i]);
				++n;
			}
			bufstr_set(me.rowbuf, 2 * n, _("No limit"));
			bufstr_set(me.rowbuf, 2 * n + 1, "0");
			++n;
		}
		else
		{
			// a command the game cannot describe: call it as it is
			bufstr_set(me.rowbuf, 2 * n, _("Call this vote"));
			bufstr_set(me.rowbuf, 2 * n + 1, "");
			++n;
		}
	}
	me.nItems = n;
}

void GodnoticVoteList_draw(entity me)
{
	string cur = strcat(touch_vote_stage, "|", cvar_string("_touch_vote_cmds"), "|", ftos(strlen(cvar_string("_touch_vote_maps"))), "|", cvar_string("_touch_vote_players"));
	if (cur != me.vkey)
	{
		strcpy(me.vkey, cur);
		me.fillRows(me);
		if (me.selectedItem >= me.nItems)
			me.setSelected(me, 0);
	}
	SUPER(GodnoticVoteList).draw(me);
}

void GodnoticVoteList_drawListBoxItem(entity me, int i, vector absSize, bool isSelected, bool isFocused)
{
	if (isSelected)
		draw_Fill('0 0 0', '1 1 0', SKINCOLOR_LISTBOX_SELECTED, SKINALPHA_LISTBOX_SELECTED);
	else if (isFocused)
	{
		me.focusedItemAlpha = getFadedAlpha(me.focusedItemAlpha, SKINALPHA_LISTBOX_FOCUSED, SKINFADEALPHA_LISTBOX_FOCUSED);
		draw_Fill('0 0 0', '1 1 0', SKINCOLOR_LISTBOX_FOCUSED, me.focusedItemAlpha);
	}
	string s = draw_TextShortenToWidth(bufstr_get(me.rowbuf, 2 * i), 1 - 2 * me.realFontSize.x, 0, me.realFontSize);
	draw_Text(me.realUpperMargin * eY + me.realFontSize.x * eX, s, me.realFontSize, '1 1 1', SKINALPHA_TEXT, 0);
}

void GodnoticVoteList_resizeNotify(entity me, vector relOrigin, vector relSize, vector absOrigin, vector absSize)
{
	SUPER(GodnoticVoteList).resizeNotify(me, relOrigin, relSize, absOrigin, absSize);
	float itemh = absSize.y * me.itemHeight;
	float itemw = absSize.x * (1 - me.controlWidth);
	me.realFontSize.y = me.fontSize / itemh;
	me.realFontSize.x = me.fontSize / itemw;
	me.realUpperMargin = 0.5 * (1 - me.realFontSize.y);
}

void TouchVoteCall_Heading()
{
	if (!touch_vote_head)
		return;
	if (touch_vote_stage == "")
		touch_vote_head.setText(touch_vote_head, (TouchVoteCall_CommandsKnown() ? _("Tap what to vote on") : _("Asking the server what it allows...")));
	else
		touch_vote_head.setText(touch_vote_head, TouchVoteCall_Label(touch_vote_stage));
}

// A tap: the whole vote is the command and the row's value
void GodnoticVoteList_clickListBoxItem(entity me, float i, vector where)
{
	if (i < 0 || i >= me.nItems)
		return;
	m_play_click_sound(MENU_SOUND_EXECUTE);
	string value = bufstr_get(me.rowbuf, 2 * i + 1);
	if (touch_vote_stage == "")
	{
		string kind = TouchVoteCall_KindOf(value);
		if (kind == "none")
		{
			TouchVoteCall_Send(value, "");
			localcmd("\ntogglemenu 0\n");
			return;
		}
		strcpy(touch_vote_stage, value);
		if (kind == "map" && cvar_string("_touch_vote_maps") == "")
			TouchVoteCall_AskMaps();
		if (kind == "other")
		{
			// nothing to pick: call it as it is
			TouchVoteCall_Send(value, "");
			strcpy(touch_vote_stage, "");
			localcmd("\ntogglemenu 0\n");
			return;
		}
		me.setSelected(me, 0);
		TouchVoteCall_Heading();
		return;
	}
	string cmd = touch_vote_stage;
	strcpy(touch_vote_stage, "");
	TouchVoteCall_Send(cmd, value);
	localcmd("\ntogglemenu 0\n");
}

// Back: from an argument list to the commands, then out of the dialog
void TouchVoteCall_Back(entity button, entity me)
{
	if (touch_vote_stage != "")
	{
		strcpy(touch_vote_stage, "");
		TouchVoteCall_Heading();
		return;
	}
	Dialog_Close(button, me);
}

void GodnoticVoteCallDialog_configureDialog(entity me)
{
	// as the game menu: finger-sized rows on a touchscreen
	if (touch_menu_on)
		me.rowHeight = TouchMenuScale_dpToUnits(30 * TouchMenuScale_size());
	SUPER(GodnoticVoteCallDialog).configureDialog(me);
}

void GodnoticVoteCallDialog_showNotify(entity me)
{
	SUPER(GodnoticVoteCallDialog).showNotify(me);
	strcpy(touch_vote_stage, "");
	TouchVoteCall_AskCommands();
	TouchVoteCall_Heading();
}

void GodnoticVoteCallDialog_fill(entity me)
{
	entity e;
	me.TR(me);
		me.TD(me, 1.5, 2, e = makeGodnoticButton(_("Back"), '0 0 0'));
			e.onClick = TouchVoteCall_Back;
			e.onClickEntity = me;
	me.gotoRC(me, 2, 0);
		me.TD(me, 1, 2, touch_vote_head = makeXonoticTextLabel(0, _("Tap what to vote on")));
	me.gotoRC(me, 3, 0);
		me.TD(me, 8, 2, touch_vote_list = makeGodnoticVoteList());
	me.gotoRC(me, 11.5, 0);
		// what else the server accepts: printed to the console
		me.TD(me, 1.5, 2, e = makeGodnoticCommandButton(_("Show the server's vote help"), '0 0 0', "cmd vote help; togglemenu 0; toggleconsole", 0));
}

void TouchVoteCall_ConfigureDialogs(entity mainwindow)
{
	entity dialog = NEW(GodnoticVoteCallDialog);
	dialog.configureDialog(dialog);
	mainwindow.addItemCentered(mainwindow, dialog, dialog.intendedWidth * eX + dialog.intendedHeight * eY, SKINALPHAS_MAINMENU_z);
}

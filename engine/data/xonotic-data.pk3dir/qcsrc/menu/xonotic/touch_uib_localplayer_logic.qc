// SPDX-License-Identifier: GPL-3.0-or-later
// Godnotic: what a local game uses for the player and the bots (Settings > Player > Local).
//
// Pure functions: values in, values out, no cvars and no files. The part that reads the
// settings and applies them is touch_uib_localplayer.qc. This file is also compiled as C
// by tests/touch-localplayer/localplayer_test.c (scripts/test-localplayer.sh), so it keeps
// to the part of QuakeC that is C as well: no `bool`, no `==` on strings (LP_Same), the
// engine's string builtins only (the test supplies them), locals declared before use.
//
// The settings (all of them local: nothing here is ever sent to a server):
//   you     "online" (the default: your online model, as today) | "model" | "random"
//   youcol  "online" (the default) | "random" (a new scheme each match) | "fixed"
//   bots    "random" (the default: each bot keeps the model bots.txt gives it) | "one" | "set"
//   botcol  "bot" (the default: each bot its own, as today) | "match" (one scheme for the
//           match) | "fixed"
// A model is a key "<model path>:<skin>", e.g. "models/player/erebus.iqm:0"; a set is keys
// separated by commas. A colour is what _cl_color holds: shirt * 16 + pants.

#ifndef LP_TEST
float LP_Same(string a, string b) { return a == b; }
#endif

float LP_SetMax() { return 32; }

float LP_Index(float n, float roll)
{
	float i;
	if (n < 1) return -1;
	i = floor(roll * n);
	if (i >= n) i = n - 1;
	if (i < 0) i = 0;
	return i;
}

string LP_KeyModel(string key)
{
	float k = strstrofs(key, ":", 0);
	if (k < 0) return key;
	return substring(key, 0, k);
}

string LP_KeySkin(string key)
{
	float k = strstrofs(key, ":", 0);
	if (k < 0 || k + 1 >= strlen(key)) return "0";
	return substring(key, k + 1, -1);
}

string LP_Key(string model, string skin)
{
	return strcat(model, ":", skin);
}

// "models/player/erebus.iqm" is "erebus.iqm" in bots.txt
string LP_BotModelName(string key)
{
	string m = LP_KeyModel(key);
	if (strlen(m) > 14 && LP_Same(substring(m, 0, 14), "models/player/"))
		return substring(m, 14, -1);
	return m;
}

// ---------------------------------------------------------------- sets
float LP_SetCount(string set)
{
	if (LP_Same(set, "")) return 0;
	return tokenizebyseparator(set, ",");
}

float LP_SetHas(string set, string key)
{
	string with = strcat(",", set, ",");
	return strstrofs(with, strcat(",", key, ","), 0) >= 0;
}

// in when it was out, out when it was in; a full set takes no more
string LP_SetToggle(string set, string key)
{
	string out = "";
	float n, i;
	string e;
	if (LP_Same(key, "")) return set;
	if (LP_SetHas(set, key))
	{
		n = LP_SetCount(set);
		for (i = 0; i < n; ++i)
		{
			e = argv(i);
			if (!LP_Same(e, key))
				out = LP_Same(out, "") ? e : strcat(out, ",", e);
		}
		return out;
	}
	if (LP_SetCount(set) >= LP_SetMax()) return set;
	return LP_Same(set, "") ? key : strcat(set, ",", key);
}

string LP_SetPick(string set, float roll)
{
	float n = LP_SetCount(set);
	if (n < 1) return "";
	return argv(LP_Index(n, roll));
}

// ---------------------------------------------------------------- you
// randomKey: a model drawn from the installed ones for this match, "" when there is none
string LP_YouModel(string mode, string chosen, string randomKey)
{
	if (LP_Same(mode, "model")) return chosen;
	if (LP_Same(mode, "random")) return randomKey;
	return "";   // "online": nothing to apply, the game uses your online model
}

// -1: keep your online colours
float LP_YouColour(string mode, float fixed, float roll1, float roll2)
{
	if (LP_Same(mode, "fixed")) return bound(0, floor(fixed), 255);
	if (LP_Same(mode, "random")) return LP_Index(15, roll1) * 16 + LP_Index(15, roll2);
	return -1;
}

// ---------------------------------------------------------------- bots
// "" keeps the model bots.txt gives that bot
string LP_BotModel(string mode, string one, string set, float roll)
{
	if (LP_Same(mode, "one")) return one;
	if (LP_Same(mode, "set")) return LP_SetPick(set, roll);
	return "";
}

// the colour a bot line gets: -1 keeps the line's own (a bot's own, or random as today)
float LP_BotColour(string mode, float fixed, float matchColour)
{
	if (LP_Same(mode, "fixed")) return bound(0, floor(fixed), 255);
	if (LP_Same(mode, "match")) return matchColour;
	return -1;
}

float LP_BotsChanged(string botmode, string botcol)
{
	if (LP_Same(botmode, "one") || LP_Same(botmode, "set")) return 1;
	if (LP_Same(botcol, "match") || LP_Same(botcol, "fixed")) return 1;
	return 0;   // both at their defaults: bots.txt stays as it is
}

string lp_col[32];

// One line of bots.txt (name, model, skin, shirt, pants, team, skills ...). Comments and
// blank lines pass through. Columns after the fifth are never touched.
string LP_BotLine(string line, string botmode, string one, string set, float roll,
	string botcol, float fixed, float matchColour)
{
	string model, out;
	int n, i;
	float c, hasmodel;
	if (LP_Same(line, "")) return line;
	if (LP_Same(substring(line, 0, 2), "//") || LP_Same(substring(line, 0, 1), "#")) return line;
	model = LP_BotModel(botmode, one, set, roll);   // before tokenizing the line: it tokenizes too
	hasmodel = !LP_Same(model, "");
	c = LP_BotColour(botcol, fixed, matchColour);
	n = tokenizebyseparator(line, "\t");
	if (n < 5) return line;
	for (i = 0; i < n && i < 32; ++i)
		lp_col[i] = strzone(argv(i));
	if (hasmodel)
	{
		strunzone(lp_col[1]); lp_col[1] = strzone(LP_BotModelName(model));
		strunzone(lp_col[2]); lp_col[2] = strzone(LP_KeySkin(model));
	}
	if (c >= 0)
	{
		strunzone(lp_col[3]); lp_col[3] = strzone(ftos(floor(c / 16)));
		strunzone(lp_col[4]); lp_col[4] = strzone(ftos(c - 16 * floor(c / 16)));
	}
	out = lp_col[0];
	for (i = 1; i < n && i < 32; ++i)
		out = strcat(out, "\t", lp_col[i]);
	for (i = 0; i < n && i < 32; ++i)
		strunzone(lp_col[i]);
	return out;
}

// ---------------------------------------------------------------- when
// A match on this device is one where the client is also the server. 1: it just began, apply
// the local choices; 2: it is over (or the app was killed during one), put your own values
// back; 0: nothing to do. `applied`: whether the choices are in force.
float LP_Step(float wasLocal, float isLocal, float applied)
{
	if (isLocal && !wasLocal) return 1;
	if (!isLocal && applied) return 2;
	return 0;
}

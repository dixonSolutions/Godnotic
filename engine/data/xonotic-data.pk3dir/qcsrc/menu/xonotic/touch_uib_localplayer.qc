// SPDX-License-Identifier: GPL-3.0-or-later
// Godnotic UI bridge: Settings > Player > Local, for games on this device (single player,
// a match against bots, a LAN game this device hosts). What is chosen is decided by
// touch_uib_localplayer_logic.qc (and tested there); this file keeps the settings, draws the
// tab and applies them. Online play is never touched:
//
//   You     the model and colours the player sees on their own character is the client-side
//           cl_forcemyplayermodel/skin/colors, set when a local match begins and put back
//           when it ends. _cl_playermodel, _cl_playerskin and _cl_color (what is sent to
//           servers) are not written here, so nothing local can reach a public server.
//   Bots    a copy of bots.txt (godnotic-bots-local.txt) with the chosen model and colours,
//           named in bot_config_file, which only a server on this device ever reads. With
//           the defaults (random) the copy is not made and bots.txt is read as before.
//
// Defaults (settings untouched): your online character, random models and random colours
// for each bot: exactly the game as it was.

const string UIB_LP_BOTFILE = "godnotic-bots-local.txt";

void UIB_LocalInit()
{
	registercvar("touch_local_you", "online", 32);       // online | model | random
	registercvar("touch_local_model", "", 32);           // "<model>:<skin>" when you = model; "" = the online one
	registercvar("touch_local_youcol", "online", 32);    // online | random | fixed
	registercvar("touch_local_color", "", 32);           // shirt * 16 + pants when youcol = fixed; "" = the online one
	registercvar("touch_local_bots", "random", 32);      // random | one | set
	registercvar("touch_local_bot_model", "", 32);       // the one model, "" = your online one
	registercvar("touch_local_bot_set", "", 32);         // models to pick from, comma separated
	registercvar("touch_local_botcol", "bot", 32);       // bot | match | fixed
	registercvar("touch_local_botcolor", "33", 32);      // shirt * 16 + pants when botcol = fixed
	registercvar("_uib_debug_local", "", 0);            // tests and screenshots: "online" or "local" opens Settings > Player on that tab
	registercvar("touch_local_saved", "", 32);           // your cl_forcemy* values while a local match has changed them
}

string UIB_LPOnlineKey()
{
	return LP_Key(cvar_string("_cl_playermodel"), cvar_string("_cl_playerskin"));
}

string UIB_LPKeyAt(int i)
{
	return LP_Key(uib_pm_model[i], uib_pm_skin[i]);
}

int UIB_LPIndexOf(string key)
{
	for (int i = 0; i < uib_pm_n; ++i)
		if (UIB_LPKeyAt(i) == key)
			return i;
	return -1;
}

float UIB_LPColour(string cv, float fallback)
{
	string v = cvar_string(cv);
	return (v == "") ? fallback : floor(stof(v));
}

// the model the player's own local choice shows: the stored one, else the online one
string UIB_LPYouKey()
{
	string k = cvar_string("touch_local_model");
	return (k == "") ? UIB_LPOnlineKey() : k;
}

string UIB_LPBotKey()
{
	string k = cvar_string("touch_local_bot_model");
	return (k == "") ? UIB_LPOnlineKey() : k;
}

// ---------------------------------------------------------------- the tab
// A model's picture with arrows to flip through all of them: the game's own, the models
// uploaded on this device (the same list as Online), one cycle. id is the node prefix.
void UIB_LPFlip(string id, string key, string who)
{
	if (uib_pm_n < 1)
	{
		UIB_Text(strcat(id, "_none"), _("No player models found: the game data is not complete yet."), "caption");
		return;
	}
	int shown = UIB_LPIndexOf(key);
	if (shown < 0) shown = 0;
	UIB_Open(strcat(id, "_pic"), "image");
		uib_s("ref", strcat("pk3:", uib_pm_model[shown], "_", uib_pm_skin[shown]));
		uib_s("fit", "contain");
		uib_f("aspect", 1);
		uib_s("alt", uib_pm_title[shown]);
	UIB_Close();
	UIB_Open(strcat(id, "_flip"), "row");
		uib_s("align", "center");
		UIB_PMButton(strcat(id, "_prev"), "<", UIB_A(strcat("player.local.", who, ".prev"), _("Previous model"), ""), "tonal", "xs");
		UIB_Text(strcat(id, "_title"), uib_pm_title[shown], "title");
		UIB_PMButton(strcat(id, "_next"), ">", UIB_A(strcat("player.local.", who, ".next"), _("Next model"), ""), "tonal", "xs");
	UIB_Close();
	string kind = uib_pm_up[shown] ? _("Your model") : _("Xonotic model");
	UIB_Text(strcat(id, "_kind"), sprintf(_("%s, %d of %d"), kind, shown + 1, uib_pm_n), "caption");
}

void UIB_LPColourRows(string id, string vmkey, float c)
{
	float shirt = floor(c / 16);
	float pants = c - 16 * shirt;
	UIB_Open(strcat(id, "_shirt"), "colour");
		uib_s("label", _("Shirt"));
		uib_s("bind", strcat("vm:local.", vmkey, ".shirt"));
		uib_s("value", UIB_ColourHex(shirt));
		uib_j("palette", UIB_Palette());
	UIB_Close();
	UIB_Open(strcat(id, "_pants"), "colour");
		uib_s("label", _("Pants"));
		uib_s("bind", strcat("vm:local.", vmkey, ".pants"));
		uib_s("value", UIB_ColourHex(pants));
		uib_j("palette", UIB_Palette());
	UIB_Close();
}

void UIB_LocalPage()
{
	UIB_PMLoad();
	string you = cvar_string("touch_local_you"), youcol = cvar_string("touch_local_youcol");
	string bots = cvar_string("touch_local_bots"), botcol = cvar_string("touch_local_botcol");
	UIB_VMSet("local.you", you);
	UIB_VMSet("local.youcol", youcol);
	UIB_VMSet("local.bots", bots);
	UIB_VMSet("local.botcol", botcol);

	UIB_Text("scope", _("For games on this device: single player, matches against bots and a LAN game you host. Online servers always get the character set under Online."), "caption");

	UIB_Section("lyou", _("You in local games"), true);
	UIB_Choice("you", _("Model"), "vm:local.you", "segmented",
		UIB_Opts3("online", _("Same as online"), "model", _("Choose"), "random", _("Random")), _("Random picks a new model each match"));
	if (you == "model")
	{
		UIB_LPFlip("you", UIB_LPYouKey(), "you");
		UIB_Text("youhint", _("Players who join your LAN game see your online model. This changes how you see yourself."), "caption");
	}
	UIB_Choice("youcol", _("Colours"), "vm:local.youcol", "segmented",
		UIB_Opts3("online", _("Same as online"), "random", _("Random"), "fixed", _("Choose")), _("Random picks new colours each match"));
	if (youcol == "fixed")
		UIB_LPColourRows("you", "you", UIB_LPColour("touch_local_color", cvar("_cl_color")));
	UIB_Close();

	UIB_Section("lbots", _("Bots"), true);
	UIB_Choice("bots", _("Model"), "vm:local.bots", "segmented",
		UIB_Opts3("random", _("Random"), "one", _("One model"), "set", _("From a set")), _("Random gives each bot its usual model. From a set picks one of your chosen models for each bot"));
	if (bots == "one")
		UIB_LPFlip("bot", UIB_LPBotKey(), "bot");
	if (bots == "set")
	{
		string set = cvar_string("touch_local_bot_set");
		int cur = bound(0, UIB_VMf("local.setcur"), max(0, uib_pm_n - 1));
		if (uib_pm_n > 0)
		{
			string key = UIB_LPKeyAt(cur);
			UIB_LPFlip("set", key, "set");
			UIB_Open("setin", "switch");
				uib_s("label", _("In the set"));
				uib_s("bind", "vm:local.setin");
				uib_s("value", LP_SetHas(set, key) ? "1" : "0");
			UIB_Close();
		}
		int n = LP_SetCount(set);
		string cap = (n > 0) ? sprintf(_("%d models in the set: each bot gets one of them at random."), n) : _("The set is empty: bots keep their usual models until you add some.");
		UIB_Text("setcount", cap, "caption");
		UIB_Open("setactions", "row");
			uib_s("align", "center");
			UIB_PMButton("setall", _("All"), UIB_A("player.local.set.all", _("Add every model"), ""), "secondary", "s");
			UIB_PMButton("setnone", _("None"), UIB_A("player.local.set.none", _("Empty the set"), ""), "secondary", "s");
		UIB_Close();
	}
	UIB_Choice("botcol", _("Colours"), "vm:local.botcol", "segmented",
		UIB_Opts3("bot", _("Per bot"), "match", _("Per match"), "fixed", _("Choose")), _("Per bot: each bot has its own random colours. Per match: all bots share one random scheme, new each match"));
	if (botcol == "fixed")
		UIB_LPColourRows("bot", "bot", UIB_LPColour("touch_local_botcolor", 33));
	UIB_Text("botnote", _("Colours follow the team in team games."), "caption");
	UIB_Close();
}

// ---------------------------------------------------------------- changes
void UIB_LPStep(string who, int dir)
{
	UIB_PMLoad();
	if (uib_pm_n < 1) return;
	if (who == "set")
	{
		int c = bound(0, UIB_VMf("local.setcur"), uib_pm_n - 1);
		UIB_VMSet("local.setcur", ftos(mod(c + dir + uib_pm_n, uib_pm_n)));
		return;
	}
	string cv = (who == "you") ? "touch_local_model" : "touch_local_bot_model";
	int i = UIB_LPIndexOf((who == "you") ? UIB_LPYouKey() : UIB_LPBotKey());
	if (i < 0) i = 0;
	cvar_set(cv, UIB_LPKeyAt(mod(i + dir + uib_pm_n, uib_pm_n)));
}

bool UIB_LocalAction(string a)
{
	if (a == "player.local.you.prev") UIB_LPStep("you", -1);
	else if (a == "player.local.you.next") UIB_LPStep("you", 1);
	else if (a == "player.local.bot.prev") UIB_LPStep("bot", -1);
	else if (a == "player.local.bot.next") UIB_LPStep("bot", 1);
	else if (a == "player.local.set.prev") UIB_LPStep("set", -1);
	else if (a == "player.local.set.next") UIB_LPStep("set", 1);
	else if (a == "player.local.set.all")
	{
		UIB_PMLoad();
		string set = "";
		for (int i = 0; i < uib_pm_n && i < LP_SetMax(); ++i)
			set = LP_SetToggle(set, UIB_LPKeyAt(i));
		cvar_set("touch_local_bot_set", set);
	}
	else if (a == "player.local.set.none") cvar_set("touch_local_bot_set", "");
	else return false;
	UIB_RebuildAll();
	return true;
}

// the rows with a vm: bind (so the page rebuilds when they change): UIB_SettingsValue
bool UIB_LocalValue(string bind, string value)
{
	switch (bind)
	{
		case "vm:local.you": if (value == "online" || value == "model" || value == "random") cvar_set("touch_local_you", value); break;
		case "vm:local.youcol": if (value == "online" || value == "random" || value == "fixed") cvar_set("touch_local_youcol", value); break;
		case "vm:local.bots": if (value == "random" || value == "one" || value == "set") cvar_set("touch_local_bots", value); break;
		case "vm:local.botcol": if (value == "bot" || value == "match" || value == "fixed") cvar_set("touch_local_botcol", value); break;
		case "vm:local.setin":
		{
			UIB_PMLoad();
			if (uib_pm_n > 0)
				cvar_set("touch_local_bot_set", LP_SetToggle(cvar_string("touch_local_bot_set"), UIB_LPKeyAt(bound(0, UIB_VMf("local.setcur"), uib_pm_n - 1))));
			break;
		}
		case "vm:local.you.shirt":
		case "vm:local.you.pants":
		case "vm:local.bot.shirt":
		case "vm:local.bot.pants":
		{
			bool you = substring(bind, 9, 3) == "you";
			string cv = you ? "touch_local_color" : "touch_local_botcolor";
			float c = UIB_LPColour(cv, you ? cvar("_cl_color") : 33);
			float shirt = floor(c / 16), pants = c - 16 * floor(c / 16);
			if (substring(bind, strlen(bind) - 5, -1) == "shirt") shirt = UIB_ColourIndex(value);
			else pants = UIB_ColourIndex(value);
			cvar_set(cv, ftos(shirt * 16 + pants));
			break;
		}
		default: return false;
	}
	UIB_RebuildAll();
	return true;
}

// ---------------------------------------------------------------- applying
string uib_lp_sig, uib_lp_yousig;
float uib_lp_next, uib_lp_wasserver;

string UIB_LPBotSig()
{
	return strcat(cvar_string("touch_local_bots"), "|", cvar_string("touch_local_bot_model"), "|", cvar_string("touch_local_bot_set"), "|",
		cvar_string("touch_local_botcol"), "|", cvar_string("touch_local_botcolor"), "|", cvar_string("_cl_playermodel"), cvar_string("_cl_playerskin"));
}

string UIB_LPYouSig()
{
	return strcat(cvar_string("touch_local_you"), "|", cvar_string("touch_local_model"), "|", cvar_string("touch_local_youcol"), "|", cvar_string("touch_local_color"));
}

// A copy of bots.txt for bot_config_file with the chosen models and colours; a new roll
// each time, so each match gets its own. With the defaults nothing is written and
// bots.txt is read as before.
void UIB_LPBotsRegen()
{
	string botmode = cvar_string("touch_local_bots"), botcol = cvar_string("touch_local_botcol");
	if (!LP_BotsChanged(botmode, botcol))
	{
		if (cvar_string("bot_config_file") == UIB_LP_BOTFILE)
			localcmd("\nset bot_config_file bots.txt\n");
		return;
	}
	int fi = fopen("bots.txt", FILE_READ);
	if (fi < 0) return;
	int fo = fopen(UIB_LP_BOTFILE, FILE_WRITE);
	if (fo < 0) { fclose(fi); return; }
	float match = LP_Index(15, random()) * 16 + LP_Index(15, random());
	float fixed = UIB_LPColour("touch_local_botcolor", 33);
	string one = UIB_LPBotKey(), set = cvar_string("touch_local_bot_set"), l;
	while ((l = fgets(fi)))
		fputs(fo, strcat(LP_BotLine(l, botmode, one, set, random(), botcol, fixed, match), "\n"));
	fclose(fi);
	fclose(fo);
	localcmd(strcat("\nset bot_config_file ", UIB_LP_BOTFILE, "\n"));
}

// Put the player's own cl_forcemy* values back (they are what the match changed).
void UIB_LPRestore()
{
	string saved = cvar_string("touch_local_saved");
	if (saved == "") return;
	if (tokenizebyseparator(saved, "|") >= 4)
	{
		cvar_set("cl_forcemyplayermodel", argv(1));
		cvar_set("cl_forcemyplayerskin", argv(2));
		cvar_set("cl_forcemyplayercolors", argv(3));
	}
	cvar_set("touch_local_saved", "");
}

void UIB_LPApply()
{
	string rk = "";
	if (cvar_string("touch_local_you") == "random")
	{
		UIB_PMLoad();
		if (uib_pm_n > 0)
			rk = UIB_LPKeyAt(LP_Index(uib_pm_n, random()));
	}
	string key = LP_YouModel(cvar_string("touch_local_you"), UIB_LPYouKey(), rk);
	float c = LP_YouColour(cvar_string("touch_local_youcol"), UIB_LPColour("touch_local_color", cvar("_cl_color")), random(), random());
	if (c == 0) c = 1;   // cl_forcemyplayercolors 0 means "off"
	if (key == "" && c < 0) return;
	if (cvar_string("touch_local_saved") == "")
		cvar_set("touch_local_saved", sprintf("x|%s|%s|%s", cvar_string("cl_forcemyplayermodel"), cvar_string("cl_forcemyplayerskin"), cvar_string("cl_forcemyplayercolors")));
	if (key != "")
	{
		cvar_set("cl_forcemyplayermodel", LP_KeyModel(key));
		cvar_set("cl_forcemyplayerskin", LP_KeySkin(key));
	}
	if (c > 0)
		cvar_set("cl_forcemyplayercolors", ftos(c));
}

// every 0.25 s from UIB_Tick
void UIB_LocalTick()
{
	if (uib_gettime(1) < uib_lp_next)
		return;
	uib_lp_next = uib_gettime(1) + 0.25;
	string dbg = cvar_string("_uib_debug_local");
	if (dbg != "")
	{
		cvar_set("_uib_debug_local", "");
		UIB_VMSet("settings.section", "player");
		UIB_VMSet("settings.player.tab", dbg);
		cvar_set("_uib_debug_open", "settings");
	}
	float islocal = isserver();
	float fell = uib_lp_wasserver && !islocal;
	float step = LP_Step(uib_lp_wasserver, islocal, cvar_string("touch_local_saved") != "");
	uib_lp_wasserver = islocal;
	if (step == 2)
		UIB_LPRestore();
	if (step == 1)
	{
		UIB_LPApply();
		strcpy(uib_lp_yousig, UIB_LPYouSig());
	}
	else if (islocal && uib_lp_yousig != UIB_LPYouSig())
	{
		// changed in the settings during a islocal match: show it now
		UIB_LPRestore();
		UIB_LPApply();
		strcpy(uib_lp_yousig, UIB_LPYouSig());
	}
	// the bots' file is made while no islocal match runs, so the next match reads a fresh one
	if (!islocal && (fell || uib_lp_sig != UIB_LPBotSig()))
	{
		strcpy(uib_lp_sig, UIB_LPBotSig());
		UIB_LPBotsRegen();
	}
}

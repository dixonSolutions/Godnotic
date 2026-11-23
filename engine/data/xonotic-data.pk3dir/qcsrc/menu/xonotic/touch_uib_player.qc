// SPDX-License-Identifier: GPL-3.0-or-later
// Godnotic UI bridge: Settings > Player on one page (docs/SETTINGS_MAP.md, "Player").
//
//   Name        the field. It shows the name in its colours and carries `keypad`: the
//               characters, colour codes and faces a native renderer offers in its own
//               key panel (UIB_PMKeypad); the panel inserts at the caret like any typing.
//               The name is sent to the server a moment after the last key, never mid-word
//   Character   the model's picture with arrows to flip through every model: the game's
//               stock ones and the player's own uploads, in one cycle; its name under it
//   Colours     shirt and pants (_cl_color), the colours the character wears in a match
//   Upload      the system file picker; a model that passes the engine's check
//               (touch_playermodel.c) becomes the character at once, one that does not
//               says why and changes nothing
//
// The picture is the model's own preview image (models/player/<name>.<fmt>_<skin>.jpg), which the
// engine resizes and caches like any bridge image: no 3D view, so nothing loads and
// nothing is drawn per frame, on Android and on Linux alike. An uploaded model has none
// and shows the placeholder; the shirt and pants colours are shown beside the picture.
// Uploaded models live in <userdir>/godnotic-pm-*.pk3 and are deleted only by the model's
// own delete button.

const int UIB_PM_MAX = 96;
void UIB_PMKeypad();   // below

const float UIB_PM_NAME_DELAY = 0.8;
string uib_name_pending;
float uib_name_due = 0;   // real time the pending name is sent; 0: nothing pending


string uib_pm_model[UIB_PM_MAX];
string uib_pm_skin[UIB_PM_MAX];
string uib_pm_title[UIB_PM_MAX];
bool uib_pm_up[UIB_PM_MAX];
int uib_pm_n = -1;   // -1: not read yet (after an upload or a delete: read again)

string UIB_PMNameHex(int i)
{
	switch (i)
	{
		case 0: return "#000000"; case 1: return "#ff0000"; case 2: return "#00ff00"; case 3: return "#ffff00";
		case 4: return "#0000ff"; case 5: return "#00ffff"; case 6: return "#ff00ff"; case 7: return "#ffffff";
		case 8: return "#999999"; case 9: return "#808080";
	}
	return "#ffffff";
}

void UIB_PMLoad()
{
	if (uib_pm_n >= 0) return;
	for (int i = 0; i < UIB_PM_MAX; ++i)
		if (uib_pm_model[i] != "")
		{
			strunzone(uib_pm_model[i]); strunzone(uib_pm_skin[i]); strunzone(uib_pm_title[i]);
			uib_pm_model[i] = ""; uib_pm_skin[i] = ""; uib_pm_title[i] = "";
		}
	uib_pm_n = 0;
	// the models the player uploaded, one "\n<name>\n" each
	string reg = "";
	if (cvar_string("uib_pm") != "")
	{
		int fh = fopen("godnotic-playermodels.txt", FILE_READ);
		if (fh >= 0)
		{
			string l;
			while ((l = fgets(fh)))
				if (tokenizebyseparator(l, "\t") >= 3)
					reg = strcat(reg, "\n", argv(1), "\n");
			fclose(fh);
		}
	}
	float glob = search_begin(get_model_datafilename(string_null, -1, "txt"), true, true);
	if (glob < 0) return;
	float sortbuf = buf_create();
	for (int i = 0; i < search_getsize(glob); ++i)
	{
		string fn = search_getfilename(glob, i);
		if (!get_model_parameters(fn, -1)) continue;
		if (get_model_parameters_hidden) continue;
		bufstr_add(sortbuf, sprintf("%-128s%s", get_model_parameters_name, fn), 1);
	}
	search_end(glob);
	buf_sort(sortbuf, 128, 0);
	int n = buf_getsize(sortbuf);
	for (int i = 0; i < n && uib_pm_n < UIB_PM_MAX; ++i)
	{
		string fn = substring(bufstr_get(sortbuf, i), 128, -1);
		if (!get_model_parameters(fn, -1)) continue;
		int k = uib_pm_n++;
		uib_pm_model[k] = strzone(get_model_parameters_modelname);
		uib_pm_skin[k] = strzone(ftos(get_model_parameters_modelskin));
		uib_pm_title[k] = strzone(get_model_parameters_name);
		uib_pm_up[k] = strstrofs(reg, strcat("\n", get_model_parameters_modelname, "\n"), 0) >= 0;
	}
	buf_del(sortbuf);
	get_model_parameters(string_null, 0);
}

// the list position of the model in _cl_playermodel/_cl_playerskin, or -1
int UIB_PMCurrent()
{
	string m = cvar_string("_cl_playermodel"), s = cvar_string("_cl_playerskin");
	for (int i = 0; i < uib_pm_n; ++i)
		if (uib_pm_model[i] == m && uib_pm_skin[i] == s)
			return i;
	return -1;
}

void UIB_PMSelect(int i)
{
	cvar_set("_cl_playermodel", uib_pm_model[i]);
	cvar_set("_cl_playerskin", uib_pm_skin[i]);
	localcmd("\nplayermodel $_cl_playermodel; playerskin $_cl_playerskin\nsaveconfig\n");
}

void UIB_PMButton(string key, string label, string actionjson, string style, string width)
{
	uib_node(key, "button");
	uib_s("label", label);
	uib_j("action", actionjson);
	if (style != "") uib_s("style", style);
	if (width != "") uib_s("width", width);
	uib_end();
}

void UIB_PlayerPage()
{
	UIB_PMLoad();
	int cur = UIB_PMCurrent();
	string nm = (uib_name_due > 0) ? uib_name_pending : cvar_string("_cl_name");   // what the player has typed, sent in a moment

	UIB_Section("who", _("Name"), true);
	UIB_Open("name", "field");
		uib_s("label", _("Name"));
		uib_s("bind", "vm:player.name");
		uib_j("value", uib_qv(nm));   // verbatim: the field shows and edits the codes and glyphs themselves
		uib_s("kind", "name");
		uib_f("maxLength", UIB_PM_NAME_MAX);
		UIB_PMKeypad();   // pushes the key groups onto this field's `keypad`
	UIB_Close();
	UIB_Close();

	UIB_Section("character", _("Character"), true);
	if (uib_pm_n > 0)
	{
		int shown = (cur >= 0) ? cur : 0;
		UIB_Open("portrait", "image");
			uib_s("ref", strcat("pk3:", uib_pm_model[shown], "_", uib_pm_skin[shown]));   // models/player/erebus.iqm_0.jpg
			uib_s("fit", "contain");
			uib_f("aspect", 1);
			uib_s("alt", uib_pm_title[shown]);
			// a native renderer offers the picture fullscreen with the model itself to turn and zoom
			// (ModelViewer.kt); `model` is the file in the game's filesystem, `skin` its skin number
			uib_s("viewer", "model");
			uib_s("model", uib_pm_model[shown]);
			uib_f("skin", stof(uib_pm_skin[shown]));
		UIB_Close();
		UIB_Open("flip", "row");
			uib_s("align", "center");
			UIB_PMButton("prev", "<", UIB_A("player.model.prev", _("Previous model"), ""), "tonal", "xs");
			UIB_Text("title", uib_pm_title[shown], "title");
			UIB_PMButton("next", ">", UIB_A("player.model.next", _("Next model"), ""), "tonal", "xs");
		UIB_Close();
		string kind = uib_pm_up[shown] ? _("Your model") : _("Xonotic model");
		UIB_Text("kind", sprintf(_("%s, %d of %d"), kind, shown + 1, uib_pm_n), "caption");
	}
	else
		UIB_Text("none", _("No player models found: the game data is not complete yet."), "caption");
	// name, colours and character reach the server as they change (the engine sends them: cl_cmd.c, CL_SetInfo)
	UIB_Text("live", _("Name, colours and character change at once, in a match too. A server can keep its own character or limit renames."), "caption");
	// upload and delete sit right at the model
	if (cvar_string("uib_pm") != "")
	{
		UIB_Open("modelactions", "row");
			uib_s("align", "center");
			UIB_PMButton("pick", _("Upload a model"), "{\"id\":\"player.upload\",\"label\":\"Choose a model file or .pk3\",\"icon\":\"upload\",\"kind\":\"file\",\"accept\":[\"pk3\",\"zip\"]}", "secondary", "s");
			if (uib_pm_n > 0 && uib_pm_up[(cur >= 0) ? cur : 0])
				UIB_PMButton("delete", _("Delete"),
					sprintf("{\"id\":\"player.model.delete\",\"label\":%s,\"icon\":\"delete\",\"confirm\":{\"title\":%s,\"body\":%s,\"ok\":%s,\"destructive\":true}}",
						uib_q(_("Delete this model")), uib_q(_("Delete this model?")), uib_q(_("The file is removed from this device. You can upload it again.")), uib_q(_("Delete"))),
					"destructive", "s");
		UIB_Close();
		string msg = UIB_VM("player.upload.msg");
		if (msg != "")
			UIB_Text("result", msg, (UIB_VM("player.upload.ok") == "1") ? "caption" : "warning");
		UIB_Text("hint", _("Upload a .pk3 or .zip with the model (iqm, dpm, md3 or zym), its skin and animations; it becomes your character when it fits. Other players see it only if they have the same model."), "caption");
	}
	UIB_Close();

	UIB_Section("colours", _("Colours"), true);
	float c = cvar("_cl_color");
	UIB_Open("shirt", "colour");
		uib_s("label", _("Shirt"));
		uib_s("bind", "vm:player.shirt");
		uib_s("value", UIB_ColourHex(floor(c / 16)));
		uib_j("palette", UIB_Palette());
	UIB_Close();
	UIB_Open("pants", "colour");
		uib_s("label", _("Pants"));
		uib_s("bind", "vm:player.pants");
		uib_s("value", UIB_ColourHex(c - 16 * floor(c / 16)));
		uib_j("palette", UIB_Palette());
	UIB_Close();
	UIB_Close();
}

string UIB_PMPayloadPath(string payload)
{
	int k = strstrofs(payload, "\"path\":\"", 0);
	if (k < 0) return "";
	string path = substring(payload, k + 8, -1);
	return substring(path, 0, strstrofs(path, "\"", 0));
}

bool UIB_PlayerAction(string a, string payload)
{
	if (a == "player.model.prev" || a == "player.model.next")
	{
		UIB_PMLoad();
		if (uib_pm_n < 1) return true;
		int cur = UIB_PMCurrent();
		int i = (cur < 0) ? 0 : mod(cur + ((a == "player.model.next") ? 1 : uib_pm_n - 1), uib_pm_n);
		UIB_PMSelect(i);
		UIB_RebuildAll();
		return true;
	}
	if (a == "player.model.delete")
	{
		UIB_PMLoad();
		int cur = UIB_PMCurrent();
		if (cur >= 0 && uib_pm_up[cur] && uib_pm_delete(uib_pm_model[cur]))
		{
			uib_pm_n = -1;
			UIB_PMLoad();
			if (uib_pm_n > 0) UIB_PMSelect(0);
			UIB_VMSet("player.upload.msg", "");
			UIB_RebuildAll();
		}
		return true;
	}
	if (a == "player.upload")
	{
		string path = UIB_PMPayloadPath(payload);
		if (path == "") return true;
		if (uib_pm_import(path))
		{
			tokenizebyseparator(uib_pm_report(), "\t");
			string m = argv(1), s = argv(2), t = argv(3);
			uib_pm_n = -1;
			UIB_PMLoad();
			cvar_set("_cl_playermodel", m);
			cvar_set("_cl_playerskin", s);
			localcmd("\nplayermodel $_cl_playermodel; playerskin $_cl_playerskin\nsaveconfig\n");
			UIB_VMSet("player.upload.ok", "1");
			UIB_VMSet("player.upload.msg", sprintf(_("%s fits and is now your character."), t));
		}
		else
		{
			tokenizebyseparator(uib_pm_report(), "\t");
			UIB_VMSet("player.upload.ok", "0");
			UIB_VMSet("player.upload.msg", sprintf(_("Not used: %s"), argv(1)));
		}
		UIB_RebuildAll();
		return true;
	}
	return false;
}

// ---- the name: typed live, sent once the typing pauses -------------------------------------
//
// DarkPlaces sends `name` to the server the moment _cl_name changes (cvar.c, CL_SetInfo), so the
// field's every key would be a rename that the server announces to the whole match. The field's
// text is held here and becomes the player's name UIB_PM_NAME_DELAY seconds after the last key
// (UIB_PlayerTick), so the new name is on the server within a second and a half-typed one is not.

// the name as the player typed or picked it, checked and made the player's name
void UIB_PMCommitName()
{
	string nm = uib_name_pending;
	uib_name_due = 0;
	if (nm == cvar_string("_cl_name"))
		return;
	if (strdecolorize(nm) == "")
		return;   // a name needs a letter: the old one stays (and the field shows it again on the next visit)
	cvar_set("_cl_name", nm);   // connected: the engine sends it to the server now
	localcmd("\nname \"$_cl_name\"\nsaveconfig\n");
}

// Settings > Player, the Name field: a key typed, a keypad key tapped or a suggestion taken
void UIB_PlayerName(string value)
{
	if (strlen(value) > UIB_PM_NAME_MAX)
		value = substring(value, 0, UIB_PM_NAME_MAX);   // the name stays within the limit
	strcpy(uib_name_pending, value);
	uib_name_due = uib_gettime(1) + UIB_PM_NAME_DELAY;
	UIB_VMSet("player.name", value);
}

void UIB_PlayerTick()
{
	if (uib_name_due > 0 && uib_gettime(1) >= uib_name_due)
		UIB_PMCommitName();
}

// ---- the keypad ------------------------------------------------------------------------------
//
// The same characters Xonotic's own character map offers (charmap.qc: CHARMAP), so what the keypad
// types is what the game font draws; the colour keys are the ^0..^9 codes and six ^xRGB ones.
// Faces are the emoticons (U+1F600..U+1F64F and the heart), the game font's own glyphs are
// U+E000..U+E0FF (the Quake charset page; DarkPlaces draws them from its bitmap font) and every
// other CHARMAP entry is a symbol. Keys are verbatim (uib_qv): `^` and the private glyphs must survive.

string UIB_PMKeyJ(string text, string colour)
{
	if (colour != "")
		return strcat("{\"text\":", uib_qv(text), ",\"colour\":\"", colour, "\"}");
	return strcat("{\"text\":", uib_qv(text), "}");
}

// A QuakeC temporary string holds 4095 bytes, which the whole keypad passes, so each group is its
// own string and is pushed on its own (uib_push appends to the node's array prop).
void UIB_PMKeypad()
{
	string cols = "";
	for (int i = 0; i < 10; ++i)
		cols = strcat(cols, (i ? "," : ""), UIB_PMKeyJ(strcat("^", ftos(i)), UIB_PMNameHex(i)));
	cols = strcat(cols, ",", UIB_PMKeyJ("^xf80", "#ff8800"), ",", UIB_PMKeyJ("^x08f", "#0088ff"), ",", UIB_PMKeyJ("^x0f8", "#00ff88"));
	cols = strcat(cols, ",", UIB_PMKeyJ("^x80f", "#8800ff"), ",", UIB_PMKeyJ("^xf08", "#ff0088"), ",", UIB_PMKeyJ("^x8f0", "#88ff00"));
	string faces = "", syms = "", font = "";
	int n = strlen(CHARMAP);
	for (int i = 0; i < n; ++i)
	{
		float cp = str2chr(CHARMAP, i);
		if (cp <= 0x20)
			continue;
		string key = UIB_PMKeyJ(substring(CHARMAP, i, 1), "");
		if ((cp >= 0x1F600 && cp <= 0x1F64F) || cp == 0x2764)
			faces = strcat(faces, (faces != "" ? "," : ""), key);
		else if (cp >= 0xE000 && cp <= 0xE0FF)
			font = strcat(font, (font != "" ? "," : ""), key);
		else
			syms = strcat(syms, (syms != "" ? "," : ""), key);
	}
	uib_push("keypad", strcat("{\"id\":\"colours\",\"label\":", uib_q(_("Colours")), ",\"kind\":\"colour\",\"keys\":[", cols, "]}"));
	uib_push("keypad", strcat("{\"id\":\"faces\",\"label\":", uib_q(_("Faces")), ",\"kind\":\"glyph\",\"keys\":[", faces, "]}"));
	// the symbols and then the game font's own glyphs: two pushes would make two groups, so the font keys are appended to the symbols
	uib_push("keypad", strcat("{\"id\":\"symbols\",\"label\":", uib_q(_("Symbols")), ",\"kind\":\"glyph\",\"keys\":[", syms, (font != "" ? strcat(",", font) : ""), "]}"));
}

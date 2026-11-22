/*
 * touch_mention.c -- chat @mentions and the live "is typing" line (Godnotic).
 *
 * Typing: an unmodified Xonotic server shows a chat bubble over a player who
 * has the chat button down (usercmd button 9, which the stock client sets
 * whenever key_dest is not the game: see cl_input.c). The bubble is a plain
 * networked sprite entity (models/misc/chatbubble.spr) attached to the player.
 * Nothing else is sent, so this is all the protocol carries: we read those
 * entities. Limits: only players the server sends us (in view range), only
 * players (spectators have no bubble), and the server's ShowChatBubble hook
 * can hide it. Demos show it as recorded.
 *
 * Mentions: the picker reads the scoreboard names the server already sends
 * (cl.scores), edits only the local chat line, and the server gets the
 * finished text through the usual say command.
 *
 * Roster and typing text are also published as _touch_roster / _touch_typing
 * so the QuakeC chat sheet can use the same data.
 */
#include "quakedef.h"
#include "touch_mention.h"
#include "touch_mention_logic.h"

static cvar_t touch_chat_mentions = {CF_CLIENT | CF_ARCHIVE, "touch_chat_mentions", "1",
	"1 opens a picker of the players and spectators when @ is typed in chat"};
static cvar_t touch_chat_typing = {CF_CLIENT | CF_ARCHIVE, "touch_chat_typing", "1",
	"1 shows who is typing (the server's chat bubble over their head) in the chat area"};
static cvar_t touch_roster = {CF_CLIENT, "_touch_roster", "",
	"player names the server sent, one per line, spectators marked with a leading tab (read by the chat sheet)"};
static cvar_t touch_typing = {CF_CLIENT, "_touch_typing", "",
	"the \"is typing\" text the chat area shows (read by the chat sheet)"};

#define TM_ROSTER_MAX 64
typedef struct { char name[TM_MAX_NAME]; qbool spectator; } tm_entry_t;
static tm_entry_t tm_roster[TM_ROSTER_MAX];
static int tm_roster_n;

static int tm_sel;        // highlighted row
static int tm_dismissed = -1; // the @ position the player closed the picker on
static struct { int n, idx[TM_MAX_SHOWN]; int at; float x, y, w, rowh; } tm_pick;

static void TM_SetStr(cvar_t *c, const char *s)
{
	if (strcmp(c->string, s))
		Cvar_SetQuick(c, s);
}

static void TM_BuildRoster(void)
{
	int i;
	tm_roster_n = 0;
	if (cls.state != ca_connected || !cl.scores)
		return;
	for (i = 0; i < cl.maxclients && tm_roster_n < TM_ROSTER_MAX; i++)
	{
		tm_entry_t *e = &tm_roster[tm_roster_n];
		if (!cl.scores[i].name[0] || i == cl.realplayerentity - 1)
			continue;
		TouchMention_StripColors(cl.scores[i].name, e->name, sizeof(e->name));
		if (!e->name[0])
			continue;
		e->spectator = cl.scores[i].frags == -666 /* FRAGS_SPECTATOR */;
		tm_roster_n++;
	}
}

static void TM_PublishRoster(void)
{
	char buf[1024];
	size_t o = 0;
	int i;
	for (i = 0; i < tm_roster_n; i++)
	{
		size_t l = strlen(tm_roster[i].name);
		if (o + l + 3 >= sizeof(buf))
			break;
		if (tm_roster[i].spectator)
			buf[o++] = '\t';
		memcpy(buf + o, tm_roster[i].name, l);
		o += l;
		buf[o++] = '\n';
	}
	buf[o] = 0;
	TM_SetStr(&touch_roster, buf);
}

/* ---- typing ---- */

static int tm_bubble_model;

static int TM_BubbleModel(void)
{
	int i;
	if (tm_bubble_model > 0 && tm_bubble_model < MAX_MODELS
		&& !strcmp(cl.model_name[tm_bubble_model], "models/misc/chatbubble.spr"))
		return tm_bubble_model;
	tm_bubble_model = 0;
	for (i = 1; i < MAX_MODELS && cl.model_name[i][0]; i++)
		if (!strcmp(cl.model_name[i], "models/misc/chatbubble.spr"))
			return tm_bubble_model = i;
	return 0;
}

static void TM_BuildTyping(char *out, size_t size)
{
	const char *names[TM_ROSTER_MAX];
	char store[TM_ROSTER_MAX][TM_MAX_NAME];
	int count = 0, i, model, n = min(cl.num_entities, MAX_EDICTS);
	out[0] = 0;
	if (cls.state != ca_connected || !cl.scores || !touch_chat_typing.integer || !(model = TM_BubbleModel()))
		return;
	for (i = 1; i < n && count < TM_ROSTER_MAX; i++)
	{
		const entity_state_t *s = &cl.entities[i].state_current;
		int slot;
		if (!s->active || s->modelindex != model || !s->tagentity)
			continue;
		slot = s->tagentity - 1;
		if (slot < 0 || slot >= cl.maxclients || !cl.scores[slot].name[0])
			continue;
		TouchMention_StripColors(cl.scores[slot].name, store[count], TM_MAX_NAME);
		names[count] = store[count];
		count++;
	}
	TouchMention_TypingText(names, count, out, size);
}

/* ---- picker ---- */

static void TM_Matches(void)
{
	const char *names[TM_ROSTER_MAX];
	int i, at, qlen;
	tm_pick.n = 0;
	if (key_dest != key_message || chat_mode < 0 || !touch_chat_mentions.integer)
		return;
	at = TouchMention_Token(chat_buffer, chat_bufferpos);
	if (at < 0 || at == tm_dismissed)
		return;
	qlen = chat_bufferpos - at - 1;
	for (i = 0; i < tm_roster_n; i++)
		names[i] = tm_roster[i].name;
	tm_pick.n = TouchMention_Filter(names, tm_roster_n, chat_buffer + at + 1, (size_t)qlen, tm_pick.idx, TM_MAX_SHOWN);
	tm_pick.at = at;
	if (tm_sel >= tm_pick.n)
		tm_sel = 0;
}

static void TM_Accept(int row)
{
	const tm_entry_t *e;
	int c;
	if (row < 0 || row >= tm_pick.n)
		return;
	e = &tm_roster[tm_pick.idx[row]];
	c = TouchMention_Insert(chat_buffer, sizeof(chat_buffer), tm_pick.at, chat_bufferpos, e->name);
	if (c >= 0)
		chat_bufferpos = c;
	tm_pick.n = 0;
	tm_sel = 0;
}

qbool TouchMention_Key(int key, int ascii)
{
	(void)ascii;
	if (TouchChatLog_Key(key, true))
		return true;
	TM_BuildRoster();
	TM_Matches();
	if (key != K_ESCAPE && key != K_ENTER && key != K_KP_ENTER && key != K_TAB
		&& key != K_UPARROW && key != K_DOWNARROW && key != K_MOUSE1)
		return false;
	if (!tm_pick.n)
	{
		if (key != K_ESCAPE)
			tm_dismissed = -1;
		return false;
	}
	switch (key)
	{
	case K_UPARROW:   tm_sel = (tm_sel + tm_pick.n - 1) % tm_pick.n; return true;
	case K_DOWNARROW: tm_sel = (tm_sel + 1) % tm_pick.n; return true;
	case K_TAB: case K_ENTER: case K_KP_ENTER: TM_Accept(tm_sel); return true;
	case K_ESCAPE: tm_dismissed = tm_pick.at; tm_pick.n = 0; return true; /* the next Esc closes the chat */
	case K_MOUSE1:
	{
		float mx = in_windowmouse_x * vid_conwidth.value / vid.mode.width;
		float my = in_windowmouse_y * vid_conheight.value / vid.mode.height;
		int row = (int)((my - tm_pick.y) / tm_pick.rowh);
		if (mx < tm_pick.x || mx > tm_pick.x + tm_pick.w || my < tm_pick.y || row < 0 || row >= tm_pick.n)
			return false;
		TM_Accept(row);
		return true;
	}
	}
	return false;
}

void TouchMention_Init(void)
{
	Cvar_RegisterVariable(&touch_chat_mentions);
	Cvar_RegisterVariable(&touch_chat_typing);
	Cvar_RegisterVariable(&touch_roster);
	Cvar_RegisterVariable(&touch_typing);
}

void TouchMention_Draw(float x, float v, float size)
{
	char typing[160];
	float y = v;
	int i;
	if (cls.state != ca_connected || cls.demoplayback)
		return;
	TM_BuildRoster();
	TM_PublishRoster();
	TM_BuildTyping(typing, sizeof(typing));
	TM_SetStr(&touch_typing, typing);

	if (key_dest == key_message)
		y += size; /* the input line sits at v */
	if (typing[0])
	{
		DrawQ_String(x, y, typing, 0, size, size, 0.75f, 0.85f, 1.0f, 0.9f, 0, NULL, true, FONT_CHAT);
		y += size;
	}
	TM_Matches();
	if (!tm_pick.n)
		return;
	{
		float rowh = size * 1.4f, w = size * 18.0f, h = rowh * tm_pick.n;
		if (y + h > vid_conheight.value)
			y = max(0.0f, v - h); /* no room below: above the input line */
		tm_pick.x = x; tm_pick.y = y; tm_pick.w = w; tm_pick.rowh = rowh;
		DrawQ_Fill(x, y, w, h, 0.02f, 0.04f, 0.08f, 0.85f, 0);
		for (i = 0; i < tm_pick.n; i++)
		{
			const tm_entry_t *e = &tm_roster[tm_pick.idx[i]];
			char label[TM_MAX_NAME + 16];
			if (i == tm_sel)
				DrawQ_Fill(x, y + i * rowh, w, rowh, 0.2f, 0.45f, 0.9f, 0.7f, 0);
			dpsnprintf(label, sizeof(label), "@%s%s", e->name, e->spectator ? "  (spectator)" : "");
			DrawQ_String(x + size * 0.4f, y + i * rowh + (rowh - size) * 0.5f, label, 0, size, size,
				1, 1, 1, 1, 0, NULL, true, FONT_CHAT);
		}
	}
}

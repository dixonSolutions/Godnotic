// Chat @mention matching and the typing line (engine/darkplaces/touch_mention_logic.h).
#include <stdio.h>
#include "touch_mention_logic.h"

static int failures;
#define CHECK(cond, ...) do { if (!(cond)) { failures++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static void test_strip(void)
{
	char o[64];
	TouchMention_StripColors("^1Red^x0f0Green^7 ^^caret^", o, sizeof(o));
	CHECK(!strcmp(o, "RedGreen ^caret^"), "strip: '%s'", o);
	TouchMention_StripColors("^xzzz plain ^", o, sizeof(o));
	CHECK(!strcmp(o, "^xzzz plain ^"), "bad ^x kept: '%s'", o);
	TouchMention_StripColors("abcdef", o, 4);
	CHECK(!strcmp(o, "abc"), "truncates: '%s'", o);
}

static void test_token(void)
{
	CHECK(TouchMention_Token("@bo", 3) == 0, "at start");
	CHECK(TouchMention_Token("hi @bo", 6) == 3, "after space");
	CHECK(TouchMention_Token("mail a@b", 8) == -1, "address is not a mention");
	CHECK(TouchMention_Token("hello", 5) == -1, "no @");
	CHECK(TouchMention_Token("hi @bob how", 7) == 3, "cursor inside the line");
	CHECK(TouchMention_Token("@", 1) == 0, "bare @");
	CHECK(TouchMention_Token("", 0) == -1, "empty");
}

static void test_filter(void)
{
	const char *names[] = { "Bobby", "alice", "xBOBx", "Zed", "" };
	int out[6], n;
	n = TouchMention_Filter(names, 5, "bo", 2, out, 6);
	CHECK(n == 2 && out[0] == 0 && out[1] == 2, "prefix first then inside: n=%d", n);
	n = TouchMention_Filter(names, 5, "", 0, out, 6);
	CHECK(n == 4, "empty query lists everyone but the blank: %d", n);
	n = TouchMention_Filter(names, 5, "", 0, out, 2);
	CHECK(n == 2, "limit: %d", n);
	n = TouchMention_Filter(names, 5, "nope", 4, out, 6);
	CHECK(n == 0, "no match");
	n = TouchMention_Filter(names, 5, "ALI", 3, out, 6);
	CHECK(n == 1 && out[0] == 1, "case-insensitive");
}

static void test_insert(void)
{
	char t[32];
	int c;
	strcpy(t, "hi @bo there");
	c = TouchMention_Insert(t, sizeof(t), 3, 6, "Bobby");
	CHECK(c == 3 + 1 + 5 + 1 && !strcmp(t, "hi @Bobby there"), "mid-line: '%s' %d", t, c);
	strcpy(t, "@a");
	c = TouchMention_Insert(t, sizeof(t), 0, 2, "alice");
	CHECK(c == 7 && !strcmp(t, "@alice "), "at end: '%s'", t);
	strcpy(t, "@a");
	c = TouchMention_Insert(t, 5, 0, 2, "alice");
	CHECK(c == -1 && !strcmp(t, "@a"), "does not fit, unchanged: '%s'", t);
}

static void test_mentions(void)
{
	CHECK(TouchMention_Mentions("hey @Bob look", "bob"), "plain");
	CHECK(TouchMention_Mentions("@bob", "Bob"), "start/end");
	CHECK(!TouchMention_Mentions("hey @bobby", "bob"), "prefix of a longer name");
	CHECK(!TouchMention_Mentions("hey bob", "bob"), "needs the @");
	CHECK(!TouchMention_Mentions("a@bob", "bob"), "address");
	CHECK(TouchMention_Mentions("hey @bob, look", "bob"), "comma ends it");
	CHECK(!TouchMention_Mentions("x", ""), "empty name");
}

static void test_typing(void)
{
	const char *n[] = { "A", "B", "C", "D", "E" };
	char o[128];
	TouchMention_TypingText(n, 0, o, sizeof(o));
	CHECK(!o[0], "nobody");
	TouchMention_TypingText(n, 1, o, sizeof(o));
	CHECK(!strcmp(o, "A is typing\xE2\x80\xA6"), "one: %s", o);
	TouchMention_TypingText(n, 2, o, sizeof(o));
	CHECK(!strcmp(o, "A, B are typing\xE2\x80\xA6"), "two: %s", o);
	TouchMention_TypingText(n, 3, o, sizeof(o));
	CHECK(!strcmp(o, "A, B, C are typing\xE2\x80\xA6"), "three: %s", o);
	TouchMention_TypingText(n, 4, o, sizeof(o));
	CHECK(!strcmp(o, "A, B and 2 others are typing\xE2\x80\xA6"), "four: %s", o);
	TouchMention_TypingText(n, 5, o, sizeof(o));
	CHECK(!strcmp(o, "A, B and 3 others are typing\xE2\x80\xA6"), "five: %s", o);
}


/* a remote player typing: the line about them never lands on my input */
static void test_typing_placement(void)
{
	float size = 14, v, y;
	/* input closed: the chat area */
	CHECK(TouchMention_TypingY(100, size, 0, 28, 0) == 100, "no input open: at the chat area");
	/* input open, empty field or full: above the input line, clear of its box [v, v + h] */
	for (v = 0; v <= 200; v += 10)
	{
		y = TouchMention_TypingY(v, size, 1, 2 * size, 0);
		CHECK(y >= 0, "drawn at v=%g", v);
		CHECK(y + size <= v + 0.001f || y >= v + 2 * size, "typing line [%g, %g] stays off the input box [%g, %g]", y, y + size, v, v + 2 * size);
	}
	CHECK(TouchMention_TypingY(100, size, 1, 28, 0) < 100, "above the input when there is room");
	CHECK(TouchMention_TypingY(5, size, 1, 28, 0) >= 5 + 28, "below the whole box when there is no room above");
	CHECK(TouchMention_TypingY(100, size, 1, 28, 1) < 0 && TouchMention_TypingY(100, size, 0, 28, 1) < 0, "the QuakeC sheet draws its own: the engine draws none");
	/* the text builder is given names only: it has no way to reach a chat buffer */
	{
		const char *n[] = { "Bob" };
		char o[64];
		TouchMention_TypingText(n, 1, o, sizeof(o));
		CHECK(!strstr(o, "hello"), "just a name and a verb: %s", o);
	}
}

static void test_chatname(void)
{
	char n[64];
	CHECK(TouchMention_ChatName("^7\x01^xf80spec^7: hi good luck", n, sizeof(n)) && !strcmp(n, "spec"), "plain chat: '%s'", n);
	CHECK(TouchMention_ChatName("^7^3(team) ^1Bonnie Boo^7: gg", n, sizeof(n)) && !strcmp(n, "Bonnie Boo"), "team chat with a space in the name: '%s'", n);
	CHECK(!TouchMention_ChatName("^7Tester connected", n, sizeof(n)), "a join line has no speaker");
	CHECK(!TouchMention_ChatName("^7: nobody", n, sizeof(n)), "empty name");
}

static void test_complete(void)
{
	char t[64];
	int c;
	// partial in the middle of a line, text after it: one space, cursor after it
	strcpy(t, "hey @bo what's up");
	c = TouchMention_Insert(t, sizeof(t), 4, 7, "Bobby");
	CHECK(!strcmp(t, "hey @Bobby what's up") && c == 11, "middle: '%s' %d", t, c);
	CHECK(t[c - 1] == ' ' && t[c] == 'w', "cursor is right after the one space");
	// at the end: the trailing space is added
	strcpy(t, "hi @al");
	c = TouchMention_Insert(t, sizeof(t), 3, 6, "alice");
	CHECK(!strcmp(t, "hi @alice ") && c == 10, "end: '%s' %d", t, c);
	// a name with colour codes is inserted plain
	strcpy(t, "@x");
	c = TouchMention_Insert(t, sizeof(t), 0, 2, "^1Red^7Guy^x0f0!");
	CHECK(!strcmp(t, "@RedGuy! ") && c == 9, "colours stripped: '%s' %d", t, c);
	// no double space when one follows already
	strcpy(t, "@a there");
	c = TouchMention_Insert(t, sizeof(t), 0, 2, "ann");
	CHECK(!strcmp(t, "@ann there") && c == 5, "existing space reused: '%s' %d", t, c);
	// a bare @ completes too
	strcpy(t, "@");
	c = TouchMention_Insert(t, sizeof(t), 0, 1, "zed");
	CHECK(!strcmp(t, "@zed ") && c == 5, "bare @: '%s' %d", t, c);
}

static void test_place(void)
{
	int below;
	float y;
	// room under the input line: below, right under it
	y = TouchMention_ListY(20, 40, 100, 360, &below);
	CHECK(below == 1 && y == 40, "below when it fits: %g", y);
	// no room below (keyboard top at 120), room above: above the input
	y = TouchMention_ListY(200, 220, 100, 260, &below);
	CHECK(below == 0 && y == 100, "above when below is covered: %g", y);
	// exactly touching the limit still counts as below
	y = TouchMention_ListY(20, 40, 100, 140, &below);
	CHECK(below == 1 && y == 40, "fits exactly: %g", y);
	// neither fits: pushed onto the screen, never negative
	y = TouchMention_ListY(30, 50, 100, 120, &below);
	CHECK(below == 1 && y == 20, "squeezed: %g", y);
	y = TouchMention_ListY(30, 50, 100, 80, &below);
	CHECK(y == 0, "never above the screen: %g", y);
}

int main(void)
{
	test_chatname();
	test_strip();
	test_token();
	test_filter();
	test_insert();
	test_complete();
	test_place();
	test_mentions();
	test_typing();
	test_typing_placement();
	if (failures) { printf("%d failure(s)\n", failures); return 1; }
	printf("mention: ok\n");
	return 0;
}

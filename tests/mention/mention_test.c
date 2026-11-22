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
	CHECK(c == 3 + 1 + 5 + 1 && !strcmp(t, "hi @Bobby  there"), "mid-line: '%s' %d", t, c);
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

int main(void)
{
	test_strip();
	test_token();
	test_filter();
	test_insert();
	test_mentions();
	test_typing();
	if (failures) { printf("%d failure(s)\n", failures); return 1; }
	printf("mention: ok\n");
	return 0;
}

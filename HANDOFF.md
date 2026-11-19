# vote2 handoff

Complete. Branch work/vote2 (built on work/integration of cx-lead), merged with origin/main (already up to date).

- The info "i" chip is shown in every in-match mode (playing, dead, spectating, out) and hidden on the match-end
  screens (intermission, map vote, gametype vote), where the open card is also closed (touch_vote.c TouchVote_Draw).
- Called votes: listed with Yes/No/Abstain for the caller (own Yes already counted), voters, and late joiners.
  The late-join recovery is `cmd vote status` once after signon (parsed in touch_vote_parse.h); the caller is
  recovered from its reply.
- "+" in the card header opens a lazily drawn (5 rows visible), drag-scrolled list of the server's allowed commands
  (reply to `cmd vote help`), with pickers: maps (`cmd lsmaps`), players (#entity), numbers; any other command opens
  the console prefilled with `cmd vote call <command> `. Server-provided names are filtered to [A-Za-z0-9_.-].
- Tests: scripts/test-touch-vote.sh (unit), scripts/test-touch-vote-live.sh (offscreen local dedicated server,
  4 phases; needs /tmp/cx-fast-data/data and the built darkplaces-sdl). `touch_vote_state` prints the panel state.

Not checked: a real tablet; the web build (documented dependency cache missing); Android.
Known gap: the gotomap/kick/number pickers key off command names; unknown custom commands use the console prefill.
Live test notes: spectator clients are used for most phases; one client joins (`cmd join`) for the playing mode.

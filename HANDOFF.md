# vote2 handoff

Incomplete. The info chip now stays visible in modes with touch match controls, its card opens even after the player has voted, and end-of-match map/gametype choices no longer open there. Called-vote reads prefer the server's `vote_waiting` flag and fall back to a parsed broadcast; clients without readable vote globals request and parse `vote status` after joining.

The create-vote button, virtual list of server commands, and argument pickers are not implemented. Late joining, the caller's choice, and map/gametype exclusion still need behavioral tests. The status parser test alone does not prove recovery on a running server. The offscreen server/client attempt and its result are recorded in the worker report. Nothing has been tested on a tablet. Review print/global state transitions and the empty-card design before integration.

Validation: `scripts/test-touch-vote.sh` passed; `taskset -c 12-15 make -C engine/darkplaces -j4 sdl-release sv-release DP_LINK_JPEG=dlopen` passed; `git diff --check` passed. Web engine build failed because the documented cached dependency path is absent and outside the writable workspace. Do not integrate this as a completed task.

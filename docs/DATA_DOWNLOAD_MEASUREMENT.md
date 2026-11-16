# First-launch download measurement

Measured 2026-11-16 against the public releases, from a fresh install. Run
`cd loader && cargo run -p godnotic-loader-core --features http-ureq --example dataplan`
to inspect the current loader plan. Byte counts below are pack payloads, not
HTTP headers, ZIP directory probes, retries, or the manifest request.

| GPU | Manifest response | Fallback | Packs | Payload bytes |
| --- | --- | --- | ---: | ---: |
| ASTC supported | ASTC manifest 404 | slim archive | 6 | 389,766,783 |
| ASTC unsupported or probe fails | DXT manifest 404 | full archive | 7 | 1,170,730,250 |

Android probes ASTC and caches the result; a failed probe selects DXT. The
Android setup falls back to the corresponding single archive when the split
manifest is absent, and includes music. The loader's automatic choice and
fallback match this behavior. A specific phone's GPU result must be observed
on that phone; this measurement does not assert that every phone supports ASTC.

The slim archive's largest pack is maps, 231,062,552 bytes; music is
47,120,605 bytes, data 108,167,773 bytes, and the remaining three packs total
3,415,853 bytes. The archive stores the already compressed pk3s (ZIP method
0), so the outer ZIP does not compress them a second time. The slim archive
already omits nexcompat. Removing maps or compatibility assets outright would
break content; there is no evidence here that those packs can be discarded.

The existing per-map split is the clear startup reduction, but it is not
published. A previously built local ASTC split (`split-G3`, set
`20261107-7f8d55483341`) contains 72 packs totaling 372,601,606 bytes, with
14 first-play packs totaling 74,676,756 bytes. Those are local build numbers,
not the plan currently requested by clients or a measurement after a deployed
change. Publishing requires building and validating both profiles and publishing
their immutable archives before the manifest aliases, as described in
[the data fetch handoff](handoffs/datafetch.md). Until that happens the actual
plan remains 389,766,783 bytes for ASTC and 1,170,730,250 bytes for non-ASTC;
no download reduction is claimed here.

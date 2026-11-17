# Building split data releases

`.github/workflows/split-data.yml` builds both ASTC and DXT from the dated
Godnotic-data full release. It publishes nothing. Dispatch it on a pushed
Godnotic commit, with an unused positive revision:

```sh
gh workflow run split-data.yml -R dixonSolutions/Godnotic --ref main -f source_date=20261107 -f revision=1
```

Download both artifacts from the successful run and verify their checksums.
Each contains a stored ZIP with the individual pk3 files and a format 1
manifest with the immutable archive URL, byte ranges, sizes, CRC32 and SHA256.
The splitter checks the union, dependencies, ranges and hashes against the
repacked input. The ZIP is the release asset: clients fetch individual packs
from it by HTTP Range. The `data-slim` release receives only the two manifests;
its legacy archive is untouched. The revision must never be reused for changed
bytes. The usage table is tied to the source date; record a new one before
building a different date.

The loader's web build currently pins split set IDs in
`scripts/vercel-build.sh`. Before publishing the aliases, set its two pins to
`.set` from these exact manifests and deploy that change, as described in
`docs/handoffs/datafetch.md`. Also validate the artifacts on the engine and
on a tablet before treating them as ready for users.

After those checks, the coordinator can publish the exact artifacts with the
following commands, filling in the successful run ID and using the same date
and revision as the build. The create commands fail if a release tag already
exists; resolve that by choosing a fresh revision and rebuilding. The alias
upload replaces existing manifests, so run it only after both immutable ZIPs
and manifests have been uploaded and verified.

```sh
set -e
RUN_ID=<successful-build-run-id>
DATE=20261107
REV=1
REPO=dixonSolutions/Godnotic-data
mkdir -p split-release
for p in astc dxt; do
  gh run download "$RUN_ID" -R dixonSolutions/Godnotic -n "split-data-$p" -D "split-release/$p"
  (cd "split-release/$p" && sha256sum -c SHA256SUMS)
done
for p in astc dxt; do
  tag="data-$p-$DATE-r$REV"
  gh release create "$tag" -R "$REPO" --prerelease --latest=false --title "Godnotic data $p $DATE r$REV" --notes "Immutable split data from Godnotic build $RUN_ID."
  gh release upload "$tag" -R "$REPO" "split-release/$p/Godnotic-data-$p.zip" "split-release/$p/godnotic-data-$p.json"
  mkdir -p "split-release/verify-$p"
  gh release download "$tag" -R "$REPO" -D "split-release/verify-$p"
  cmp "split-release/$p/Godnotic-data-$p.zip" "split-release/verify-$p/Godnotic-data-$p.zip"
  cmp "split-release/$p/godnotic-data-$p.json" "split-release/verify-$p/godnotic-data-$p.json"
done
for p in astc dxt; do
  gh release upload data-slim -R "$REPO" --clobber "split-release/$p/godnotic-data-$p.json"
done
```

Use `set -e` in the invoking shell so a failed verification stops publication.
Rollback removes the two aliases, retaining the immutable releases for clients
that have already cached a manifest.

# Releasing Eidora

Versioning follows "Muster B": the version lives in the source, and a git tag
points at the commit that carries it. F-Droid builds the app itself from that
tag and reads the version straight from `app/build.gradle.kts` — it does **not**
run the GitHub workflows, so nothing about the version is injected at build time.

## The two version fields

Both live in `app/build.gradle.kts` under `defaultConfig`:

- **`versionName`** — the human-readable string shown to users, e.g. `"1.2.0"`.
- **`versionCode`** — a monotonically increasing integer F-Droid uses to decide
  what's newer. Convention: `MAJOR*10000 + MINOR*100 + PATCH`, so `1.2.0`
  becomes `10200`. Keep each part below 100.

`versionName` is cosmetic; `versionCode` is the one that must always go up.

## Cutting a release

1. **Bump the version** in `app/build.gradle.kts`:
   - set `versionName` to the new string (e.g. `"1.2.0"`)
   - set `versionCode` to the matching integer (e.g. `10200`)
2. **Add a changelog** for that `versionCode`, in each locale:
   - `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`
   - `fastlane/metadata/android/de-DE/changelogs/<versionCode>.txt`

   The file name is the **versionCode** (e.g. `10200.txt`), not the name.
   Keep it short — F-Droid shows it as the "What's New" note.
3. **Commit** both changes together.
4. **Tag** that commit with `v<versionName>` and push the tag:

   ```sh
   git tag v1.2.0
   git push origin v1.2.0
   ```

That's it. The tag is the record of the release; the numbers in the commit are
what F-Droid actually reads.

## First-time F-Droid inclusion

The very first listing is a manual step: submit a metadata file for `org.eidora`
as a merge request to F-Droid's `fdroiddata` repository. After that, F-Droid can
pick up new tags automatically.

The exact metadata fields (update/version-check mode, current version code) and
their syntax should be checked against the current F-Droid documentation at
submission time — the format is occasionally revised, so don't copy an old
example blindly.

## Releasing a new free model container

The free model container (YuNet + SFace) is published separately from the app,
by the **Build free model container** workflow. Its version is driven entirely
by the manifest, so there's one source of truth:

1. Bump `container.version` in
   `docs/containers/free-models/manifest.yml` (an integer — 1, 2, 3, …). Bump it
   whenever the container's contents change. If the **embedder** changes in a way
   that shifts its vector space, also change `container.embedding_space` — that's
   what makes the app recompute embeddings on update instead of silently mixing
   incompatible ones.
2. Run the workflow (manually — it's `workflow_dispatch`).

The workflow reads `container.version`, tags the release `container-free-v<N>`,
attaches the container and its `container-sha256.txt`, and refuses to run if a
release with that tag already exists (so you can't overwrite a published
container without bumping the version). The app finds the newest
`container-free-v<N>` via the GitHub Releases API and verifies the download
against the attached checksum — no hash is hard-coded anywhere.

## Notes

- F-Droid signs with its own key, not ours. An APK you might build yourself and
  the F-Droid APK are therefore not signature-compatible; users can't switch
  between them without reinstalling. This is normal for F-Droid apps.
- The app downloads the free model container at runtime (not at build time), so
  the build itself has no network dependency — good for F-Droid reproducibility.

This is not legal or infrastructure advice — verify the F-Droid specifics
against their current docs.

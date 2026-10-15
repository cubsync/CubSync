# Releasing

## Checklist

1. **Green first.** `./gradlew check` locally, and CI green on `main`.
2. **Decide the version.** Follow [VERSIONING.md](VERSIONING.md). While the major
   version is 0, a change to the wire format is a minor bump.
3. **Bump in one place.** `cubsync.version` in `gradle.properties`.
4. **Check the CLI fallback.** `BuildInfo.DEFAULT_VERSION` in
   `cli/src/main/kotlin/io/github/cubsync/cli/Output.kt` mirrors the same value for
   the case where the CLI does not run from a jar with manifest metadata. Miss this
   and `cubsync --version` will lie.
5. **Write the changelog.** Move the entries out of `Unreleased` into a new version
   section with today's date, and update the two comparison links at the bottom.
6. **Verify the built artefacts.** `./gradlew :cli:installDist` and run
   `cubsync --version` from it, plus the end-to-end procedure in
   [TESTING.md](TESTING.md). A release that has never transferred a file is not a
   release.
7. **Commit.** `chore(release): 0.2.0` — one commit, version and changelog together.
8. **Tag.** Annotated, on `main`:
   `git tag -a v0.2.0 -m "CubSync 0.2.0" && git push origin v0.2.0`.
9. **Publish.** `gh release create v0.2.0 --title "CubSync 0.2.0" --notes-file <file>`
   with the changelog section as the body. Attach artefacts only when there is
   something a user can run; until packaging exists, the source tag is the artefact.
10. **Confirm.** `gh release view v0.2.0` and `git rev-parse v0.2.0^{commit}` — the
    tag must point at the release commit and be an ancestor of `main`.

## Why the release notes are not generated

The commit history is Conventional Commits, so notes *could* be generated. They are
not, because the interesting part of a release in this project is not the list of
commits — it is what changed about the wire format, what a user of an older build
will see when they connect, and what is deliberately still missing. That is a
paragraph a person writes.

The changelog is still organised by `Added` / `Changed` / `Fixed` / `Removed` so it
can be read quickly.

## When the protocol changes

If `Protocol.VERSION` changes:

- The changelog entry must say so in the first paragraph.
- The handshake table in `HandshakeTest` needs the new "older peer" row to be
  meaningful again, and the downgrade path needs a real test.
- Both ends must be able to keep talking at the lower version, or the release notes
  have to say plainly that they cannot.

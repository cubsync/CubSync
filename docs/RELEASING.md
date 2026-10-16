# Releasing

## Checklist

1. **Green first.** `./gradlew check` locally, and CI green on `main`.
2. **Decide the version.** Follow [VERSIONING.md](VERSIONING.md). While the major
   version is 0, a change to the wire format is a minor bump.
3. **Bump in one place.** `cubsync.version` in `gradle.properties`, and nowhere
   else. The build turns it into `cubsync-build.properties`, which is what
   `BuildInfo` reads, so the CLI, the window's about box and the installer's file
   name all follow from that one line. There is no constant to keep in step.
4. **Write the changelog.** Move the entries out of `Unreleased` into a new version
   section with today's date, and update the two comparison links at the bottom.
5. **Verify the built artefacts.** `./gradlew :cli:installDist` and run
   `cubsync --version` from it, plus the end-to-end procedure in
   [TESTING.md](TESTING.md). A release that has never transferred a file is not a
   release. A release with a window in it should also have been looked at:
   `./gradlew :app:desktop:test` writes a picture of every screen to
   `app/desktop/build/screenshots`.
6. **Package what a user will download.** `./gradlew :app:desktop:packageMsi`,
   `./gradlew :app:desktop:createDistributable` and
   `./gradlew :app:android:assembleDebug`. The MSI step downloads WiX the first time
   it runs. Attach all three to the release; a tag alone is not an application. The
   Android artefact is the debug APK until there is a keystore and a decision about
   where the application is distributed — say so in the release notes rather than
   quietly shipping something unsigned.
7. **Commit.** `chore(release): 0.2.0` — one commit, version and changelog together.
8. **Tag.** Annotated, on `main`:
   `git tag -a v0.2.0 -m "CubSync 0.2.0" && git push origin v0.2.0`.
9. **Publish.** `gh release create v0.3.0 --title "CubSync 0.3.0" --notes-file
   docs/releases/v0.3.0.md` — the bilingual notes file described below, committed with
   the release — then attach the installers:
   `gh release upload v0.3.0 app/desktop/build/compose/binaries/main/msi/*.msi`.
   The tag-triggered workflow does the same thing on a machine that is not this
   one; if it ran, its assets are already there and this step is a check.
10. **Confirm.** `gh release view v0.2.0` and `git rev-parse v0.2.0^{commit}` — the
    tag must point at the release commit and be an ancestor of `main`.

## A tag publishes a draft, not a release

Pushing `v*` runs the release workflow, which builds the MSI, the portable zip and the APK
and attaches all three to a **draft** release for that tag. The tag exists, the artefacts
are downloadable by whoever is reviewing the build, and the page stays off the public
releases list until somebody publishes it.

The reason is that "the build succeeded" and "this is ready to hand to a user" are two
different judgements, and only the second one is a decision. Making a tag publish
immediately collapses them: every commit worth building becomes an announcement, and the
releases page stops saying which versions anybody has actually accepted.

So the loop is: tag → CI builds a draft → the draft is reviewed → `gh release edit <tag>
--draft=false` publishes it. Nothing else about the workflow changes; the artefacts are
attached either way, and `gh release view <tag>` still finds a draft if you ask for it by
name.

A tag on its own — without a release — gives only GitHub's generated `Source code` archives,
which are the repository, not the product. That is why the workflow creates the draft rather
than stopping at the tag.

## Release notes are bilingual

The notes live in `docs/releases/<tag>.md`: Chinese first, then a horizontal rule and
`**English**`, then the same thing in English. That file is what `gh release create
--notes-file` is given, and it is committed with the release.

Chinese comes first because the interface is Chinese. The English half is there so a
reader who does not read Chinese is not locked out of what changed — and so the
repository stays readable to anyone who lands on it from a search result.

Keeping the file in the repository rather than in a scratch directory means the release
page has a copy that outlives GitHub's editor, and the next release can be written by
editing the previous one instead of by remembering what last time looked like.

### The notes are collapsed by default — the language, not the sections

A release page exists to hand somebody a file. The notes open above the downloads, so a
long release pushes the thing the page is *for* below the fold and every download becomes
a scroll.

The **language** block ships closed, because skipping the language you do not read is the
scroll nobody wants. The **sections** inside it ship open, because a reader who has chosen
a language has chosen to read it, and a second round of clicking is a second round of work:

```html
<details>
<summary><b>中文</b></summary>

<details open>
<summary><h3>能用了</h3></summary>

正文…

</details>

</details>
```

Two details that are easy to get wrong. Blank lines are part of the shape and not
decoration: with none after `</summary>`, GitHub renders the Markdown inside as literal
text. And the section headings have to be real headings — wrapping them in `<b>`, which the
first version of this did, makes a `##` and an `h4` look identical and the notes lose their
outline.

`tools/collapse-release-notes.py` applies the shape to a plain file and is safe to re-run —
it skips anything that already has a `<details>` in it, so restore from git first if the
shape itself changes.

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

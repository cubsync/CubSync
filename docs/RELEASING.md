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
   `./gradlew :app:android:assembleRelease`. The MSI step downloads WiX the first time
   it runs. Attach all three to the release; a tag alone is not an application.
   The Android build is the one that needs the signing identity — locally from
   `keystore.properties`, on CI from repository secrets, both described in
   [SIGNING.md](SIGNING.md). With no identity the APK comes out unsigned and cannot
   replace an installed one, so say which it is in the notes rather than leaving it
   to be discovered.
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

### One level collapses: the language

A release page exists to hand somebody a file. The notes open above the downloads, so a
long release pushes the thing the page is *for* below the fold and every download becomes
a scroll.

The **language** block ships closed, because skipping the language you do not read is the
scroll nobody wants. Everything inside it stays open, because a reader who has chosen a
language has chosen to read it:

```html
<details>
<summary><h3>中文说明</h3></summary>

#### 能用了

正文…

</details>
```

The sections used to be `<details open>` blocks of their own, one per heading. They were
dropped because **a nested `<details>` gets no indentation on GitHub**: its arrow sits at
the same x as its parent's, so the two levels read as one level, and the only way to fake
the indent is to wrap the inner block in a blockquote and live with the vertical rule that
draws. They also opened by default, so the second level of disclosure never had anything to
disclose — the same content, one click more fragile.

Two details that are easy to get wrong. Blank lines are part of the shape and not
decoration: with none after `</summary>`, GitHub renders the Markdown inside as literal
text. And the headings have to be real headings — wrapping them in `<b>`, which the first
version of this did, makes a `##` and an `h4` look identical and the notes lose their
outline. The **language** label is a heading too, one level above the sections (`h3` over
`h4`); it used to be plain `<b>` while the sections were `h3`, which made the child render
larger than its parent on the release page.

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

## 版本与验证（2026-10-25 定）

**没通过验证的改动，不升版本号。** 版本号是「这一份包给川验证」的标签，不是每次提交的计数器。

| 阶段 | 做法 |
|---|---|
| 改一处、要给他看 | 版本号**不动**，改动累积在当前未通过的版本上 |
| 要重新出包 | 重打**同一个** tag；draft 未 publish 时允许，资产 `--clobber` 覆盖 |
| 他验证通过 | 才把版本号 +1，写 `docs/releases/v<新版本>.md`，打新 tag 出 draft |
| draft 已 publish | tag 与资产**冻结**，只能发下一版 |

重打 tag 只对未发布的 draft 有效。已 publish 的版本再重打，等于改历史：别人下载过的东西和仓库对不上。

**每条改动走 PR，不绕过规则。** `main` 上的 `Changes must be made through a pull request` 是给所有人（包括维护者）定的；`Repository admin` 的 bypass 只在修 CI 自身、且 PR 无法验证时用，用过就在提交信息里写一句为什么。

**本地先出包，再等 CI。** `脚本/local-release.py` 两分钟出 `发布/win64/`（含 APK），要验证的永远是这一份；CI 负责正式签名的 draft，不该成为验证的等待点。

**截图基线跟着版本号走。** 状态栏与设置页都画版本号，所以升版本会改动全部桌面基线：先 `CUBSYNC_SCREENSHOTS=update` 收基线，再提交。若是重打同一个版本（版本号没变），基线不该变——变了说明界面真的动了。

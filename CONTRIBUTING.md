# Contributing to CubSync

Thanks for your interest. Please read this before opening an issue or a pull
request.

## Project status

CubSync is in its **bootstrap phase**. The architecture, the wire protocol, and
the public API of the synchronization engine are all still subject to change
without notice. There is no stable release and no compatibility guarantee.

Because of that:

- **Large pull requests are likely to be declined** unless they were discussed
  first. If you plan to change architecture, the protocol, or the backend SPI,
  open an issue and get agreement before writing code.
- Small, focused fixes (typos, build breakage, obvious bugs, documentation) are
  always welcome and can be sent directly.

## Development setup

Toolchain and build instructions will be published with the first milestone.
Until then, expect the build to require:

- JDK 17 or newer
- Android SDK (for the Android target)
- Gradle (use the wrapper in the repository, not a system install)

## Branching and commits

- `main` is always in a releasable state. Do not push to it directly — all
  changes arrive through a pull request.
- Branch names: `feat/<short-topic>`, `fix/<short-topic>`, `chore/<short-topic>`,
  `docs/<short-topic>`.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/):
  `type(scope): summary`, for example `fix(engine): handle nested pair overlap`.
  This is not cosmetic — release notes and version numbers are generated from
  these messages.
- Keep pull requests small and single-purpose. One change, one PR.
- Use **squash merge**. Merge commits are rejected by the branch ruleset.

## Pull request checklist

- [ ] The change is scoped to one concern.
- [ ] The build passes and the test suite is green.
- [ ] New behaviour has a test, or the PR explains why it cannot be tested.
- [ ] Public API changes are reflected in the documentation.
- [ ] No signing material, credentials, or `.env` files are included.
- [ ] `NOTICE` is updated if a new third-party dependency or asset is added.

## Licensing of contributions

This project is licensed under the Apache License 2.0. By submitting a
contribution you agree that it is licensed under the same terms, as described
in section 5 of that license ("Submission of Contributions"). You retain
copyright to your contribution.

There is no separate CLA to sign. Just do not submit code you do not have the
right to license this way — in particular, **do not copy code from a
GPL-licensed project**. See "Provenance rules" below.

## Provenance rules (important)

CubSync is a clean-room implementation and this matters legally:

- **Do not copy code** from HybridFileXfer, FolderSync, Material Files, or any
  other project, even partially, even "just this one function".
- **Do not paste decompiled or decompiler-derived code.** Not even as a
  starting point to be rewritten later.
- Reimplementing a behaviour after understanding it is fine. Referencing a
  document that describes a protocol is fine. Quoting a third party's source
  into this repository is not.
- If you consult another implementation while working, say so in the PR
  description, naming the project and the revision you looked at.

If you are unsure whether something crosses the line, ask in the issue before
opening the PR.

### AI-assisted contributions

Using AI coding tools is fine. The rules are:

- **You are responsible for what you submit.** Review it, understand it, and
  be able to explain why it is correct. Unreviewed generated code is not a
  contribution.
- **You are responsible for its provenance.** Generative tools sometimes emit
  code that closely matches existing licensed source. If any part of your
  contribution was machine-generated, satisfy yourself that it is not a copy
  of third-party code — particularly GPL-licensed code. When in doubt,
  rewrite it in your own structure or leave it out.
- Declare machine-generated content in the PR description, naming the tool if
  it matters for review.
- Do not submit generated code for a part of the codebase you do not
  understand well enough to maintain.

The maintainers review on these terms and may ask for a rewrite.

## Reporting bugs

Use the bug report issue template. Include:

- Platform and OS version (Android version, desktop OS)
- CubSync version or commit SHA
- Which backend the connection used (LAN, WebDAV, SMB, SFTP, local)
- Steps to reproduce, expected behaviour, actual behaviour
- Logs if available — and check they contain no credentials before attaching

## Security issues

Do not open a public issue. See [SECURITY.md](SECURITY.md).

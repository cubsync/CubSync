# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
with the pre-1.0 rules described in [docs/VERSIONING.md](docs/VERSIONING.md).

## [Unreleased]

## [0.3.0] - 2026-10-16

The first release with an interface, on both a desktop and a phone.

The three numbers before this one — 0.3.0, 0.4.0, 0.5.0 — each covered a single
platform, because the features landed at different times. They describe one product,
so they are one version: a tag builds the Windows installer, the Windows application
image and the Android APK from the same commit, gives them the same number and puts
them on the same release page.

### Added

- **A Windows desktop application** (`app/desktop`), built with Compose
  Multiplatform. Screens — connect, transfer, sync, jobs, logs, settings — with the
  dark and light palettes taken from the design prototype. Configurations are created
  and edited in place, folder pairs are added by picking a device and a folder, and a
  run shows its progress and then its result. Shipped as an MSI and as a
  self-contained application image.
- **An Android application** (`app/android`), with the same words as the desktop and
  the landing points a phone can use: a configuration and a job open as their own
  screen with a back arrow, and the log belongs to the job rather than to a tab of its
  own, because on a phone the question after a run is what *that* run did. Directories
  are offered as suggestions rather than through a picker: the platform's picker hands
  back a content URI and the engine works in paths, so a wrong guess somebody can
  correct in a text field beats a picker that produces something the engine cannot
  open. No storage permission is requested at all.
- **The two-pane transfer browser**, on both platforms — the screen the design starts
  on: what is here, what is there, and an arrow between them. Select rows, or press the
  arrow with nothing selected to move everything visible, and the copy runs through the
  same backend contract the synchroniser uses.
- **The display panel**, shared by both ends: five display modes (紧凑 / 详细 / 卡片 /
  缩略 / 对比), row density as a percentage, column count for the grid modes, and the
  three-way 分栏方向 with an 自动 that follows the shape of the container.
- **A shared application layer** (`service`). The configuration store, the peer pool,
  the run service, the folder server, the browse session, the formatter and the
  application log live here, and both front ends are written against it. A sync started
  from a button and one started from a terminal are the same code path, which is the
  property that matters when only one of them has been tried.
- **The finalised brand.** The V1-T2 mark is in both applications — Android gets the
  adaptive icon with a monochrome layer for themed icons and every density, Windows
  gets the multi-resolution `.ico` for the installer and the window — and the accent
  colour in both interfaces is the brand indigo `#3A5CCC`.
- **Peer details in the window.** A connection reports the peer's name, version,
  protocol version and the folders it exposes, all of it taken from the handshake
  rather than guessed.
- **`AppLog`**, a bounded in-memory log with levels and areas, streamed to whichever
  front end is showing. The CLI prints warnings; the window renders all of it.
- **A screenshot renderer in the test suite.** Every screen is composed off screen and
  written to `app/desktop/build/screenshots`, so a screen that fails to compose fails
  the build instead of being found by whoever opens the window next.

### Changed

- **`BrowserSession` is one implementation for two products.** What is open, what is
  selected, how a list is ordered, and what a copy does when one side cannot be reached
  are decided once, in `service`, and published as flows. The screens draw it.
- **`SyncDirection.deletesAllowed` now includes two-way.** It previously said that
  two-way propagates no deletions, which the engine never believed: a deletion there is
  discovered by comparing with the snapshot, not decided by the direction. The window
  was telling people the opposite of what was about to happen.
- **Root paths are normalised at every boundary.** `LocalBackend`, `BackendPool` and
  the folder server all store a root with forward slashes, so `D:\photos` from a server
  and `D:/photos` from an address box are one folder rather than two.
- **`LanBackend` keeps what the peer said about itself** — application version and
  protocol version — instead of discarding them once the handshake was checked.
- The version is read from a resource the build generates out of `gradle.properties`.
  There is no longer a `DEFAULT_VERSION` constant in the CLI to keep in step.
- The shared formatter and the policy sentence live in `service`, so the two front ends
  cannot describe the same policy in two different ways.

### Fixed

Three defects that only a platform other than the one they were written on could show.

- **`Files.readString` and `Files.writeString` do not exist on Android.** They are
  Java 11 additions and are absent from `android.jar` even at API 37, so the code
  compiled on a desktop JDK and would have thrown `NoSuchMethodError` on a phone — in
  writing a configuration, reading one, and writing a snapshot, which is to say the
  first three things anybody does. Everything goes through streams now.
- **A rename into place now survives a filesystem without `ATOMIC_MOVE`.** Android
  reports that situation with `UnsupportedOperationException` where a desktop JDK
  throws `AtomicMoveNotSupportedException`, and the first was not caught, so every
  write would have failed on a filesystem with no opinion about atomicity.
- **The Android launcher icon had the mark in both layers.** The adaptive icon's
  background was drawn with the mark as well as the foreground, so two circles of the
  same colour landed on top of each other and the launcher showed a single block of
  blue. The background is a plain colour again, and `素材/logo/tools/build.py` generates
  it that way.

### Note

The interface is not the design prototype yet. What is missing, and the order it will
be closed in, is in `报告/规划/2026-10-16-后续规划与实施.md`.

## [0.2.0] - 2026-10-16

### Added

- **Saved configurations.** `cubsync config new | list | show | add-pair | export |
  import` and `cubsync run <config>`. A configuration is global policy plus a list
  of folder pairs, and a pair names its sides independently, so one run can cover
  a local folder, one peer and another peer at once.
- **Per-pair backend resolution.** The engine resolves every endpoint through a
  resolver instead of holding one fixed pair of backends, which is what makes a
  multi-peer configuration expressible. Two pairs pointing at the same peer share
  one session.

### Changed

- Importing a configuration whose id is already in use now saves it under a new id
  and says so, instead of silently replacing the existing one.
- CLI output is ASCII: an em dash rendered as mojibake in a console with a
  non-UTF-8 code page is worse than a hyphen.

## [0.1.0] - 2026-10-16

The first working slice: the protocol the project owns, the synchronisation
engine, two backends and a command line interface. Nothing here is stable yet —
the wire format and the engine's public API can still change between minor
versions.

### Added

- **LAN protocol** (`io.github.cubsync.core.protocol`): 24-byte framed transport
  over two TCP connections — control frames on one, file payloads on the other.
  Command numbers start at 100. Capability bits are negotiated as an intersection;
  an older peer is accepted at its own level and a newer one is refused with an
  explanation. A command the peer does not implement is answered by name instead of
  desynchronising the stream.
- **Synchronisation engine** (`io.github.cubsync.core.engine`): pure comparison
  over two listings, table-driven planning for five directions, path-overlap
  blocking, per-pair snapshots, conflict policies, failure isolation between pairs,
  and an executor that runs pairs serially and files within a pair in parallel.
- **Backend SPI** (`io.github.cubsync.core.spi`): the engine's only dependency.
  Trees are addressed by a server-issued root; writes are sequential with an
  explicit resume offset; `commit()` fixes the ordering that matters (data durable,
  then rename, then timestamp).
- **Local backend** (`backends/local`): staging through a deterministic
  `.cubtmp` file, atomic replacement, timestamp applied last, path-traversal
  refusal, and a per-root `.cub_trash` for recycled deletions.
- **LAN backend** (`backends/lan`): server and client over the protocol above,
  request correlation so metadata keeps flowing during a transfer, per-stream
  queues for out-of-order chunk assembly, a monotonic watchdog, and a root
  allow-list that a peer cannot talk its way out of.
- **CLI** (`cli`): `serve`, `ls`, `push`, `pull` and `compare`, with a JSON output
  mode and exit codes that distinguish "everything ran" from "something did not".
- **Filters**: FreeFileSync-style globs (`*`, `?`, `**`, `[...]`), size and time
  bounds, hidden-entry handling, and per-pair overrides with an explicit off switch.
- **Configuration model**: global policy plus folder pairs, one JSON schema shared
  by both ends, a versioned on-disk format with tolerant import, and an export
  envelope.
- **Test suite**: 193 tests across the protocol, the filter engine, the comparison
  and planning matrices, chunk reordering, configuration codecs, both backends,
  and an in-memory backend that shares no code with the local one.

### Verified

- One gibibyte transferred between two JVM processes over the real protocol on
  loopback: MD5 identical, 6.8 s of engine time (~150 MiB/s including the
  verification pass), no staging file left behind, and a second run reporting
  nothing to do.
- Mirroring a tree recycles a deletion into the target root's trash instead of
  unlinking it.

### Known limitations

- Android and desktop targets are not built yet; the engine is platform-neutral
  and the modules are laid out for it, but only the JVM target exists.
- `SyncDirection.CUSTOM` is not implemented; planning refuses it rather than
  guessing.
- No scheduling: nothing runs unattended yet.
- One data connection per session, so parallel transfers share one TCP stream.
- Credentials and encryption: the protocol is plaintext and intends to run over
  a trusted network until authentication is designed.

[Unreleased]: https://github.com/cubsync/CubSync/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/cubsync/CubSync/releases/tag/v0.3.0
[0.2.0]: https://github.com/cubsync/CubSync/releases/tag/v0.2.0
[0.1.0]: https://github.com/cubsync/CubSync/releases/tag/v0.1.0

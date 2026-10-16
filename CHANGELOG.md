# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
with the pre-1.0 rules described in [docs/VERSIONING.md](docs/VERSIONING.md).

## [Unreleased]

## [0.3.0] - 2026-10-16

First release with an interface, on both desktop and Android.

### Added

- Windows desktop application (Compose Multiplatform): connect, transfer, sync, jobs,
  logs, settings. Shipped as an MSI and as a self-contained image.
- Android application with the same screens and the same words as the desktop. No
  storage permission is requested.
- Two-pane transfer browser on both platforms.
- Display panel: five display modes, row density as a percentage, column count for the
  grid modes, and pane direction.
- A shared `service` layer — configuration store, peer pool, run service, folder server,
  browse session, log — that both front ends are written against.
- `AppLog`; a screenshot renderer that fails the build when a screen will not compose.

### Changed

- One tag produces the Windows installer, the Windows image and the Android APK under one
  version number. The releases before this one covered a platform each.
- Root paths are normalised to forward slashes at every boundary.
- `SyncDirection.deletesAllowed` now includes two-way, which is what the engine did all along.
- The version is read from a generated resource; the CLI's `DEFAULT_VERSION` constant is gone.

### Fixed

- Android launcher icon showed a block of colour instead of the mark.
- `Files.readString` and `Files.writeString` do not exist on Android.
- A rename into place did not survive Android's `UnsupportedOperationException`.

### Security

- The wire protocol is unencrypted and unauthenticated. Sharing is for a trusted network;
  do not expose the port to the internet.

## [0.2.0] - 2026-10-16

### Added

- Saved configurations: `cubsync config new | list | show | add-pair | export | import`
  and `cubsync run <config>`. A pair names its two sides independently, so one run can
  cover a local folder and two peers.
- Per-pair backend resolution, which is what makes a multi-peer configuration
  expressible; two pairs pointing at the same peer share one session.

### Changed

- Importing a configuration whose id is already in use saves it under a new id and says
  so, instead of replacing the existing one.
- CLI output is ASCII: an em dash in a non-UTF-8 console is worse than a hyphen.

## [0.1.0] - 2026-10-16

The first working slice: the protocol, the engine, two backends and a CLI. Nothing is
stable yet — the wire format and the engine's public API can still change.

### Added

- LAN protocol: 24-byte frames over two TCP connections, one for control and one for
  payload; capability bits negotiated as an intersection; a command the peer does not
  implement answered by name rather than desynchronising the stream.
- Synchronisation engine: comparison over two listings, table-driven planning for five
  directions, path-overlap blocking, per-pair snapshots, conflict policies, and failure
  isolation between pairs.
- Backend SPI: the engine's only dependency. Writes are sequential with an explicit
  resume offset, and `commit()` fixes the order that matters — data durable, then
  rename, then timestamp.
- Local backend: staging through a deterministic `.cubtmp` file, atomic replacement,
  path-traversal refusal, and a per-root `.cub_trash`.
- LAN backend: server and client over the protocol, request correlation that keeps
  metadata flowing during a transfer, per-stream queues for out-of-order chunks, and a
  root allow-list a peer cannot talk its way out of.
- CLI: `serve`, `ls`, `push`, `pull`, `compare`, with a JSON output mode and exit codes
  that distinguish "everything ran" from "something did not".
- Filters: globs (`*`, `?`, `**`, `[...]`), size and time bounds, hidden entries, and
  per-pair overrides.
- Configuration model: global policy plus folder pairs, one JSON schema shared by both
  ends, and a versioned on-disk format with tolerant import.

### Verified

- 1 GiB between two JVM processes over the real protocol on loopback: identical MD5,
  ~150 MiB/s including the verification pass, no staging file left behind, and a second
  run reporting nothing to do.

### Known limitations

- Android and desktop targets are not built yet.
- `SyncDirection.CUSTOM` is not implemented; planning refuses it rather than guessing.

[Unreleased]: https://github.com/cubsync/CubSync/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/cubsync/CubSync/releases/tag/v0.3.0
[0.2.0]: https://github.com/cubsync/CubSync/releases/tag/v0.2.0
[0.1.0]: https://github.com/cubsync/CubSync/releases/tag/v0.1.0

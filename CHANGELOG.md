# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
with the pre-1.0 rules described in [docs/VERSIONING.md](docs/VERSIONING.md).

## [Unreleased]

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

[Unreleased]: https://github.com/cubsync/CubSync/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/cubsync/CubSync/releases/tag/v0.1.0

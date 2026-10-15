# CubSync

**Cross-platform file sync — one engine, many backends.**

CubSync keeps folders in sync between your devices over your own network.
Android, desktop, and headless (daemon) in one codebase, driven by a single
synchronization engine that is agnostic to the transport underneath it.

> Status: **early development**. Nothing here is stable yet — no releases, no
> wire-format guarantee, no migration path.

---

## What it is

A file synchronization tool, not just a file transfer tool. You define pairs
of folders, pick a direction and a comparison strategy, and CubSync works out
what differs and what to do about it.

| | |
|---|---|
| **Directions** | two-way, mirror (one-way), update-only |
| **Comparison** | timestamp + size, content hash, size only |
| **Filters** | include / exclude globs, size thresholds, per-pair overrides |
| **History** | per-pair snapshots, conflict reporting, isolated retry of failed pairs |
| **Layout** | multiple configurations, each with multiple folder pairs |

## Architecture in one paragraph

The synchronization engine talks only to a `RemoteBackend` interface. A LAN
transport (CubSync's own framed protocol), WebDAV, SMB, SFTP, FTP and local
filesystem are all just implementations of that interface. The engine and the
UI have no knowledge of which one is in use, so adding a backend does not
touch them. Only the first-party LAN protocol supports parallel channels.

## Platforms

| Target | Status |
|---|---|
| Android (Compose + Material 3) | planned |
| Desktop — Windows / macOS / Linux (Compose Multiplatform) | planned |
| Headless daemon (NAS, containers) | planned |

Single-language codebase: **Kotlin** (Kotlin Multiplatform + Compose
Multiplatform) for every target.

## Repository layout

```
core/          shared engine, models, filters, persistence  (planned)
backends/      SPI implementations: lan, webdav, smb, sftp, local  (planned)
app-android/   Android application  (planned)
app-desktop/   Desktop application  (planned)
daemon/        headless mode  (planned)
plugins/       plugin API and examples  (planned)
```

## Building

Build instructions will be published with the first milestone. Toolchain
requirements (JDK, Android SDK, Gradle) will be pinned in the repository so
builds are reproducible.

## Contributing

This project is in its bootstrap phase and its architecture is still moving.
Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

## Security

Please do not open a public issue for security problems. See
[SECURITY.md](SECURITY.md).

## License

Apache License 2.0 — see [LICENSE](LICENSE).

Third-party attributions and prior-art credit: see [NOTICE](NOTICE).

---

## Credits and Prior Art

CubSync is written from scratch and contains **no copied source code**. Its
design was informed by earlier work, which we credit here as a statement of
fact:

- **[HybridFileXfer](https://github.com/weixiansen574/HybridFileXfer)** (GPL-3.0)
  — informed the core concept: moving files between Android and desktop over
  a local network, and the dual-pane browsing model. No code, files, or
  protocol definitions were copied, ported, or derived. CubSync is therefore
  not a derivative work of HybridFileXfer.
- **FolderSync Pro** — information architecture for multi-pair configuration
  and job history. Design inspiration only.
- **Material Files** — Android file-browser interaction conventions.
  Design inspiration only.

Trademarks belong to their respective owners; see [NOTICE](NOTICE).

## Differences from prior art

| | HybridFileXfer | CubSync |
|---|---|---|
| Purpose | file transfer | file **synchronization** |
| Direction | one-way, server-driven | bidirectional, peer-to-peer |
| Protocol | first-party only | pluggable backends (SPI) |
| Desktop | command line | full GUI (Compose Multiplatform) |
| Resume | limited | manifest negotiation + persistent jobs |
| License | GPL-3.0 | Apache-2.0 |

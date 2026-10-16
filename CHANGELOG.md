# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
with the pre-1.0 rules described in [docs/VERSIONING.md](docs/VERSIONING.md).

## [Unreleased]

## [0.3.5] - 2026-10-16

### Fixed

- The phone keeps its own address on screen. Starting the server reported itself with a
  four-second notice and left nothing behind, and that port is what the other machine has
  to be given. The card now shows 端口 plus one `IP:port` per interface, tappable to copy.

### Changed

- Release notes are collapsible: the language block starts closed, its sections start open,
  and the headings keep the depth the file had instead of being flattened to bold.
  `tools/collapse-release-notes.py` applies the shape, and every earlier release was
  reshaped the same way.

## [0.3.4] - 2026-10-16

### Fixed

- The phone can start its server. It required a shared folder, had none, and answered
  `choose at least one folder to share`; the first run now seeds the phone's own storage.
- Both transfer screens open on real folders instead of an empty path.
- The desktop window no longer disappears when dragged: the drag used the pointer delta
  measured inside the window, which fed the movement back in.
- The desktop no longer draws a sub-row and a standing display bar the prototype does not
  have; the two controls on it were duplicates of the heading's.
- The phone's tab bar no longer mixes 34dp and 38dp targets, and none of its controls are
  characters any more.
- The phone's drawer keeps clear of the system bars.
- The mark is drawn from the brand constants rather than the interface tokens, which is why
  its small circle had stopped being teal.

### Changed

- 显示方式 is a drop-down on the desktop heading and five marks on the phone's view bar,
  both as drawn; every mode is behind its own icon.
- The phone's transfer screen has no strip between the panes — the prototype draws that on
  the desktop only. A tap enters or ticks, a long press opens the action panel, and a
  selection raises the bar along the bottom.
- The phone's 连接 screen is the prototype's four blocks: 账户与连接状态 folded into 最近.
- Rows carry the mark for what they are: folder, image, video, document, plain file.

### Removed

- The desktop dependency on `compose.components.resources`, which nothing used.

## [0.3.3] - 2026-10-16

### Changed

- Both interfaces are redrawn from the prototype value by value: the desktop uses
  `--win-accent:#1e88e5` and the phone `--m3-primary:#4c6ef5`, each with its own surfaces,
  and the radii and type scale follow the prototype too. The previous release had
  substituted the brand indigo for the accent and called it deliberate.
- 连接 is 连接设备 on the desktop, laid out as `连接方式 | 协议协商` over 传输通道, with the
  negotiated protocol version and capability bits read from the handshake.
- 运行模式 is gone from the desktop: 普通 / Root / Shizuku-ADB are Android capabilities.
- 缓冲块数 defaults to the prototype's 256 (1MB × 256); the old ceiling of 16 made that
  value unenterable.
- Every icon is an `ImageVector` built from the prototype's own path data.

### Added

- A drawn title bar (mark, product name, device chip, window controls) on the desktop, so
  the window has no native title bar and no white strip in the dark theme.
- The rail's 工作区 / 任务 / 其他 groups, the bookmark list, the fold button, and a 28px
  status bar along the bottom of the window.
- 动态取色 (Material You) and the five accent hue chips on the phone's 设置.
- The phone keeps clear of the system bars instead of drawing under them.
- The phone's drawer opens with a left swipe as well as from the menu mark.

### Fixed

- The active pane and the selected row no longer paint a saturated block: both used the
  accent at a higher alpha than the surface they replaced.
- The phone's file rows and its bottom bar no longer lose their marks to a font that has
  no glyph for them.

## [0.3.2] - 2026-10-16

The release where the phone is laid out the way the design lays it out, and where an icon
is a shape rather than a character.

### Added

- `service`: `IconPaths`, the icon geometry both interfaces draw — five navigation marks,
  the three pane states, and the chrome. SVG path data, because a glyph has a font behind
  it and a font is a thing that may not have an opinion about `☰`.
- `service`: `Peers.localNics()`, which lists this machine's interfaces with the address
  each can be reached at, and names what kind of card each one is.
- `service`: `AppSettings.channelNics`, the 传输通道 selection — empty means every card.
- A phone screenshot test: eleven screens composed off screen with Robolectric and written
  to PNGs, so a layout can be looked at without a device or an emulator.

### Changed

- 运行模式 on the phone is a card whose heading carries the active mode and whose body
  folds away, instead of three rows always on screen.
- 传输通道 on the phone lists the interfaces that exist, each with a switch and a live
  count of what is selected, instead of a fixed list of three channel kinds.
- 同步方向, 比较方式, 冲突时 and 删除时 are rows that open a list rather than dropdowns,
  which is the phone's version of the same six values.
- 差异预览 leads with the counts and groups by folder pair.
- 正在同步 reports per-pair progress, which is the granularity the engine actually
  reports, rather than a bar that would have to guess between pairs.
- 设置 is ordered appearance first, then browsing and transfer, as the design has it.
- The drawer is headed by the product's mark, drawn from the same three numbers the logo
  build uses.
- Android: one type scale for the phone — card titles 14, row names 14, row details 11.5,
  card buttons 14 — with icons at 20dp inside 38–40dp targets and rows at 56dp.

### Fixed

- 新增 and 更新 were counted by action kind, so every copied file — including a file the
  other side had never seen — was reported as an update, and 新增 counted directories only.
  They are read off the comparison now, where the difference between "new" and "changed"
  actually lives.
- The job detail printed the number of copied files twice, once under 新增 and once under
  更新. It reads the same comparison the preview does.
- The phone's bottom bar and the desktop's rail no longer lose their icons to a font
  without the glyph: every mark is an `ImageVector` now.
- A notice on the phone disappears on its own after four seconds.
- The Android launcher icon's white ground, which the previous release changed for the
  legacy mipmap sizes but not for the adaptive layer.
- The phone screenshot test is debug-only: `ui-test-manifest` declares an activity the
  release APK does not have, and shipping it to make a test pass would be the wrong trade.

## [0.3.1] - 2026-10-16

The release where the interface catches up with the design document.

### Added

- The transfer screen's display bar: five display modes, a row density you can type as a
  percentage, a column count for the grid modes, pane direction, and item scale.
- A per-pane menu: sort key and direction, folders first, and the three 作用范围 scopes.
- The connect screen's run mode card, channel list, account and connection state, and
  recent transfers.
- The sync screen's preview and configuration-detail layers.
- A job detail screen on both ends: result banner, thirteen key/value rows, file-level
  transferred and skipped groups, that run's log inline, and the actions it ended with.
- Nine settings fields — default display mode, item scale, show metadata, header address
  lines, pane direction, buffer blocks, default receive folder, resume, delete to trash —
  plus theme and accent.
- A drawer: favourites grouped by device, accounts with their state, and roots.
- `service`: `UiSpec` (the names and step counts both ends share), `AppSettings` (the
  settings and the three-layer 作用范围 rule), `JobDetailsFactory`, `SyncPreview`,
  `DrawerModel`.

### Changed

- Sync direction, comparison, conflict and deletion moved out of the settings screen and
  into the configuration they govern.
- Every visible word, step count and state meaning now comes from `UiSpec`, so the two
  ends cannot describe the same thing differently.
- The Android launcher icon has a white ground and a 48dp mark instead of 66dp.

### Fixed

- Pressing 新建文件夹 twice quickly created one folder: the name was chosen from a listing
  taken before the first creation, so both presses aimed at the same path.

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

[Unreleased]: https://github.com/cubsync/CubSync/compare/v0.3.1...HEAD
[0.3.1]: https://github.com/cubsync/CubSync/releases/tag/v0.3.1

[0.3.0]: https://github.com/cubsync/CubSync/releases/tag/v0.3.0
[0.2.0]: https://github.com/cubsync/CubSync/releases/tag/v0.2.0
[0.1.0]: https://github.com/cubsync/CubSync/releases/tag/v0.1.0

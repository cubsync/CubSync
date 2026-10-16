<img src="docs/logo-lockup.svg" alt="CubSync" width="440">

**English** · **[中文](#中文)**

---

## English

**Cross-platform file sync — one engine, many backends.**

CubSync keeps folders in sync between your devices over your own network. Android,
desktop, and headless (daemon) in one codebase, driven by a single synchronization
engine that is agnostic to the transport underneath it.

> Status: **usable, early**. Version 0.3.1 has the engine, the protocol, two backends,
> a CLI, a Windows desktop application and an Android application, with the interface at
> the design document's level of detail on both ends. One tag produces every platform's
> package under one version number. The wire format can still change between minor
> versions — see [docs/VERSIONING.md](docs/VERSIONING.md).

**▶ [Try the interface prototype](https://cubsync.github.io/demo/)** — both ends, clickable,
in a browser. Nothing to install. The screenshots below are from the real applications; the
prototype is the design at full size.

![The connect screen](docs/screenshots/connect.png)

*Tell this machine what to share, then connect to another machine that is sharing.
Those two things have to be true before anything else can work.*

---

### What it is

A file synchronization tool, not just a file transfer tool. You define pairs of
folders, pick a direction and a comparison strategy, and CubSync works out what
differs and what to do about it.

| | |
|---|---|
| **Directions** | two-way, mirror (one-way), update-only |
| **Comparison** | timestamp + size, content hash, size only |
| **Filters** | include / exclude globs, size and time bounds, per-pair overrides |
| **History** | per-pair snapshots, conflict reporting, isolated retry of failed pairs |
| **Layout** | multiple configurations, each with multiple folder pairs |
| **Resume** | staging file plus an explicit resume offset; interrupted transfers continue |

### Architecture in one paragraph

The synchronization engine talks only to a `RemoteBackend` interface. A LAN
transport (CubSync's own framed protocol), WebDAV, SMB, SFTP, FTP and local
filesystem are all just implementations of that interface. The engine and the UI
have no knowledge of which one is in use, so adding a backend does not touch them.
Only the first-party LAN protocol supports parallel channels.

More detail, including the decisions that are easy to get wrong, in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

### Platforms

| Target | Status |
|---|---|
| Headless CLI + daemon | works (JVM 17+) |
| Desktop — Windows (Compose Multiplatform) | works; MSI and application image |
| Desktop — macOS / Linux | builds from source; no packaged artefact yet |
| Android (Compose + Material 3) | works; sideload the APK from the release |

Single-language codebase: **Kotlin** (Kotlin Multiplatform + Compose Multiplatform)
for every target. `core` is already a multiplatform source set, so the Android and
desktop targets are additions rather than a restructure.

### Repository layout

```
core/           protocol · model · filter · spi · transfer · engine · util
backends/local/ the filesystem as a backend
backends/lan/   a peer's tree over the first-party protocol
service/        the application layer: configs, peers, runs, the folder server, the log
app/desktop/    the desktop application (Compose Multiplatform)
app/android/    the Android application (Compose, Material 3)
cli/            the command line entry point
testing/        test-only fixtures (in-memory backend, fault injector)
docs/           architecture, versioning, testing, releasing
```

### Building

Requires JDK 17 or newer. The Gradle wrapper is committed; do not use a system
Gradle.

```
./gradlew check          # compile + test, what CI runs
./gradlew :cli:installDist
./cli/build/install/cubsync/bin/cubsync --version
```

Run the desktop application straight from the source tree:

```
./gradlew :app:desktop:run
```

Or build something to hand to somebody:

```
./gradlew :app:desktop:packageMsi            # an installer
./gradlew :app:desktop:createDistributable   # a self-contained folder with CubSync.exe
```

Packaging an MSI makes the build download WiX, so that step needs network access the
first time.

The Android application needs the SDK (API 36 and build-tools 36), pointed at by
either `ANDROID_HOME` or a `local.properties` with `sdk.dir`. It is the only module
that needs one; everything else builds with a JDK alone.

```
./gradlew :app:android:assembleDebug        # app/android/build/outputs/apk/debug/
./gradlew :app:android:installDebug         # straight onto a connected device
```

There is no signed release build yet: that needs a keystore, and a keystore is a
decision about distribution rather than a build step. The APK on each release is the
debug one, which sideloads normally.

### Using the desktop application

The rail has six entries, in the design's order. The first one is the only one that has to
be understood before the rest works.

![The connect screen](docs/screenshots/connect.png)

**连接** — pick the run mode, see which channels are available, then choose the folders
this machine shares and press 开始共享; the addresses to type on the other machine appear
under it. Connecting out is the other half and is independent: a one-way copy to a peer
works without sharing anything locally, and a folder can be shared without ever connecting
out. Accounts and their state, and the last few transfers, are on the same screen.

**传输** — two folders and the arrow between them, which is what the product is named
after. 显示方式 ▾ opens a bar holding all five modes, 行密度 as a typeable percentage,
列数 for the grid modes, 分栏方向 and 条目缩放; it acts on whichever pane you touched
last. Each pane's ⋯ holds its sort order, 文件夹优先 and the three 作用范围 scopes — and
the header of each pane *is* its address: every layer is clickable, the middle folds into a
`⋯`, and clicking the blank part turns it into a box you can type a path into.

![The transfer screen](docs/screenshots/transfer.png)

**同步** — a configuration is a policy plus a list of folder pairs. The list is on the
left, the configuration on the right: 同步方向, 比较方式, 冲突, 删除 and 并行度, then the
pairs, then 过滤. 预览 compares and shows the summary without writing anything; 试运行 does
the same and records it; 立即同步 does the work.

**作业** — every run, newest first. Open one and it becomes a screen of its own: the
result, thirteen rows of detail, the file-level lists of what was transferred and what was
skipped and why, that run's own log, and the actions its outcome allows — 再次运行, 重试失败对
or 报告.

![The jobs screen](docs/screenshots/jobs.png)
![The job detail screen](docs/screenshots/job-detail.png)

**日志** — the live record, filterable by level and by area. On the desktop it keeps an
entry of its own, because the window has room for one.

**设置** — the nine browsing and transfer fields the design lists — 默认显示方式, 条目缩放,
显示元数据, 栏头地址行数, 分栏方向, 缓冲块数, 默认接收目录, 断点续传, 同步删除走回收站 — plus
the theme and the accent. The synchronisation policy is deliberately not here: it belongs
to the configuration it governs.

![The settings screen](docs/screenshots/settings.png)

### On a phone

The Android application has five tabs — the desktop's six minus 日志, which belongs to the
job it came from — and the same words, the same step counts and the same states. Those
come from one place (`UiSpec` in the `service` module), so the two ends cannot describe the
same setting differently.

**连接** — the run mode card first, because on a phone it is the question underneath
everything else, and collapsed, because somebody who is not fighting permissions should not
have to read about them. Then the channels — one switch per network card this machine
actually has, with the address it can be reached at — then sharing, then the accounts and
their state, then the last few transfers.

![The phone's connect screen](docs/screenshots/phone-connect.png)

**传输** — the same two panes, with the design's own sub-row: ☰ for the drawer, a
three-way switch between both panes and either one alone, 显示方式▾, and ⋮. The display bar
hangs under it with the five modes, 行密度 as a percentage you can type, 列数 for the grid
modes, 分栏方向 and 条目缩放. A long press on a row opens its action panel; the header of a
pane is its address, exactly as on the desktop.

![The phone's transfer screen](docs/screenshots/phone-transfer.png)

**同步** — the list of configurations, and each of them opened with `←`: the policy as four
rows that open a list, the pairs, the filter, then 预览 as a layer of its own. A value with
six choices is a row rather than a dropdown, because a dropdown is a mouse's affordance;
the values are the same on both ends either way.

![The phone's difference preview](docs/screenshots/phone-sync-preview.png)
![A run in flight on the phone](docs/screenshots/phone-running.png)

**作业** — every run, and one run opened in full: the result, the thirteen detail rows, the
file-level lists, that run's log inline, and the actions it ended with. 日志 is the second
segment of this tab rather than a tab of its own.

**设置** — the same nine fields as the desktop, in the same order, with the same defaults,
and the appearance above them.

![The phone's settings screen](docs/screenshots/phone-settings.png)

Those five images are not mock-ups: `PhoneScreenshotTest` composes every phone screen on the
JVM and writes it to a PNG. See [docs/TESTING.md](docs/TESTING.md).

### Using the command line

Serve a folder to the network:

```
cubsync serve --root /srv/photos --port 43817 --name nas
```

Look at what a peer offers, then copy to it:

```
cubsync ls nas.local:43817
cubsync push --to nas.local:43817 --remote /srv/photos --from ./photos \
             --direction mirror-to --compare hash --parallel 4
```

The direction defaults to update-only, so a first run cannot delete anything on the
peer; mirroring has to be asked for. Deletions default to `recycle`, which moves the
file into `.cub_trash` inside the root it came from.

A one-off transfer is fine, but the point of the tool is a saved configuration:
several folder pairs, one policy, and a history per pair so a two-way run can tell a
deletion from a new file.

```
cubsync config new --name "photos and docs" --direction two-way
cubsync config add-pair <id> --left local --left-root ~/photos \
                             --right nas.local:43817 --right-root /srv/photos
cubsync config add-pair <id> --left local --left-root ~/docs \
                             --right phone.local:43817 --right-root /sdcard/Docs \
                             --filter '*.tmp' --name "documents"
cubsync config show <id>
cubsync run <id>
```

`cubsync --help` lists every option. `--json` prints a machine-readable summary built
from the same result the text output shows, and the exit code is 0 when every pair
finished, 1 when something did not, and 2 when the command line was wrong.

Configurations and their per-pair snapshots live under `~/.cubsync` by default and
can be moved with `--state-dir`; `cubsync config export` writes a `.cubcfg` that can
be imported on another machine.

### Testing

```
./gradlew check
```

Every suite in the repository: the protocol codec and its malformed inputs, the
filter dialect, the comparison and planning matrices, chunk reordering,
configuration codecs, both backends against real sockets and real files, the engine
driving an in-memory backend that shares no code with the local one, the application
layer over real directories, the Android application's state and navigation, and the
desktop window — which is composed off screen in the test suite, so a screen that
fails to lay out fails the build.

What each layer is for, and the end-to-end acceptance procedure, are in
[docs/TESTING.md](docs/TESTING.md).

### Versioning and releases

Versions follow SemVer with the pre-1.0 rules in
[docs/VERSIONING.md](docs/VERSIONING.md); notable changes are in
[CHANGELOG.md](CHANGELOG.md); the release procedure is in
[docs/RELEASING.md](docs/RELEASING.md).

### Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. Two
things there are stricter than usual: one change per pull request, and no copying
code from this project's prior art.

### Security

Please do not open a public issue for security problems. See
[SECURITY.md](SECURITY.md). The LAN protocol is unencrypted and unauthenticated
today, so it is intended for a trusted network until authentication is designed —
that limitation is deliberate and recorded in the changelog.

### License

Apache License 2.0 — see [LICENSE](LICENSE).

Third-party attributions and prior-art credit: see [NOTICE](NOTICE).

---

### Credits and Prior Art

CubSync is written from scratch and contains **no copied source code**. Its design
was informed by earlier work, which we credit here as a statement of fact:

- **[HybridFileXfer](https://github.com/weixiansen574/HybridFileXfer)** (GPL-3.0)
  — informed the core concept: moving files between Android and desktop over a local
  network, and the dual-pane browsing model. No code, files, or protocol definitions
  were copied, ported, or derived. CubSync is therefore not a derivative work of
  HybridFileXfer.
- **FolderSync Pro** — information architecture for multi-pair configuration and job
  history. Design inspiration only.
- **Material Files** — Android file-browser interaction conventions. Design
  inspiration only.
- **FreeFileSync** — the glob dialect users of this class of tool already know, and
  the idea of a per-pair snapshot database. Behavioural reference only.

Trademarks belong to their respective owners; see [NOTICE](NOTICE).

### Differences from prior art

| | HybridFileXfer | CubSync |
|---|---|---|
| Purpose | file transfer | file **synchronization** |
| Direction | one-way, server-driven | bidirectional, peer-to-peer |
| Protocol | first-party only | pluggable backends (SPI) |
| Desktop | command line | GUI (Compose Multiplatform) |
| Android | — | GUI (Compose, Material 3) |
| Resume | limited | staging file + explicit resume offset + snapshots |
| Deletions | — | propagated with a recoverable trash |
| License | GPL-3.0 | Apache-2.0 |

---

## 中文

[English](#english) · **中文**

**跨平台文件同步 —— 一个引擎，多种后端。**

CubSync 让你在自己的网络里，把自己的设备之间的文件夹保持同步。Android、桌面、
无头（守护进程）共用同一套代码，由同一个同步引擎驱动，而这个引擎不关心底下跑的是什么传输。

> 状态：**可用，但还早**。0.3.1 版有引擎、协议、两个后端、一个 CLI、一个 Windows 桌面应用
> 和一个 Android 应用，两端界面都做到了设计文档的细节程度。一个 tag 在同一版本号下产出每个
> 平台的包。线格式在小版本之间仍可能变 —— 见 [docs/VERSIONING.md](docs/VERSIONING.md)。

**▶ [在线体验界面原型](https://cubsync.github.io/demo/)** —— 两端界面，浏览器里直接点。
无需安装。下面截图来自真实应用；原型是设计稿的原尺寸。

![连接界面](docs/screenshots/connect.png)

*先说清这台机器要共享什么，再去连一台正在共享的机器。这两件事成立，别的才谈得上。*

---

### 它是什么

这是一个**文件同步**工具，不只是文件传输工具。你定义成对的文件夹，选定一个方向和一种比较
策略，CubSync 算出哪些不一样、以及该怎么处理。

| | |
|---|---|
| **方向** | 双向、镜像（单向）、仅更新 |
| **比较** | 时间戳 + 大小、内容哈希、仅大小 |
| **过滤** | 包含 / 排除 glob、大小与时间界限、按对覆盖 |
| **历史** | 每对的快照、冲突报告、失败对的单独重试 |
| **组织** | 多套配置，每套含多组文件夹对 |
| **续传** | 暂存文件加一个显式的续传偏移；中断的传输可以接着跑 |

### 一段话讲架构

同步引擎只跟 `RemoteBackend` 接口对话。局域网传输（CubSync 自己的分帧协议）、WebDAV、
SMB、SFTP、FTP 和本地文件系统，都只是这个接口的实现。引擎和界面并不知道正在用的是哪一个，
所以新增一个后端碰不到它们。只有第一方的局域网协议支持并行通道。

更多细节，包括那些容易做错的决策，在 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

### 平台

| 目标 | 状态 |
|---|---|
| 无头 CLI + 守护进程 | 可用（JVM 17+） |
| 桌面 —— Windows（Compose Multiplatform） | 可用；MSI 与应用镜像 |
| 桌面 —— macOS / Linux | 可从源码构建；尚无打包产物 |
| Android（Compose + Material 3） | 可用；从发布页侧载 APK |

单一语言代码库：每个目标都用 **Kotlin**（Kotlin Multiplatform + Compose Multiplatform）。
`core` 本身已经是多平台源集，所以 Android 与桌面目标只是新增，而不是重构。

### 仓库结构

```
core/           协议 · 模型 · 过滤 · spi · 传输 · 引擎 · 工具
backends/local/ 把文件系统当作一个后端
backends/lan/   通过第一方协议访问对端的目录树
service/        应用层：配置、对端、运行、文件夹服务、日志
app/desktop/    桌面应用（Compose Multiplatform）
app/android/    Android 应用（Compose、Material 3）
cli/            命令行入口
testing/        仅测试用的夹具（内存后端、故障注入器）
docs/           架构、版本、测试、发布
```

### 构建

需要 JDK 17 或更新。Gradle wrapper 已入库；不要用系统装的 Gradle。

```
./gradlew check          # 编译 + 测试，CI 跑的就是这个
./gradlew :cli:installDist
./cli/build/install/cubsync/bin/cubsync --version
```

直接从源码树跑桌面应用：

```
./gradlew :app:desktop:run
```

或者构建一个能交给别人的东西：

```
./gradlew :app:desktop:packageMsi            # 一个安装包
./gradlew :app:desktop:createDistributable   # 一个自带 CubSync.exe 的目录
```

打 MSI 会让构建去下载 WiX，所以这一步第一次需要联网。

Android 应用需要 SDK（API 36 与 build-tools 36），通过 `ANDROID_HOME` 或带 `sdk.dir` 的
`local.properties` 指定。它是唯一需要 SDK 的模块；其它模块只要一个 JDK 就能构建。

```
./gradlew :app:android:assembleDebug        # app/android/build/outputs/apk/debug/
./gradlew :app:android:installDebug         # 直接装到已连接的设备上
```

目前还没有签名的 release 构建：那需要密钥库，而密钥库是一个关于分发方式的决定，不是一个构建
步骤。每次发布附带的 APK 是 debug 包，正常侧载即可。

### 桌面应用怎么用

侧栏六项，按设计稿的顺序。第一项是唯一必须先弄懂的，别的才谈得上。

![连接界面](docs/screenshots/connect.png)

**连接** —— 先选运行模式，看有哪些通道可用，再选这台机器要共享的文件夹，按「开始共享」；
要在另一台机器上输入的地址就出现在下面。往外连是另一半，而且互相独立：向对端单向复制不需要
在本地共享任何东西，共享一个文件夹也不需要往外连过。账号及其状态、最近几次传输，都在同一屏。

**传输** —— 两个文件夹和它们中间的那个箭头，产品就是以此命名的。「显示方式 ▾」打开一条通栏，
装着全部五种模式；「行密度」是可输入的百分比；网格模式下有「列数」；还有「分栏方向」和
「条目缩放」；它作用于你最后碰过的那一栏。每一栏的 ⋯ 里放它的排序、「文件夹优先」和三层
「作用范围」—— 而每一栏的栏头**就是**它的地址：每一层都可点，中间那层折成 `⋯`，点空白处
把它变成一个可以输入路径的框。

![传输界面](docs/screenshots/transfer.png)

**同步** —— 一套配置 = 一条策略 + 一列文件夹对。左边是列表，右边是配置：「同步方向」「比较
方式」「冲突」「删除」「并行度」，然后是各对文件夹，再是「过滤」。「预览」只做比较并给出摘要，
不写任何东西；「试运行」做同样的事并记录下来；「立即同步」才真干活。

**作业** —— 每一次运行，最新在前。点开一次运行，它就是独立一屏：结果、十三行明细、按文件
列出传输了什么和跳过了什么及原因、这次运行自己的日志，以及它结果所允许的动作 ——「再次运行」
「重试失败对」或「报告」。

![作业界面](docs/screenshots/jobs.png)
![作业详情界面](docs/screenshots/job-detail.png)

**日志** —— 实时记录，可按级别和按区域过滤。桌面上它自己占一项，因为窗口放得下。

**设置** —— 设计稿列出的九项浏览与传输字段 ——「默认显示方式」「条目缩放」「显示元数据」
「栏头地址行数」「分栏方向」「缓冲块数」「默认接收目录」「断点续传」「同步删除走回收站」——
加上主题和强调色。同步策略刻意不在这里：它属于它所管辖的那套配置。

![设置界面](docs/screenshots/settings.png)

### 手机上

Android 应用有五个标签页 —— 桌面的六项减去「日志」，日志属于它来自的那次作业 —— 用同样的
措辞、同样的步骤数和同样的状态。这些都出自同一处（`service` 模块里的 `UiSpec`），所以两端
不可能把同一个设置描述成两样。

**连接** —— 运行模式卡片在最前，因为在手机上它是压在一切底下的那个问题；而且默认折叠，因为
没在跟权限搏斗的人不该被迫读权限。然后是通道 —— 这台机器实际有的每张网卡一个开关，附上它的
可达地址 —— 然后是共享，然后是账号及其状态，然后是最近几次传输。

![手机上的连接界面](docs/screenshots/phone-connect.png)

**传输** —— 同样的两栏，加上设计稿自己的副行：☰ 开抽屉，一个在「两栏」「只左栏」「只右栏」
之间的三态切换，「显示方式▾」，以及 ⋮。显示通栏挂在它下面，装着五种模式、「行密度」（可输入
的百分比）、网格模式下的「列数」、「分栏方向」和「条目缩放」。长按一行打开它的动作面板；一栏
的栏头就是它的地址，与桌面完全一致。

![手机上的传输界面](docs/screenshots/phone-transfer.png)

**同步** —— 配置列表，每个都用 `←` 展开：策略是四行、点开是一个列表，然后是各文件夹对，然后
是过滤，再然后「预览」自成一层。有六个取值的项做成一行而不是下拉框，因为下拉框是鼠标的交互；
两端的取值无论如何都是一样的。

![手机上的差异预览](docs/screenshots/phone-sync-preview.png)
![手机上的一次运行](docs/screenshots/phone-running.png)

**作业** —— 每一次运行，以及完整展开的一次运行：结果、十三行明细、按文件的列表、这次运行的
日志就地展示，以及它以什么动作收尾。「日志」是这个标签页的第二段，而不是一个独立标签。

**设置** —— 与桌面相同的九项，同样的顺序，同样的默认值，外观在其上。

![手机上的设置界面](docs/screenshots/phone-settings.png)

那五张图不是效果图：`PhoneScreenshotTest` 在 JVM 上把每个手机界面组合出来写成 PNG。见
[docs/TESTING.md](docs/TESTING.md)。

### 命令行怎么用

把一个文件夹共享到网络上：

```
cubsync serve --root /srv/photos --port 43817 --name nas
```

看看对端提供什么，然后往那边复制：

```
cubsync ls nas.local:43817
cubsync push --to nas.local:43817 --remote /srv/photos --from ./photos \
             --direction mirror-to --compare hash --parallel 4
```

方向默认是仅更新，所以第一次运行不可能删掉对端的任何东西；要镜像得明确要求。删除默认走
`recycle`，即把文件移进它来源的那个根目录下的 `.cub_trash`。

一次性传输没问题，但这个工具的重点是保存下来的配置：多组文件夹对、一条策略、每对一个历史，
这样一次双向运行才能分清「删除」和「新文件」。

```
cubsync config new --name "photos and docs" --direction two-way
cubsync config add-pair <id> --left local --left-root ~/photos \
                             --right nas.local:43817 --right-root /srv/photos
cubsync config add-pair <id> --left local --left-root ~/docs \
                             --right phone.local:43817 --right-root /sdcard/Docs \
                             --filter '*.tmp' --name "documents"
cubsync config show <id>
cubsync run <id>
```

`cubsync --help` 列出所有选项。`--json` 打印一份机器可读的摘要，用的是文本输出所展示的同一份
结果；每一对都跑完时退出码为 0，有没跑完的为 1，命令行写错为 2。

配置及其按对的快照默认放在 `~/.cubsync` 下，可用 `--state-dir` 挪走；`cubsync config export`
写出的 `.cubcfg` 可以在另一台机器上导入。

### 测试

```
./gradlew check
```

覆盖仓库里的每一套件：协议编解码及其畸形输入、过滤方言、比较与规划的矩阵、分块重排、配置的
编解码、两个后端对真实 socket 和真实文件、引擎驱动一个与本地后端不共享任何代码的内存后端、
应用层跑在真实目录上、Android 应用的状态与导航，以及桌面窗口 —— 它在测试里被离屏组合出来，
所以一个布局坏掉的界面会让构建失败。

每一层是干什么的，以及端到端验收流程，在 [docs/TESTING.md](docs/TESTING.md)。

### 版本与发布

版本遵循 SemVer，1.0 之前的规则见 [docs/VERSIONING.md](docs/VERSIONING.md)；值得注意的改动
在 [CHANGELOG.md](CHANGELOG.md)；发布流程在 [docs/RELEASING.md](docs/RELEASING.md)。

### 参与贡献

开 pull request 之前请先读 [CONTRIBUTING.md](CONTRIBUTING.md)。其中两条比通常更严：一次
pull request 只做一件事，以及不得从本项目的先行技术里复制代码。

### 安全

安全问题请不要开公开 issue，见 [SECURITY.md](SECURITY.md)。局域网协议目前是不加密、不鉴权
的，所以在鉴权设计出来之前，它面向的是可信网络 —— 这个限制是有意为之，并记录在变更日志里。

### 许可

Apache License 2.0 —— 见 [LICENSE](LICENSE)。

第三方署名与先行技术致谢：见 [NOTICE](NOTICE)。

---

### 致谢与先行技术

CubSync 从零写起，**不含任何抄来的源码**。它的设计受以下更早的工作启发，这里作为事实陈述致谢：

- **[HybridFileXfer](https://github.com/weixiansen574/HybridFileXfer)**（GPL-3.0）
  —— 启发了核心概念：在局域网里于 Android 与桌面之间搬文件，以及双栏浏览模型。没有复制、
  移植或派生任何代码、文件或协议定义。因此 CubSync 不是 HybridFileXfer 的衍生作品。
- **FolderSync Pro** —— 多对配置与作业历史的信息架构。仅设计启发。
- **Material Files** —— Android 文件浏览器的交互惯例。仅设计启发。
- **FreeFileSync** —— 这类工具的用户已经熟悉的 glob 方言，以及「按对的快照数据库」这个想法。
  仅行为参考。

商标归各自所有者；见 [NOTICE](NOTICE)。

### 与先行技术的差异

| | HybridFileXfer | CubSync |
|---|---|---|
| 目的 | 文件传输 | 文件**同步** |
| 方向 | 单向，服务端驱动 | 双向，点对点 |
| 协议 | 仅第一方 | 可插拔后端（SPI） |
| 桌面 | 命令行 | 图形界面（Compose Multiplatform） |
| Android | — | 图形界面（Compose、Material 3） |
| 续传 | 有限 | 暂存文件 + 显式续传偏移 + 快照 |
| 删除 | — | 经可恢复的回收站传播 |
| 许可 | GPL-3.0 | Apache-2.0 |

# Design

Why the interface looks the way it does, and what the words mean. This is the part of the
design that belongs in the open: it explains what you see in the application and what a
change to it should respect. The prototype, the specifications and the reasoning behind
unreleased work are kept with the design notes and are not published.

## Two ends, one product

The desktop and the phone are the same application. They share one engine, one set of
behaviours and one vocabulary, and where they differ it is because a mouse and a thumb
differ — never because the concepts differ.

| | Desktop | Phone |
|---|---|---|
| Navigation | six entries in a rail | five in a tab bar, the log folded into its job |
| A second level | opens beside, in the same window | opens over, with `←` back |
| A control with parameters | a drop-down from the heading | a bar that unfolds under the row |
| Acting on one row | a menu beside it | a long press |
| Choosing several | a selection that stays | a selection that raises a bar along the bottom |

The rule underneath: **the same capability may have a different *home* on each end, but its
name, its number of settings and what its states mean must be identical word for word.** A
reader who knows the desktop should not have to learn the phone.

## What things are called

These are the interface's own words. They are Chinese in the product, and they are the
whole vocabulary — anything not in this table is not a concept in this product.

| Concept | Name | Not |
|---|---|---|
| The two machines | 本机 / 远端 | 手机、电脑、本地、服务端、对端 |
| Process roles | 服务端（主控）/ 客户端（从属） | server, client |
| The five ways to draw a listing | 紧凑 / 详细 / 卡片 / 缩略 / 对比 | 列表、网格、图标视图 |
| Row height | 行密度, a percentage `− ［ ］% +` | 紧凑/标准/宽松 |
| How the panes are arranged | 分栏方向: 自动 / 左右 / 上下 | 横排、竖排 |
| How far a preference reaches | 作用范围: 当前目录 / 本栏全局 / 两栏共用 | 全局、局部 |
| Transfer settings | 缓冲块数 · 断点续传 · 默认接收目录 · 同步删除走回收站 | 缓存、续传、下载目录 |
| Directories it creates | `.cubtmp` · journal · `.cub_trash` · 回收站 | 临时目录、垃圾箱 |

A preference written to a broader scope is read only after every narrower one has been
consulted, and writing it **clears the narrower records it would otherwise be hidden
behind**. That sentence is the whole reason 作用范围 has three settings instead of two: a
folder that behaves differently is a choice somebody made, and it has to be possible to
unmake it.

## The rules a change has to keep

- **The prototype is where the numbers come from.** Colours, type sizes, radii, animation
  durations, hit areas and icon paths are read from it rather than chosen. The written
  specification classifies and explains them; it does not hold numbers of its own, and it
  does not outrank the prototype.
- **When the two disagree, both are changed together**, in the same commit, with the reason
  written down. What is never acceptable is changing one and leaving a comment calling the
  difference deliberate.
- **No control that does nothing.** A feature that is not built is not drawn. A capability
  the protocol promises but this build has not wired up is drawn, and says so when tapped —
  it is never silently absent, and it never pretends to work.
- **Icons are paths, never characters.** `☰`, `⋮`, `★` and their relatives render as boxes
  in a font that has no glyph for them, which is how the phone's tab bar once shipped
  empty. Every mark is an `ImageVector` built from the prototype's own path data.
- **Nothing is drawn under the system bars.** The phone keeps clear of the status bar and
  the gesture bar; the desktop window draws its own title bar, because a native one does not
  follow the theme.
- **Light, fast, small.** No dependency that is not on a code path, no unused module in the
  runtime image, no feature that costs memory to be idle.

## Where to look

- `docs/ARCHITECTURE.md` — the modules, what is shared, and the three decisions worth knowing
- `docs/TESTING.md` — the layers of the test suite and what each one is for
- `docs/VERSIONING.md` — one version for every platform, and what counts as a breaking change
- `docs/RELEASING.md` — how a release is cut, and why a tag publishes a draft
- `docs/SIGNING.md` — how to tell that a build is ours

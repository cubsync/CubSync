# Architecture

## Modules

```
core/            protocol · model · filter · spi · transfer · engine · util
backends/local/  the filesystem as a RemoteBackend
backends/lan/    a peer's tree as a RemoteBackend, over the first-party protocol
service/         the application layer: configs, peers, runs, the folder server, the log
app/desktop/     the desktop application (Compose Multiplatform)
cli/             the command line entry point
testing/         test-only fixtures: an in-memory backend and a fault injector
```

Dependencies point one way: the front ends and the backends depend on `core`, and
`core` depends on nothing in this repository. The engine never imports a backend,
which is what makes "add WebDAV without touching the UI" a statement of fact rather
than a hope. Neither front end imports a backend either; both go through `service`.

Everything except the two backends, the application layer and the front ends lives
in `commonMain`, so the Android target can be added to `core` without moving code.

## The application layer

`service` exists because there were about to be two front ends. The first thing a
second front end does is copy the first one's orchestration — which pool to open,
where snapshots live, which roots to register, what to log — and that copy is how
two front ends start disagreeing about what a sync is.

It holds:

- `ConfigStore` — configurations and per-pair snapshots under one state directory.
- `EndpointId` — one string that names a side of a pair, and one rule for reading it.
- `BackendPool` — a session per peer, reused across calls and replaced when its
  socket has died.
- `SyncService` — the single path that runs a configuration, with an observer for
  progress and a log for the record.
- `ServerController` — the only place that opens a listening socket, and the only
  place that has to be careful about what it exposes.
- `AppLog` — a bounded in-memory log with levels and areas.

The rule that keeps it honest: a front end may format what `service` returns, and
may not decide anything about how a sync happens.

## The three decisions worth knowing

**Backends are an interface, not a protocol.** `RemoteBackend` exposes list, stat,
hash, mkdir, delete, rename, setMtime, freeSpace, openRead and openWrite, and
declares what it can do through capability bits. The engine asks what is allowed and
degrades: a backend without hashing gets the timestamp comparison instead of content
comparison, and says so.

**Two connections, not one.** The LAN protocol puts commands on one TCP connection
and file payloads on another. A 1 MiB data frame queued in front of a progress
report is how a transfer makes its own UI look hung; the split removes the
possibility rather than tuning around it.

**A write is not finished until it is committed.** A transfer writes to a staging
file next to the target, then makes the data durable, then renames it into place,
and only then applies the timestamp. The order is the point: a rename that lands
before the data does leaves a truncated file under the correct name after a crash.
The staging name is deterministic, so the next attempt finds the partial bytes and
continues from there.

## Cancellation

The design document this project started from specified a `CancellationSignal`
parameter on every backend operation. The implementation uses coroutine cancellation
instead: it is the same idea, already available, already propagating through the
call chain, and it does not add a parameter to every method of the SPI. A blocking
implementation cooperates by checking for cancellation between chunks, which the
engine does on every chunk boundary.

## Deletion semantics

`ActionKind.DELETE_LEFT` means "remove the file on the left", and it is produced for
a path that only the *left* still has when the snapshot says the two sides agreed on
it before. The side that still holds the file is where the deletion is applied; the
side that made the deletion is the side that no longer has it. Getting this backwards
deletes from a side that has nothing to delete, which is how a two-way sync ends up
reporting failures for files it never touched.

With no snapshot there is no evidence about which side deleted anything, so the
engine assumes an addition. That is the guess that cannot lose data.

## Filters

A filter has an explicit `enabled` flag. The earlier design used "an empty filter" to
mean "no filtering for this pair", which does not hold: an empty filter still
excludes hidden entries. Three states are now distinguishable — inherit, replace, and
off — and the third one means what it says.

## Where a real backend has to be careful

- **Listing never shows the backend's own bookkeeping.** `.cubtmp` staging files and
  the `.cub_trash` tree are hidden from listings. A peer that could see a staging
  file would faithfully try to synchronise a half-written file.
- **Roots are checked after normalisation.** A peer may only name a root the server
  was started with, and a path inside it is normalised before use, so `a/../../etc`
  is refused as surely as `..`.
- **Errors carry a code.** A raw `IOException` escaping the SPI aborts a whole pair;
  every failure is converted into a `BackendException` with a protocol error code.
- **Streams are sequential.** `openWrite` reports the offset it will start from, and
  everything before that offset is already durable and must not be rewritten.

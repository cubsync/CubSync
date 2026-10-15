# Versioning

CubSync follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html) with the
pre-1.0 relaxations spelled out below, because "we might change anything" is not a
useful promise to make to someone syncing their files with it.

## Where the version lives

One place: `cubsync.version` in [`gradle.properties`](../gradle.properties). Every
module takes it from there, the Gradle distribution name and the CLI's `--version`
both read it, and the release procedure does not touch any other file. A version
that has to be edited in five places is a version that will disagree with itself.

The CLI falls back to `BuildInfo.DEFAULT_VERSION` when it is not running from a
jar that carries manifest metadata. That constant is the one exception, and the
release checklist reminds you to check it.

## Pre-1.0 rules

While the major version is `0`:

- **Minor** (`0.1.0` → `0.2.0`): new capability, or any change to the wire protocol
  or the engine's public API. Anything a user of the library could notice.
- **Patch** (`0.1.0` → `0.1.1`): a fix that changes no interface.
- The protocol has its own integer, `Protocol.VERSION`. It stays at 1 until the
  framing or a payload shape changes, and it is what a peer compares during the
  handshake — so a change there is a **minor** bump of the product version, and the
  changelog has to say so.

## What counts as breaking

| Change | Bump |
|---|---|
| Frame layout, command number, payload shape, error code meaning | minor, and `Protocol.VERSION` |
| `RemoteBackend` method added, removed or re-signatured | minor |
| `SyncConfig` field removed or its meaning changed | minor, plus a schema migration |
| Configuration schema version | minor; older files must still load |
| Snapshot file format | minor; a mismatch must be treated as "no history", never as "everything is new" |
| Bug fix with no interface change | patch |
| Documentation, comments, tests | patch |

Renumbering an existing command is breaking. Adding one is not — as long as it is
new, because a command number is a name that peers have already learned.

## Compatibility promises

- **Protocol**: peers of different versions can talk. The handshake takes the lower
  version and the intersection of the capabilities, so an old peer keeps working and
  simply does less. A peer that announces a *newer* version is refused with a
  message saying so, rather than being half-understood.
- **Configuration**: a file written by an older build always loads. A file from a
  newer build is refused by name, so the user is told to update rather than quietly
  getting half of their settings.
- **Snapshots**: losing a snapshot loses history, not data. The next run is treated
  as a first synchronisation, which never deletes anything it cannot explain.
- **CLI**: command names, option names and exit codes are part of the interface.
  Exit code 0 means every pair finished, 1 means something did not, 2 means the
  command line was wrong.

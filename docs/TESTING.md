# Testing

## Running the suite

```
./gradlew check          # everything: compile, unit tests, module checks
./gradlew test           # test tasks only
./gradlew :core:jvmTest  # one module
```

`check` is what CI runs. Test reports land in `<module>/build/reports/tests/jvmTest/index.html`.

## The layers, and what each one is for

**Pure unit tests** (`core/src/commonTest`) cover everything that is a function of
its arguments:

| Area | What is proven |
|---|---|
| Frame codec | Round trips for every frame type; payload sizes at 0, 1, 2, 255, 256, 65535 and 1 MiB; and every malformed input — bad magic, truncated header, truncated payload, an over-long declared length, unknown version, unknown frame type — each with its own error code. 10 000 randomised round trips plus 20 000 malformed buffers, asserting that nothing escapes except a `ProtocolException`. |
| Handshake | The version matrix as a table: same, older, newer, zero, negative, and a peer offering capability bits this build has never heard of. |
| Filters | Glob semantics, including the two rules that are easy to get wrong: a trailing `/**` also matches the directory itself, and a whitelist never prunes a directory by name. |
| Comparison | Three comparison modes against equal, one-sided, newer, different, type-mismatch and case-collision inputs, plus the snapshot-driven conflict rule and rename detection. |
| Planning | Five directions × eight classifications as a table, then the policies on top: `KEEP` suppresses deletions, `RECYCLE` versus `PROPAGATE`, and the five conflict policies. |
| Chunk reordering | In-order, out-of-order, every permutation of four chunks, duplicates, a stream missing its final chunk, and two independent streams. |
| Configuration | Round trip, the three filter states, export envelope, unknown-field rejection on load, tolerant parsing on import, a newer schema refused by name, and unknown enum values falling back rather than failing. |
| Hashing | Hard-coded MD5 and SHA-256 vectors, so a change of hash semantics fails here instead of corrupting somebody's files. |

**Contract tests** (`testing/src/commonTest`) run the engine against a second
`RemoteBackend` — an in-memory one that shares no code with the local one. Anything
the engine gets right against both is a property of the engine rather than of a
filesystem. This is where the resume path is exercised: a fault injection fails a
write at a chosen chunk, and the test asserts what was left behind and that the next
run completes with identical content.

**Filesystem tests** (`backends/local/src/jvmTest`) cover what only exists on a real
disk: staging files, atomic replacement, the timestamp applied last, a staging file
that belongs to a different file being discarded, `.cubtmp` and `.cub_trash` never
appearing in a listing, path-traversal refusal, and a full pair synchronisation with
a recycled deletion.

**Protocol tests** (`backends/lan/src/jvmTest`) run a real server on a real socket
with an in-memory backend behind it: metadata commands, a 1.5 MB round trip byte for
byte, reading from an offset, resuming a write, a root that was not exposed, a path
that climbs out of one, a peer speaking a newer protocol, an unknown command
answered by name with the link still usable afterwards, and the engine driving a
peer across the wire including parallel transfers.

**Application tests** (`service/src/test`) run the layer both front ends sit on,
over real directories and a real socket: a configuration round trip, the folder
server exposing exactly what it was given and nothing else, a run that copies real
files, a peer that cannot be reached failing one pair instead of throwing, and a
port that is already in use reported in words somebody can act on.

**Window tests** (`app/desktop/src/test`) drive the state object the way a person
drives the window — start a run, watch it finish, read the result — and render
every screen off screen. The rendering is not decoration: a screen that cannot be
composed fails the build here instead of being discovered by whoever opens the
window next. The images land in `app/desktop/build/screenshots`.

**Android tests** (`app/android/src/test`) cover the phone's state and navigation:
that a detail is a place and the back arrow leaves it, that the guards refuse what
they should before anything is opened, and that a run started from the state object
moves real files. They run on the JVM, so they cannot prove that an API exists on a
device — which is exactly the class of bug that shipped `Files.readString` in 0.3.0
and was only caught by building the Android application.

They also render every phone screen off screen. `PhoneScreenshotTest` drives a real
composition under Robolectric (`@GraphicsMode(NATIVE)`) and draws the window into a
bitmap itself, because Compose's `captureToImage` waits on a window traversal that
never happens off a device. The images land in `app/android/build/screenshots-phone`,
and they are the only way a phone layout gets looked at before somebody installs an
APK — which is how a release once shipped with the whole bottom bar missing its icons.
The test is debug-only: it needs `ui-test-manifest` to declare `ComponentActivity`, and
that manifest is debug-only on purpose.

**CLI tests** (`cli/src/test`) cover argument parsing, the quirks of flags that take
no value, and the summary format.

## End-to-end acceptance

The acceptance for the first milestone is a real transfer between two processes,
not a unit test. `!tmp/e2e/run.sh` (kept outside the repository) does this:

1. Builds the CLI distribution and creates a 1 GiB file plus a small tree.
2. Starts `cubsync serve` as its own process, bound to loopback.
3. Runs `cubsync push --direction mirror-to` against it.
4. Compares MD5 sums of the source and the received file.
5. Checks the deleted file was recycled rather than unlinked, that no `.cubtmp`
   file is left behind, and that a second run reports nothing to do.

Recorded result for 0.1.0:

```
push_exit=0 elapsed_ms=8335        (8537 ms including JVM startup)
engine time: 6819 ms
sender   md5=b6fe82679bf3e9ab2127afd0efb5039e
receiver md5=b6fe82679bf3e9ab2127afd0efb5039e
MD5 MATCH
staging leftovers: 0
second run: 0 copied, 0 B, 0 deleted
```

That is about 150 MiB/s including the verification pass that re-reads the file to
compare its hash, over one data connection. The figure is a floor, not a claim:
multi-channel aggregation is not implemented yet.

## Adding a test

- If the behaviour is a matrix, write it as a table and loop. A row per case makes
  the missing cases obvious during review.
- If a test needs a backend, use the in-memory one unless the test is specifically
  about the filesystem.
- If the test is about failure handling, use `FaultInjectingBackend` and assert what
  was left on disk, not just that an exception was thrown.
- Do not assert on wall-clock durations; assert on content, on what was left behind
  and on counters.

# Signing

An Android application is signed by a key. The key is not a build setting: it is an
identity, and Android treats a build signed by a different identity as a different
application — it cannot replace an installed one, only sit next to it or force the
user to uninstall first.

That is the whole reason this document exists. Everything else about a release can be
regenerated from the repository; this cannot.

## Why the debug APK was wrong to ship

Every release before this one carried the debug APK. A debug key is generated
automatically, and on CI it was generated on a fresh runner for every build, so each
release was signed by a **different** key. The consequence is not visible to somebody
installing for the first time; it is visible to somebody upgrading, who has to uninstall
— and lose their configuration — before the new build will install.

A single, stable key is the fix, and it has to exist before the next release rather than
after it.

## The four values

| Value | What it is | Environment variable | `keystore.properties` key |
|---|---|---|---|
| The keystore file | A `.jks` holding the private key | `ANDROID_KEYSTORE_FILE` | `storeFile` |
| Keystore password | Unlocks the file | `ANDROID_KEYSTORE_PASSWORD` | `storePassword` |
| Key alias | Names the key inside it | `ANDROID_KEY_ALIAS` | `keyAlias` |
| Key password | Unlocks the key | `ANDROID_KEY_PASSWORD` | `keyPassword` |

The environment wins when set, so CI needs no file; `keystore.properties` in the
repository root is the local fallback. `keystore.properties` is gitignored, and so is
every keystore extension (`*.jks`, `*.keystore`, `*.p12`, `*.pfx`, `*.pepk`, `*.pem`,
`*.key`). `keystore.properties.example` is the one that is committed, so a new checkout
can see which values are expected without seeing any of them.

If neither source is present the build still succeeds. `assembleRelease` produces an
unsigned APK and the release workflow falls back to the debug APK with a warning. A
fresh clone should not fail because it does not hold the key.

## Creating the identity

Once per application, and never again for that application:

```
keytool -genkeypair \
  -keystore cubsync-release.jks \
  -alias cubsync \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=CubSync, OU=CubSync, O=CubSync, L=, ST=, C=CN" \
  -storetype PKCS12
```

- **`-validity 10950`** is thirty years. Google requires a validity that outlives the
  application's expected life on the store — the old advice of 25 years is the floor,
  not the target.
- **`-storetype PKCS12`** is the current default and the only one worth choosing; JKS
  is legacy.
- `keytool` prompts for both passwords. Use the same one for both unless you have a
  reason not to: they are stored together either way, and two passwords is one more
  thing to lose.

The workspace script `脚本/new-signing-identity.py` wraps this, writes the
`keystore.properties` beside it, and prints the base64 blob CI needs. It prompts for the
password rather than taking it as an argument, so it does not end up in a shell history.

Run it from this directory with no arguments — the application name comes from the
directory name and the repository from `git remote origin`, so there is nothing to fill
in. `--dry-run` prints the values it would use and writes nothing.

```
py ../脚本/new-signing-identity.py --dry-run   # look
py ../脚本/new-signing-identity.py             # then make it
```

## Where the keystore lives

Not in the repository, and not only on the machine that built it. Two copies, in two
places:

1. **A password manager**, attached to a secure note that also carries the four values
   as text. Losing the password and keeping the file is the same as losing both.
2. **An offline archive**, because a password manager is one account away from being
   unreachable and this file cannot be replaced.

The same is true of a release-signed APK: keeping the key and the APK means a future
store migration or a signing-key rotation stays possible.

## CI

The release workflow reads four repository secrets. The keystore is base64 because a
secret is text and a keystore is binary:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 cubsync-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | `cubsync` |
| `ANDROID_KEY_PASSWORD` | the key password |

With `ANDROID_KEYSTORE_BASE64` unset, the workflow warns and uploads the debug APK, so a
fork can still produce something installable without holding the key.

## Verifying what was built

```
# Which key signed it, and is it the one you think?
apksigner verify --print-certs CubSync-0.3.6.apk

# Is it the same key as the previous release?
apksigner verify --print-certs CubSync-0.3.5.apk
```

`apksigner` ships in the Android SDK at `build-tools/<version>/apksigner`.

**Not `keytool -printcert -jarfile`.** That reads JAR signatures (the v1 scheme), and
these APKs are signed with the v2/v3 scheme, whose signature lives in the APK Signing
Block instead. Against a v2/v3-only APK, `keytool -printcert` prints nothing at all —
which reads like a pass rather than a check that did not happen. The v1 scheme was
dropped because `minSdk` is 26, and no current Android needs it.

Compare the `certificate SHA-256 digest` lines; they must match. Two releases with
different digests are two applications as far as Android is concerned, and the second
cannot replace the first.

Locally, `./gradlew :app:android:signingReport` prints the configuration the build
resolved, which is the quickest way to confirm that `keystore.properties` is being read
at all. It prints `Config: null` for the release variant when no identity was found.

## Changing the key

There is no in-place rotation. A new key means every existing installation has to be
uninstalled and reinstalled, so the time to choose the key is before the first release
that anybody keeps. If the key is lost, that is the only path — which is why the backup
in the section above is not optional.

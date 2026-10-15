# Security Policy

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Report privately using GitHub's [private vulnerability reporting][advisory]
(Security tab → "Report a vulnerability"). If that is unavailable, contact the
maintainer directly through the contact address on the organization profile.

Please include:

- A description of the issue and its impact
- Affected component (engine, a specific backend, Android app, desktop app, daemon)
- Steps to reproduce, or a proof of concept
- Any suggested fix

Do not include live credentials, private keys, or personal file paths in the report.

[advisory]: https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability

## What to expect

This is a small project without a paid support contract. There is no SLA.
Reports are read as soon as possible, typically within a few days. You will get
an acknowledgement, an assessment, and — for confirmed issues — notice when a
fix ships and credit in the release notes unless you ask to remain anonymous.

Please allow reasonable time for a fix before public disclosure.

## Scope

In scope:

- The synchronization engine and its persistence layer
- The LAN transport protocol (framing, authentication, path handling)
- Backend implementations (path traversal, credential handling, TLS verification)
- Android application, including storage permission and root-mode handling
- Desktop application and the headless daemon

Out of scope:

- Vulnerabilities in third-party dependencies — report those upstream. If the
  impact is specific to how CubSync uses them, tell us anyway.
- Anything requiring physical access to an unlocked, already-compromised device
- Self-inflicted misconfiguration, such as exposing a daemon to the public
  internet without authentication
- Missing hardening that has no demonstrated impact

## Threat model

CubSync is designed for use on a **local network you control**, or over a
network you have already secured (VPN, tunnel). On that assumption:

- Transfers are expected to be protected by the transport when it supports it
  (for example WebDAV over HTTPS, SFTP over SSH). The first-party LAN protocol
  is designed for trusted networks; do not expose it directly to the internet.
- A party that is already on the same local network is not treated as fully
  trusted. Path traversal, symlink escapes, and unauthorized write outside
  configured folder pairs are all in scope and count as vulnerabilities.
- Deletion and overwrite operations must never occur outside the scope of a
  configured folder pair, including when filters and conflict rules are in play.

## Handling of secrets

- Signing keys, keystore files, and credentials live in CI secrets or a local
  keystore, never in the repository. `.gitignore` covers the usual names; if you
  notice a secret that has been committed, report it privately.
- The Android build's release signing configuration is supplied through
  environment variables and is never checked in.

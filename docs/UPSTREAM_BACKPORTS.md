# Upstream backport attribution

Vector-SR is a fork of [JingMatrix/Vector](https://github.com/JingMatrix/Vector). This file records selective upstream backports that are adapted onto the Vector-SR codebase while preserving the legacy View-based Manager and the SR soft-restart/reinjection architecture.

## Attribution policy

For future selective backports:

- Direct cherry-picks should retain Git's `-x` provenance whenever possible.
- Manual or conflict-resolved ports must include the original upstream repository and commit SHA in the commit message.
- When a change is only partially adopted, the commit message and this table should state which semantics were ported and which were intentionally excluded.
- Vector-SR-specific adaptations must not be presented as verbatim upstream changes.
- New Compose Manager / Manager IPC namespace changes remain outside this backport series unless explicitly adopted later.

Recommended commit trailers for adapted changes:

```text
Upstream-Repository: JingMatrix/Vector
Upstream-Commit: <full SHA>
Upstream-URL: https://github.com/JingMatrix/Vector/commit/<full SHA>
Adaptation: <why a direct cherry-pick was not used>
```

## Current backport map

| Upstream commit | Vector-SR status | Notes |
| --- | --- | --- |
| `077131dba53b2be6d4962c3527fcf4f57ebe1241` | Ported | Android 15/16 SystemUI notification-flag workaround. |
| `28daecfa3b30ea21e1a7e33feae6545552acaf04` | Partially ported | Daemon reads verbose-log preference before dropping the main-thread euid. Log-only cleanup is not required for behavior. |
| `73571c7f8243295ddd2d5de62cffb4f6cbf5c3dc` | Ported | Android 17 `IServiceConnection.connected` 4-argument overload and `IBinderSession` stub. |
| `fbcff2f5087209e5667c40f45f2a8ac7a7a6f2b6` | Ported/adapted | Legacy unsafe-hook refusal was blob-compatible; modern `VectorNativeHooker` was manually adapted to preserve SR API102 work. |
| `92f3e60ea9504c9064cc3bca287456d196a5937c` | Partially ported | JNI exception hygiene is present in shared native helpers; Zygisk call-site logging and dex2oat `GetStringUTFChars` OOM edge remain to be completed. |
| `73eba3ac0b6897144be8487e2a86c71449558949` | Runtime portion ported | `XResources` lambda removal is present. Build-time isolation guard remains pending. New-Manager logging/archive changes are intentionally excluded. |
| `44552398db793a6d02b33acbc66978966950ffef` | Pending manual adaptation | Android 17 static-final field support must be merged into SR's API102-modified native hook bridge rather than cherry-picked wholesale. |
| `5ff67a87cb70414d8c52b59f97f56a9de257ee49` | Pending manual adaptation | `system_server` native-library staging conflicts with SR FileSystem/reinjection responsibilities and must be isolated from the soft-restart lifecycle. |
| `04093fdbf4cff2426cd1b320d5e568282d0e2a77` | Pending semantic audit | API101 conformance bundle will be ported item-by-item to avoid regressing SR's API102 implementation. |
| `abae837a50d3a50577e4a458e2bbf57e64312af3` | Pending semantic alignment | Canonical API102 implementation; native-level atomic hook replacement is the highest-priority missing semantic. |
| `4fcea0e528e737efb5fdadf02ee7fd47d55d527b` | Pending semantic alignment | API102 runtime scope-request follow-up. |
| `e8bec6bd714d0f277c8134096501ce36902b32fd` | Pending semantic alignment | Module app/provider lifetime follow-up. |

This table is intentionally conservative: an entry is marked `Ported` only when the relevant behavior exists on the Vector-SR backport branch; it does not claim source-level identity unless explicitly stated.

# Upstream backport attribution

Vector-SR is a fork of [JingMatrix/Vector](https://github.com/JingMatrix/Vector). This file records selective upstream backports adapted onto Vector-SR while preserving the legacy View-based Manager and the SR soft-restart/reinjection architecture.

## Attribution policy

For future selective backports:

- Direct cherry-picks should retain Git's `-x` provenance whenever possible.
- Manual or conflict-resolved ports must include the original upstream repository and commit SHA in the commit message.
- When a change is only partially adopted, the commit message and this table must state which semantics were ported and which were intentionally excluded.
- Vector-SR-specific adaptations must not be presented as verbatim upstream changes.
- The Compose/new Manager and the `org.matrix.vector.ipc` Manager/Binder namespace remain outside this backport series unless explicitly adopted later.

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
| `28daecfa3b30ea21e1a7e33feae6545552acaf04` | Behavior ported | Daemon reads verbose-log preference before dropping the main-thread euid. Upstream log-only cleanup is intentionally not required. |
| `73571c7f8243295ddd2d5de62cffb4f6cbf5c3dc` | Ported | Android 17 `IServiceConnection.connected` four-argument overload and `IBinderSession` stub. |
| `fbcff2f5087209e5667c40f45f2a8ac7a7a6f2b6` | Ported/adapted | Legacy unsafe-hook refusal was blob-compatible; modern refusal was adapted around SR's hook registry. |
| `92f3e60ea9504c9064cc3bca287456d196a5937c` | Behavior ported | JNI pending-exception cleanup, Zygisk Java-entry result handling, and dex2oat `GetStringUTFChars` failure handling are present. |
| `73eba3ac0b6897144be8487e2a86c71449558949` | Runtime portion ported | `XResources` lambda/compute removal is present. The upstream dexlib2 release-build isolation task is not imported; new-Manager logging/archive changes are intentionally excluded. |
| `44552398db793a6d02b33acbc66978966950ffef` | Ported | Android 17 static-final reflective write support is integrated into the existing native HookBridge. |
| `5ff67a87cb70414d8c52b59f97f56a9de257ee49` | Ported/adapted | `system_server` native-library staging is isolated in `NativeLibraryStager` so it does not own or alter the SR reinjection lease lifecycle. |
| `04093fdbf4cff2426cd1b320d5e568282d0e2a77` | Selectively ported | API101 hook-class-initializer, invoker/chain, exception-mode, preferences, late-system_server, package semantics and scope correctness are selectively adapted without the new Manager IPC model. |
| `abae837a50d3a50577e4a458e2bbf57e64312af3` | Core semantics ported/adapted | Canonical native `replaceCallback()` and invocation-snapshot semantics are present; successor generation is built before disturbing the old one; every module-owned hook registration is serialized with generation retirement; a committed old `VectorContext` remains permanently frozen; per-entry detach, API102 legacy-API isolation and multi-user ownership are present. The newer process-wide asynchronous reload channel/outcome/timeout model and canonical system_server hot reload remain intentionally outside this SR adaptation. |
| `ebb438b948b327b5d0c1bb5495c56c879948990d` | Daemon semantics selectively ported | Empty `staticScope` declarations are ignored as packaging errors; scope requests retain timeout/first-answer semantics; full uninstall resets `Never ask again`; activation clears only the stale `not activated yet` notice. Manager-side Compose scope-editor changes are excluded. |
| `4fcea0e528e737efb5fdadf02ee7fd47d55d527b` | Ported/adapted | Scope requests are batched to one prompt/result with `system` handling, durable approval, first-answer-wins and bounded pending requests while retaining the old receiver/IPC namespace. |
| `f2ef0b20b39ed869fb54be2cb1359a96042dcb40` | Daemon behavior selectively ported | Module-update notifications posted in user 0 prefer a user-0 Manager destination when the module is installed there; unrelated Compose scope UI/navigation changes are excluded. |
| `e8bec6bd714d0f277c8134096501ce36902b32fd` | Ported/adapted | External provider references are released, binder delivery is moved off the UID observer, failures are throttled, binder death is tracked, Android 8.1/9 signatures are handled, and the API27-37 `IUidObserver` method union is declared/overridden. |
| `f5655426b28a2c9cf8c6d310643d1f0bdc38bdf4` | Runtime compatibility selectively ported | API27-safe package version-code reads, SELinux `FileObserver` construction and `LocalServerSocket` lifetime are applied to the daemon. Dependency, AGP/NDK, signing, warning cleanup and new-Manager changes from the same upstream commit are deliberately excluded. |

## Vector-SR-specific compatibility work

The integration branch also contains fixes that are not claimed as verbatim upstream code:

- Historical Vector-SR API102 test metadata is accepted when canonical `targetApiVersion` is absent, while standard libxposed metadata remains authoritative. This prevents the daemon and injected process from assigning different API levels to the same APK.
- `BaseInvoker` uses the canonical API102 native callback snapshot as the invocation boundary; a callback already snapshotted cannot disappear because another thread replaced its handle.
- The API102 generation map swap is treated as the irreversible commit point. If later new-generation callback work fails, the retired generation remains frozen rather than being incorrectly re-enabled.
- Device-wide package removal passes a nullable user to `PreferenceStore.deleteModulePrefs`, so preferences from all Android users are actually removed. This intentionally corrects the current upstream call-site typo where `targetUser` is computed but `userId` is still passed.
- The API27 dex2oat compatibility changes are fitted around Vector-SR's existing stale-mount/property-fallback/soft-restart recovery instead of replacing that recovery with upstream's file wholesale.

## Intentionally retained Vector-SR architecture

- Legacy View-based Manager in `app/`.
- Existing `org.lsposed.lspd.*` service/Binder namespace.
- KernelSU soft-restart reinjection owner, lease, PID-reuse and recovery model.
- SR `ModuleCodeIdentity` stale-code diagnostics.

## API102 boundary that remains intentionally non-canonical

Vector-SR still retains its pre-existing synchronous `IHotReloadTarget` transport. Therefore this backport does **not** claim the newer upstream process-wide asynchronous channel, framework-owned 30-second timeout/outcome protocol, cached-process freezer flow, or canonical system_server hot reload. The in-process generation retirement and atomic hook semantics are aligned independently of that transport choice.

This table is intentionally conservative. `Ported` means the relevant behavior exists on this branch; it does not claim source-level identity unless the corresponding commit explicitly records a blob-compatible backport.
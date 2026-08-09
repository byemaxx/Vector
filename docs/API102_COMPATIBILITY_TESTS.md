# libxposed API 102 behavioral acceptance matrix

This matrix validates API 102 behavior independently of Vector-SR's internal IPC topology. The implementation intentionally keeps the existing View Manager and `org.lsposed.lspd.*` transport; compatibility is judged by the behavior observable through libxposed API/service interfaces.

## Hook and generation lifecycle

| Case | Expected behavior |
| --- | --- |
| API102 module initial load | Exactly one active generation is published; API102 code cannot resolve legacy Xposed API classes. |
| Successor construction fails | Old generation remains active and unfrozen; no partial generation is published. |
| `onHotReloading()` returns `false` | Reload reports `HOT_RELOAD_FAILED` with a null message; old generation is unfrozen and remains active. |
| `onHotReloading()` throws | Reload reports `HOT_RELOAD_FAILED` with a diagnostic; old generation remains active. |
| Successful handover | Old generation is frozen before the callback, old hook handles are snapshotted afterwards, successor is committed, and the old generation stays permanently retired. |
| `onHotReloaded()` throws after commit | Reload reports `HOT_RELOAD_FAILED`, but the new generation remains committed and its loaded version is recorded. |
| `detach()` | Only the calling entry is detached; sibling entries remain active. |
| Saved instance state | Framework/app objects are accepted; the value itself and direct array/collection/map members created by the retiring module classloader are rejected. |
| Mixed Java/native module | A module with exactly one Java entry remains reloadable even with `native_init`; the framework does not `dlclose`, call `JNI_OnUnload`, or unregister JNI state. Native cleanup before accepting reload is module-owned. |

## Hook replacement

| Case | Expected behavior |
| --- | --- |
| `HookHandle.replaceHook()` | Old and new hook callbacks are exchanged atomically; no interval has both or neither registered. |
| `HookBuilder.setId()` replacement | Existing same-id hook is replaced atomically while preserving API102 ownership semantics. |
| Invocation already in progress | A call uses the callback snapshot it began with even if another thread replaces a hook concurrently. |
| Retired generation attempts a new hook | Registration is rejected after the old generation has been frozen. |

## Service hot reload

| Case | Expected behavior |
| --- | --- |
| `getRunningTargets()` | Returns modern-module targets for the calling module/user plus device-wide system targets, with current pid/uid/process/version/state. A running modern module is still listed when its descriptor itself cannot be hot reloaded. |
| Unsupported loaded target | A target whose loaded generation has zero/multiple Java entries remains discoverable; a reload request reports `HOT_RELOAD_UNSUPPORTED`. |
| Invalid or cross-user target id | `hotReloadModule()` throws `SecurityException` synchronously, before ordinary unsupported checks. |
| Manual reload request | Target ownership/capability is validated and the target is atomically marked `RELOADING` before enqueue; lifecycle work executes outside the caller Binder thread and result is delivered through the callback. |
| Same target already reloading | A competing request deterministically reports `HOT_RELOAD_IN_PROGRESS`. |
| Service reconnect during reload | Re-registering the same resident target does not overwrite its `RELOADING` state before the active worker completes. |
| Target dies before/during reload | Result is `HOT_RELOAD_PROCESS_DIED`. |
| Target does not answer | Framework reports `HOT_RELOAD_FAILED` after the 30-second framework-owned deadline and leaves the target out of `RELOADING`. |
| Module refuses reload | Result is `HOT_RELOAD_FAILED` with null message. |
| Replacement descriptor unsupported | If the running target is reloadable but the replacement no longer has exactly one Java entry, the attempt reports `HOT_RELOAD_UNSUPPORTED` and the old generation remains active. |
| Service data | The Bundle is passed through after normal Binder unmarshalling; Vector-SR does not add a stricter framework-side Parcelable/Serializable whitelist. |

## Automatic and process-state behavior

| Case | Expected behavior |
| --- | --- |
| `autoHotReload=false`/absent | APK update leaves existing generations stale until an explicit reload or process restart. |
| `autoHotReload=true` | A committed module code-identity change offers reload to stale hot-reloadable running targets. |
| Same APK path update | A version/content change still rebuilds module identity and can trigger auto reload. |
| Duplicate package broadcasts | Once the new code identity is committed, later cache rebuilds see no identity change and do not offer the same generation again. |
| Cached/frozen target | Only the addressed process cgroup is temporarily thawed; freezer state is restored best-effort after the transaction. |
| Secondary user/work profile | A module app cannot enumerate or reload another Android user's app targets. |
| `system_server` | Device-wide target is discoverable and reloadable; updated native libraries are restaged before successor construction. An already-running target remains addressable even if `system` scope was removed afterwards. |
| Early `system_server` target | A target registered before PackageManager/cache readiness preserves the actually loaded APK identity and is reconciled only when the same artifact is later identified. |
| KernelSU soft restart | The dead system_server target disappears through binder death and re-registers after reinjection; surviving app-process targets are retained. |

## Device validation before merge

At minimum, validate a normal application target and a `system_server` target with an API102 test module. Exercise success, refusal, post-commit callback failure, timeout/process death, `autoHotReload=true`, a background cached target, a same-path APK update, and a loaded multi-entry target returning `UNSUPPORTED`. Where a work profile or secondary user is available, verify target visibility and manual reload isolation across users.

Useful lifecycle log markers include `RELOAD_REQUESTED`, `NEW_INSTANTIATED`, `FREEZE_HOOKS`, `COMMITTED`, `PRECOMMIT_FAILED`, and `RETIRED_FROZEN`.

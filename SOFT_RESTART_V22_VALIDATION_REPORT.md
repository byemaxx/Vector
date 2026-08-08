# Vector-SR v2.2 Soft Restart Validation Report

## Scope

- Upstream baseline: `077131dba`.
- Working branch: `feat/upstream-v2.2-sr`.
- This validation covers the recovery coordinator, owner metadata/lease handling,
  framework registration lifetime, Dex2Oat recovery, and stable-release metadata flow.

## Changes validated in this round

- Metadata locking uses bounded explicit acquisition attempts and releases each lock once.
- Owner liveness starts with `kill(pid, 0)`, then validates boot ID, process start time, and cmdline.
- Recovery Phase A and Phase B use the same bounded lease acquisition policy.
- Failed recovery retries are bounded across the whole failure episode; activity callbacks do not
  capture an obsolete generation.
- `ACTIVITY_UNAVAILABLE` retries only Phase B. `ATTACH_TIMEOUT` restarts the primary zygote before
  a bounded full recovery generation, so a new system_server can specialize and attach.
- Injection transact failures are an explicit bounded failure kind and schedule the same prepared
  primary-zygote recovery path instead of relying on a Binder death callback.
- Controlled primary-zygote restarts carry an expected source generation; stale/expected death
  callbacks cannot reset the active failure episode or its retry budget.
- System-server attach notifications are checked against the current PID and framework registry,
  including early attach during `WAITING_FOR_SYSTEM_SERVER`.
- Framework recovery epochs are independent from registration IDs, and stale cleanup confirms a
  failed heartbeat twice before removing the same registration.
- Dex2Oat debug binaries are optional, release binaries remain required, and mount verification
  checks both the daemon and init namespaces. Invalid socket IDs receive an explicit failure byte.
- Stable update metadata is published only after the tagged release asset is present, to a clean
  orphan `update-metadata` branch.
- ManagerGuard initialization failures now return no manager binder, while invalidation still
  performs Binder cleanup outside the manager lock.
- Dex2Oat SELinux-permissive and sepolicy-error states remain fail-closed when no property fallback
  is active. This is an intentional conservative policy pending device verification.

## Local verification

All commands were run from the repository root using the existing Gradle cache.

- `git submodule sync --recursive`
- `git submodule update --init --recursive --force`
- `:daemon:compileDebugKotlin`
- `:daemon:ktfmtCheck`
- `:daemon:buildCMakeDebug[arm64-v8a]`
- `:zygisk:buildCMakeDebug[arm64-v8a]`
- `git diff --check`

The arm64 debug native/Kotlin build and formatting check passed. Only existing `seteuid` deprecation
warnings were emitted.

## Not yet executed

- GitHub Actions run for the stable tag and remote release-asset verification.
- Full `clean zipAll` across all ABIs.
- Device installation, system_server soft-restart loops, Dex2Oat SELinux/mount scenarios, and
  crash/restart stress testing.
- The expected behavior for permissive and sepolicy-error ROMs: recovery must either retain a
  verified property fallback or stop without claiming successful recovery; device logs must confirm
  which branch was taken.
- Automated daemon unit tests: this checkout currently has no daemon test source set or test
  dependency configured, so no synthetic test suite was added in this round.

## Assessment

The implementation changes in this round are locally buildable and are committed separately for
review. Overall project acceptance remains **pending** until the stable CI workflow and a real device
complete the release, installation, and repeated soft-restart checks above.

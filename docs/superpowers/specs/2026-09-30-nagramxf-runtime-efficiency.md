# NagramXF Runtime Efficiency Spec

## Goal
Reduce idle CPU wakeups, battery drain, heat, UI jank, and concurrency-related bugs without rewriting Telegram core or changing user-visible Ghost Mode semantics.

## Project constraints
- Base: `trangkyanh17/NagramXF`, branch `dev`, baseline `30dcd6ce7b5b0279fa86f302ca61cd272bfa3068`.
- Keep Telegram upstream Java/UI and native media/network stacks close to upstream.
- Do not introduce Rust or a whole-app language rewrite.
- Prefer small, reviewable patches with TDD and fresh verification.
- Native execution method: this session implements changes directly; no subagent ownership.
- Build/test on isolated worktree; never patch `dev` in place.
- GitHub Actions is final CI evidence; device testing is required later for real battery/thermal/jank claims.

## First subsystem: AyuWorker
Current `AyuWorker.run()` uses `scheduleWithFixedDelay` with 1500 ms initial delay and 3000 ms period. Once started, the scheduler continues waking indefinitely even when no account has pending offline work.

Required behavior:
1. `setOnline(account, true)` must schedule one delayed offline check, preserving the existing 1500 ms debounce behavior.
2. Repeated qualifying calls before the delay expires must replace/reschedule the pending one-shot task rather than create parallel tasks.
3. After the one-shot task runs, no recurring timer remains.
4. `clearOnline(account)` must prevent a pending offline send for that account.
5. `requestLastSeenUpdate(account)` must still trigger the immediate offline request + delayed pill notification when enabled, but must not cause a second offline request solely because of the worker timer.
6. `shutdown()` must cancel pending work and stop the scheduler cleanly.

## Out of scope for this patch
Plugin watchdog polling, Room main-thread queries, executor consolidation, push-service cadence, native compiler flags, and UI rendering optimizations are separate follow-up plans.
# P7 — Bounded Transcription Burst Concurrency Implementation Plan

**Status:** Approved 2026-10-04
**Design:** `docs/superpowers/specs/2026-10-04-transcription-burst-concurrency-design.md`
**Implementation base:** `132c8d8f87ecccf3e7a9778526675ba823d64161`
**Design commit:** `7de45d21a9dce5a4fa3ba0230ae229172fe47e0d`

## Goal

Replace the unbounded cached transcription executor with a two-worker bounded executor that queues extra jobs FIFO and allows idle workers to time out, while preserving all provider/request/callback behavior.

## Runtime scope

Expected runtime changes:

- `TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/TranscribeHelper.java`
- new `TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/TranscriptionExecutorFactory.java`

Expected tests:

- `TMessagesProj/src/test/java/tw/nekomimi/nekogram/helpers/TranscriptionExecutorFactoryTest.java`
- `TMessagesProj/src/test/java/tw/nekomimi/nekogram/helpers/TranscribeHelperConcurrencyShapeTest.java`

Forbidden runtime scope without returning to design:

- `TranscribeButton.java`
- `HttpClient.kt`
- `NaConfig.kt`
- `com.exteragram.messenger.ai.network.Client`
- Telegram server transcription paths
- generic Telegram media/camera/exoplayer pools
- any P1–P6 runtime file unrelated to this P7 change

## Task 1 — RED executor policy test

Create the pure-Java factory test before runtime code.

Required RED assertions on current P6 baseline:

1. `TranscriptionExecutorFactory` exists;
2. newly created executor is a `ThreadPoolExecutor`;
3. core pool size is exactly 2;
4. maximum pool size is exactly 2;
5. keepalive is exactly 60 seconds;
6. `allowsCoreThreadTimeOut()` is true;
7. work queue is `LinkedBlockingQueue`;
8. freshly created pool has zero live workers;
9. two latch-blocked jobs can become active together;
10. a third job remains queued while both workers are occupied;
11. releasing one worker allows the third job to begin;
12. third submission is not rejected.

Use only deterministic latches/barriers. Do not sleep for 60 seconds.

Run:

```bash
./gradlew :TMessagesProj:testNormalDebugUnitTest \
  --tests 'tw.nekomimi.nekogram.helpers.TranscriptionExecutorFactoryTest' \
  --no-configuration-cache
```

Record RED before adding the factory.

## Task 2 — Implement pure-Java bounded executor factory

Add package-private:

`TranscriptionExecutorFactory.java`

Constants:

```text
MAX_CONCURRENT_TRANSCRIPTIONS = 2
KEEP_ALIVE_SECONDS = 60
```

Factory contract:

- create `ThreadPoolExecutor(2, 2, 60, SECONDS, new LinkedBlockingQueue<>())`;
- call `allowCoreThreadTimeOut(true)`;
- do not call `prestartCoreThread()` or `prestartAllCoreThreads()`;
- do not install a rejection policy that can fail queued work;
- import only `java.util.concurrent.*`.

Run focused factory tests until GREEN.

### Commit

```text
perf: add bounded transcription executor policy
```

## Task 3 — RED TranscribeHelper integration/source contract

Before changing `TranscribeHelper`, add a narrow source-contract test.

Required assertions:

1. no `Executors.newCachedThreadPool()` remains in `TranscribeHelper`;
2. executor is obtained from `TranscriptionExecutorFactory`;
3. exactly one static transcription executor field remains;
4. all 3 existing `executorService.submit(...)` sites remain;
5. request methods remain:
   - `requestWorkersAi`
   - `requestGeminiAi`
   - `requestOpenAiCompatible`
6. `sendRequest(...)` provider routing remains present;
7. no second transcription executor is introduced;
8. no `CallerRunsPolicy`, `SynchronousQueue`, `Semaphore`, or provider-specific executor is introduced.

Run focused test and record RED.

## Task 4 — Minimal TranscribeHelper integration

Modify only executor construction in `TranscribeHelper`.

Replace:

```java
Executors.newCachedThreadPool()
```

with factory-created executor.

Do not alter:

- provider selection;
- credentials checks;
- endpoint/prompt/model logic;
- media extraction;
- file reading;
- Base64;
- request body;
- OkHttp client;
- synchronous `.execute()`;
- callbacks;
- error strings;
- submit-site count or placement.

Then run:

```bash
./gradlew :TMessagesProj:testNormalDebugUnitTest \
  --tests 'tw.nekomimi.nekogram.helpers.TranscriptionExecutorFactoryTest' \
  --tests 'tw.nekomimi.nekogram.helpers.TranscribeHelperConcurrencyShapeTest' \
  --no-configuration-cache

./gradlew :TMessagesProj:compileNormalDebugJavaWithJavac --no-configuration-cache
git diff --check
```

### Commit

```text
perf: bound custom transcription concurrency
```

## Task 5 — Whole-P7 regression and preservation gate

Run fresh:

```bash
./gradlew :TMessagesProj:testNormalDebugUnitTest --no-configuration-cache
./gradlew :TMessagesProj:compileNormalDebugJavaWithJavac --no-configuration-cache
```

Both must report `BUILD SUCCESSFUL`.

Run a deterministic static gate against P6 base `132c8d8...`.

The gate must prove:

1. no `newCachedThreadPool()` remains anywhere in `TMessagesProj/src`;
2. only one transcription executor factory/pool exists;
3. pool core size = 2;
4. pool max size = 2;
5. keepalive = 60 seconds;
6. core timeout enabled;
7. queue is `LinkedBlockingQueue`;
8. no caller-runs/rejection behavior added;
9. all 3 provider `submit(...)` sites remain;
10. `TranscribeButton.java` SHA-256 matches P6;
11. `HttpClient.kt` SHA-256 matches P6;
12. `NaConfig.kt` SHA-256 matches P6;
13. AI chat `Client.java` SHA-256 matches P6;
14. `requestWorkersAi`, `requestGeminiAi`, `requestOpenAiCompatible`, and `sendRequest` bodies match P6 except the executor source line/context required by this change;
15. runtime diff is limited to `TranscribeHelper.java` + `TranscriptionExecutorFactory.java`;
16. no P1–P6 protected runtime file changes;
17. `git diff --check` passes.

Review full diff for unrelated formatting churn.

## Task 6 — Record evidence and publish implementation branch

After all verification is fresh:

- mark every checklist item complete;
- set plan status to `Executed — verified 2026-10-04`;
- commit verification evidence;
- push implementation branch:

```text
perf/v2-p7-transcription-concurrency
```

Verify local HEAD == remote HEAD.

Do not merge into `dev`, release, build/install APK, or claim device-level battery/thermal improvements.

## Execution checklist

- [ ] Factory RED test observed.
- [ ] Factory implementation GREEN.
- [ ] Factory policy exact: 2/2 workers.
- [ ] 60-second keepalive verified.
- [ ] Core-thread timeout verified.
- [ ] FIFO LinkedBlockingQueue verified.
- [ ] Two simultaneous jobs verified.
- [ ] Third job queues without rejection.
- [ ] Factory committed.
- [ ] TranscribeHelper source RED test observed.
- [ ] Cached executor removed.
- [ ] Exactly one factory-backed transcription executor.
- [ ] Three submit sites preserved.
- [ ] Provider routing preserved.
- [ ] Focused P7 tests GREEN.
- [ ] Normal Java compile passed.
- [ ] Runtime integration committed.
- [ ] Full normal unit suite passed fresh.
- [ ] Fresh normal Java compile passed.
- [ ] Whole-P7 static preservation gate passed.
- [ ] TranscribeButton unchanged from P6.
- [ ] HttpClient unchanged from P6.
- [ ] NaConfig unchanged from P6.
- [ ] AI Client unchanged from P6.
- [ ] No unrelated runtime diff.
- [ ] git diff --check passed.
- [ ] Implementation branch pushed.
- [ ] Local/remote implementation HEAD match.
- [ ] No integration/release/deploy/device gate crossed.

## Stop conditions

Stop and return to design if implementation requires:

- changing `TranscribeButton`;
- changing HTTP timeout/client behavior;
- changing provider routing or callback threading;
- rejecting queued transcription jobs;
- using CallerRunsPolicy;
- changing provider payloads/endpoints/prompts;
- introducing cancellation semantics;
- changing Telegram server transcription;
- changing AI chat request scheduling;
- touching unrelated P1–P6 runtime files.

## Approval boundary

Plan approval authorizes Tasks 1–6 on the dedicated P7 implementation branch.

It does **not** authorize merge into `dev`, release, APK installation, production/device rollout, or quantitative performance claims.

# P5 — Lazy MediaStreamingProvider Lifecycle Design

**Status:** Draft — awaiting design approval
**Program:** NagramXF Performance V2
**Implementation base after P4B:** `57931554aec829fd6c5ceb6a2e2b13010bcf3170`
**Golden behavioral baseline:** `30dcd6ce7b5b0279fa86f302ca61cd272bfa3068`

## Intent

Avoid creating the `MediaStreamingProvider` callback `HandlerThread` during ordinary process/provider startup when media streaming is never used.

P5 preserves the existing ContentProvider contract and streaming behavior. The only intended runtime change is lifecycle timing:

- provider creation remains lightweight;
- the callback worker is created lazily on the first valid streaming file open;
- subsequent streaming opens reuse the same worker;
- provider shutdown still stops the worker if it was ever created.

No media format, URI, permission, data-source, file-descriptor, or PhotoViewer decision semantics are changed.

## Baseline evidence

At verified P4B HEAD:

- `TMessagesProj/src/main/AndroidManifest.xml` declares `tw.nekomimi.nekogram.streaming.MediaStreamingProvider` with authority `${applicationId}.streaming`, `exported=false`, and `grantUriPermissions=true`;
- the provider has no separate `android:process` declaration;
- `MediaStreamingProvider.onCreate()` immediately creates and starts a `HandlerThread("MediaStreamingProvider")`, then creates its `Handler`;
- that handler is consumed only by `openFile(...)` when passed to `StorageManagerCompat.openProxyFileDescriptor(...)`;
- `query`, `getType`, `insert`, `delete`, `update`, and `getStreamTypes` do not need the worker;
- repository call-site search finds streaming entry from `PhotoViewer` through `MediaStreamingProvider.openForStreaming(...)`;
- `openForStreaming(...)` only constructs/grants a content URI and launches the external viewer;
- `StorageManagerCompat` needs a `Handler` for proxy-file callback execution, including the pre-O reliable-pipe fallback.

Therefore the worker is a lifecycle resource whose creation can be delayed until a valid `openFile(..., "r")` actually needs proxy-file callback execution.

## Classification

P5 is a **feature-path lifecycle optimization**.

Ordinary app startup that never invokes external media streaming should not pay for a dedicated `MediaStreamingProvider` worker thread.

The optimization does not claim battery, thermal, startup-time, or memory percentages before real-device measurement.

The allowed structural claim is:

> The MediaStreamingProvider callback HandlerThread is no longer created by provider onCreate(); it is created on first valid streaming file open.

## Behavior contract to preserve

### Provider registration

The manifest provider entry remains unchanged:

- same authority;
- same class;
- same exported state;
- same URI grant behavior;
- no process change.

P5 must not disable or dynamically unregister the provider.

### onCreate()

`onCreate()` still returns `true`.

It must not:

- create/start a `HandlerThread`;
- create the callback `Handler`;
- perform media I/O;
- schedule delayed initialization.

There is no replacement background resource created at startup.

### openFile() validation order

Existing validation remains intact:

1. resolve provider context;
2. return `null` if context is unavailable;
3. reject any mode other than `"r"` with `SecurityException`;
4. construct the existing `ProxyFileDescriptorCallback`;
5. obtain `StorageManagerCompat`;
6. lazily obtain the callback handler;
7. call `openProxyFileDescriptor(...)`;
8. translate `IOException` to `FileNotFoundException` as before.

Invalid context or invalid write mode must not create the worker.

### First valid streaming open

The first valid read-mode `openFile()` creates exactly one callback worker:

- thread name remains `MediaStreamingProvider`;
- thread is started before its `Looper` is used;
- callback `Handler` is bound to that thread's looper.

Thread creation is synchronous at the point it is first required. P5 does not move or defer the actual proxy callback registration after `openFile()` returns.

### Repeated and concurrent streaming opens

All valid opens for one provider instance reuse the same callback handler/thread.

Lazy initialization must be synchronized so concurrent first opens cannot create duplicate `HandlerThread` instances.

The provider must not create one worker per file descriptor.

### shutdown()

`shutdown()` remains the lifecycle cleanup boundary.

If the worker was never created:

- shutdown is a no-op for the worker and must not throw.

If the worker exists:

- preserve baseline `quit()` behavior;
- clear provider references after requesting quit so a dead handler is not retained by the provider object.

P5 does not add a new delayed shutdown, idle timeout, thread restart loop, or reference-counted lifecycle.

### Streaming data path

The following behavior remains unchanged:

- `getStreamingUri(...)`;
- authority `${applicationId}.streaming`;
- `Intent.ACTION_VIEW`;
- MIME type;
- `FLAG_GRANT_READ_URI_PERMISSION`;
- activity request code `500`;
- `ProxyFileDescriptorCallback` constructor/data source behavior;
- `onRead`, `onWrite`, `onFsync`, `onGetSize`, and `onRelease`;
- `StorageManagerCompat` behavior on O+ and pre-O.

P5 does not modify `PhotoViewer`.

## Chosen architecture

Keep lazy ownership inside `MediaStreamingProvider`; do not introduce a global singleton or service.

Provider fields remain:

- nullable `HandlerThread callbackThread`;
- nullable `Handler callbackHandler`.

Add one synchronized helper, conceptually:

```java
private synchronized Handler getCallbackHandler()
```

Behavior:

1. if `callbackHandler` already exists, return it;
2. otherwise create `HandlerThread("MediaStreamingProvider")`;
3. start it;
4. create `Handler(thread.getLooper())`;
5. publish both fields;
6. return the handler.

`onCreate()` becomes only:

```java
return true;
```

`openFile()` calls the lazy helper only after context/mode validation and immediately before proxy-file-descriptor registration.

`shutdown()` becomes synchronized/null-safe, quits the thread when present, and clears both fields.

## Why provider-local lazy initialization

The worker serves only this provider's proxy-file callbacks.

Moving it into:

- `ApplicationLoader`;
- `Utilities.globalQueue`;
- a static global executor;
- `PhotoViewer`;

would broaden ownership and alter callback-thread behavior without evidence that such a change is needed.

Keeping the worker provider-local preserves baseline isolation and makes lifecycle ownership explicit.

## Why Utilities.globalQueue is not used

Unlike P4B prewarming, proxy-file-descriptor APIs accept a `Handler` and rely on a looper-backed callback context.

Replacing the dedicated handler with a generic queue would change the execution contract and the pre-O pipe implementation.

P5 therefore preserves the dedicated `HandlerThread`; it only changes **when it is created**.

## Race analysis

### Two first openFile() calls

Both may reach lazy handler acquisition concurrently.

The synchronized helper guarantees only one thread is created and both calls receive the same handler.

### shutdown() before any open

No worker exists; shutdown returns without error.

### shutdown() after worker creation

The existing worker receives `quit()` and references are cleared.

No new idle or restart policy is introduced.

### Invalid open mode

The `SecurityException` is thrown before lazy handler acquisition, so invalid writes cannot instantiate the worker.

### Context unavailable

The existing `null` result happens before lazy handler acquisition.

## Scope boundaries

Expected runtime diff:

- `TMessagesProj/src/main/java/tw/nekomimi/nekogram/streaming/MediaStreamingProvider.java` only.

Expected tests:

- one structural/lifecycle regression test under `TMessagesProj/src/test/java/tw/nekomimi/nekogram/streaming/`.

Manifest, PhotoViewer, StorageManagerCompat, data-source classes, and all prior P1–P4 code remain frozen for P5.

If implementation needs another runtime file, execution stops and the design is revisited before expanding scope.

## Explicit non-goals

P5 does not:

- remove the provider;
- change provider export/grant policy;
- put the provider in another process;
- replace `HandlerThread` with an executor;
- reuse Telegram global queues for proxy callbacks;
- add an idle timeout;
- stop/restart the worker between individual streams;
- modify streaming URI generation;
- modify external viewer selection;
- modify `ProxyFileDescriptorCallback`;
- optimize data-source reads;
- alter `StorageManagerCompat`;
- claim measured startup/power improvement.

## Rejected alternatives

### Disable provider until user invokes streaming

Rejected because dynamically enabling a ContentProvider changes component/package-manager state and is unnecessary.

### Create thread in openForStreaming()

Rejected because that static call belongs to intent construction, not provider callback ownership, and it can run even if the external activity never opens the content URI.

### Use Utilities.globalQueue

Rejected because the proxy-file API consumes a `Handler`/Looper contract and pre-O fallback posts callback work to that handler.

### One HandlerThread per opened stream

Rejected because baseline uses one provider-level worker and per-stream workers would increase resources.

### Add idle shutdown

Rejected because it introduces timer/lifecycle complexity beyond the evidence for P5.

## Regression strategy

Because ordinary local JVM tests cannot directly exercise Android `ContentProvider`/`HandlerThread` lifecycle without an Android runtime harness, P5 uses a source-contract regression test for this narrow structural change.

The test must fail on the current baseline and then prove:

1. `onCreate()` returns `true` without constructing/starting `HandlerThread`;
2. a synchronized lazy helper exists;
3. the helper preserves thread name `MediaStreamingProvider`;
4. thread `start()` precedes `getLooper()`/Handler publication;
5. `openFile()` validates context and mode before lazy handler acquisition;
6. `openFile()` passes the lazy handler to `openProxyFileDescriptor`;
7. `shutdown()` is null-safe;
8. `shutdown()` retains `quit()`;
9. no per-open thread construction exists;
10. manifest provider declaration is unchanged;
11. `PhotoViewer` and `StorageManagerCompat` are unchanged from P4B.

## Verification gate

Before P5 implementation is considered verified:

1. focused P5 RED test is observed before runtime change;
2. focused P5 test passes after implementation;
3. full `:TMessagesProj:testNormalDebugUnitTest --no-configuration-cache` passes fresh;
4. `:TMessagesProj:compileNormalDebugJavaWithJavac --no-configuration-cache` passes under JDK 21;
5. deterministic static gate confirms only `MediaStreamingProvider.java` changed in runtime scope;
6. manifest provider stanza matches P4B byte-for-byte or method-equivalent exact text;
7. `PhotoViewer.java` matches P4B SHA-256;
8. `StorageManagerCompat.java` matches P4B SHA-256;
9. no new thread/executor/timer/polling construct exists outside the one existing lazy `HandlerThread`;
10. `git diff --check` passes;
11. implementation branch is pushed and local/remote HEAD match.

## Success criteria

P5 succeeds when a normal app process/provider initialization no longer starts the media-streaming callback worker, while the first actual valid streaming file open still gets the same dedicated callback-thread behavior and all existing streaming contracts remain intact.

The strongest accepted pre-device statement is:

> MediaStreamingProvider no longer starts its callback HandlerThread in onCreate(); the same provider-local worker is created lazily on first valid streaming open and reused thereafter.

## Integration policy

P5 is developed from verified P4B HEAD and committed independently.

This design phase does not authorize implementation, merge into `dev`, release, APK installation, or device performance claims.

After design approval, a separate implementation plan will be written and presented for Plan Gate approval.

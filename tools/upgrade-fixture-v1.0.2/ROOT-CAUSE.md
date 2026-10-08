# Investigation: fixture 1.0.1 failure

Observed by parent: phone-formal SEED PASS, candidate install Success, VERIFY mismatch; tablet-formal equal printed snapshot digests. Neither output proves all durable selected fields were equal because SEED stored one snapshot then printed a second.

Source proof: SelfRunApplication, UserNextInputStore, SelfRun3RuntimeSettings, SelfRunStore and SelfRun3Ledger in phone4.0.8 commit913973df are byte-identical to candidate production files. No production fix is indicated by this fixture failure.

Confirmed fixture defects: pending prefs were written directly without SelfRunStore runId/active/non-IDLE projection. Production cleanupStale clears runId/text/revision when runId is stale, active is false, or phase is IDLE/DONE. The isolated unchanged production class reproduces exactly those three removals. onCreate starts the instrumentation thread immediately; the test did not wait for Application.onCreate. Application.initializeProcess invokes UserNextInputStore.initialize. This makes capture timing nondeterministic. Official lifecycle reference: https://developer.android.com/reference/android/app/Instrumentation#callApplicationOnCreate(android.app.Application)

Past failing run's exact changed fields remain unconfirmed: its artifact emitted no before/after diff, and cloud materialize returned workspace_path null with HTTP403 URLs. The new fixture diagnoses exact synthetic fields without reading or emitting real user data. Tablet1.0.1 PASS is not accepted as preservation proof.

Test evidence boundaries: production cleanup behavior runs against unchanged UserNextInputStore with an in-memory Android preference test backend and constants-only Store test stand-in. Actual new UpgradeFixture class lifecycle and difference methods run with minimal Application/Instrumentation framework stubs; this proves latch/diff behavior, not full Android scheduling. The signed isolated CI run is still required.

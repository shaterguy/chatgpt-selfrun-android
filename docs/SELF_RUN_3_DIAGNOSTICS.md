# SelfRun 3 continuation diagnostics

3.0.1-dev18 is a diagnostic TEST candidate based on the failed dev16 runtime, not a confirmed correction of the missing-composer failure. The observed failure reaches the next logical turn after a committed result, but that turn cannot find an editor. The reason the page lost its editor remains unverified. A passing candidate workflow does not establish a successful authenticated ChatGPT continuation.

The dev17 source branch was occupied by a separate concurrent composer-pinning change before this candidate was published. That branch is not overwritten or incorporated into this diagnostic baseline. Dev18 retains dev16 behavior deliberately for controlled failure evidence collection; it is not a cumulative successor to that separate dev17 change.

## Collection contract

- V3_ENV records the app, Android API and WebView versions when the automation WebView is created.
- V3_JS_ERROR records only fixed error-class and DOM-operation labels plus a numeric minified React error code. Duplicate classifications are suppressed, with at most 12 distinct records per WebView. Console messages and source locations are not stored. Console events may originate in subframes; they are diagnostic clues, not authoritative control signals.
- V3_PAGE_VIEW and V3_PAGE_DOM are collected once when a continuation first reports COMPOSER_WAITING, and once at that physical request's first preparation timeout. Retries with the same request do not restart collection. Callback identity checks reject old pages, requests and tasks. There is no additional timer, generation-wait poll, network request or DOM mutation.
- Native view records contain Surface attachment, detachable-output capability, display state, view/window visibility, focus, attachment and native dimensions.
- Page records distinguish main-document, open-shadow-root and readable-frame editor counts. Traversal has limits of 24 roots, 5,000 visited elements and 8 frames. A capped scan or unreadable frame must not be interpreted as proof that no editor exists there. Closed shadow roots are not accessible.

## Compact page field names

Existing logs cap each detail at 240 characters; collection does not relax that limit or the existing redaction rules.

b = body child count; m = main count; f = form count; ed = raw main-document editor count; in = text input count; se = open-shadow editor count; fe = readable-frame editor count; sr = open-shadow root count; fr = frame count; xf = unreadable-frame count; n = visited elements; cap = traversal limit indicator; txt = capped body text length; w/h = layout viewport size; vw/vh = visual viewport size; title = fixed OTHER, APP_ERROR, CHALLENGE or AUTH classification.

The title classification is only a heuristic and can match a user-chosen page title. No title, body text, HTML, URL, credentials, error message, stack trace or screenshot is written to diagnostics. Numeric fields are clamped and projected through a fixed allowlist before logging. Storage and export remain the existing private diagnostic-log mechanism; nothing is automatically uploaded.

## Non-regression boundary

Relative to dev16, the result contract, result-read WakeLock, coordinator, request/profile protocol, first-turn and continuation input implementations, Surface detach/attach policy, preparation deadline, retry policy, navigation, login, application IDs, signing lineages and dependencies are unchanged. This candidate does not reload, recreate or keep the display permanently attached to work around the missing editor.

## Validation scope

The JVM suite includes error redaction, numeric projection, log-length, collection-budget and read-only contract tests. The existing API 36 runtime profile adds real-WebView main/shadow/frame snapshots, no page mutation, no diagnostic-triggered Surface attachment, and bounded console classification. TEST update validation starts from dev16's canonical artifact, the actual failed build being diagnosed, while the formal co-install baseline remains the latest stable 3.0.0. Authenticated live-site and user-device root-cause verification remain outside these synthetic fixtures.

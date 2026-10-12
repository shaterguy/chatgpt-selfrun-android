# SelfRun Drive development and release routing

For stable Android release tasks (`vX.Y.Z`, not `-devN`/`-rcN`), use the canonical local-first entrypoint:

`tools/selfrun_stable_release.sh plan`
`tools/selfrun_stable_release.sh dispatch --qa-report <exact-sha-local-qa.json>`
`tools/selfrun_stable_release.sh collect --run-id <terminal-successful-run-id>`

On a candidate branch that predates this tooling, run the installed `selfrun-stable-release` command from its working tree. The approved GitHub signing workflow is **`sign-selfrun-drive-stable.yml`**, hosted on the default branch and pinned to the candidate SHA via inputs. Do not automatically run a GitHub-hosted Android emulator/instrumentation suite for formal release. Reuse valid local test evidence; GitHub signs the formal APK and verifies artifact identity only. After collecting the signed APK, verify local in-place update, UID/data preservation and TEST co-install **before** creating an immutable tag/Release. Do not republish or rebuild already-verified exact artifacts.

The full authoritative channel and failure-handling procedure is `docs/RELEASE_CHANNELS.md`. Older `release-drive-*.yml`, `build-selfrun-v4-dev.yml` and co-install workflows are not the default stable signer. No source/production key transfer is allowed by this routing.

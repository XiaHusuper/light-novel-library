# Tests

`node tests/library.test.cjs` checks classification, cross-file routing, category budgets, exclusions and rotation.

`node tests/profile.test.cjs` checks daily browse upgrades, reading thresholds, half-life, score caps, reset semantics and bounded records.

`android/AccountTest.java` is the source of the isolated Android instrumentation test used for the account model. It uses a synthetic in-memory transport and a separate Keystore alias/preferences name; it never accesses a real Wenku8 account. The shipped application does not include this fixture code. A successful fixture run is not evidence of a successful authenticated live-server request.

0.4.0: `OfflineTest.java` uses generated text/PNG to test explicit-only cache, serial volume requests, resume, offline reload, quota, cancellation, per-book image isolation and native safe Insets. `AccountTest.java` additionally checks independent website sessions, complete grouped shelves, row IDs, membership readback and absence of chapter/bookmark writes. Browsing history and reading progress remain device-only by design.

0.7.0: `AboutTest.java` reads the actual installed version, validates the private GitHub login destinations and bundled offline notices without network calls. Bookmark category UI tests cover existing/new/uncategorized selections, cancellation, empty-name validation and reserved category names.

# Shared Compose UI in commonMain; Android is the first-verify target

Features are written once in `commonMain` using Compose Multiplatform and run on all targets (Android, iOS, Desktop/JVM, Web JS+Wasm) from the same code. "Android-first" means Android is where each feature is built and QA'd first; other platforms follow from the same source. Platform-specific needs (HTTP engine, DB driver, dispatchers) go behind `expect`/`actual`.

This deliberately rejects writing native UI per platform: there is nothing to "port", and the cost of N UI implementations is not justified for a user-focused tracker.

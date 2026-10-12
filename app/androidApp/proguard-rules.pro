# Muviss release R8 rules.
#
# Baseline shrink/obfuscate config is `proguard-android-optimize.txt`
# (applied from the Android SDK in build.gradle.kts). Everything below is
# specific to libraries this app pulls in that need extra keep rules beyond
# their own bundled consumer-proguard-rules — each block says which library,
# what the problem is, and why the rule is safe.

# ---------------------------------------------------------------------------
# kotlinx.serialization
# ---------------------------------------------------------------------------
# Our @Serializable models (TMDB DTOs, MediaId, WatchStatus, ...) resolve
# their generated `$serializer` companion reflectively at the KSerializer
# call site; R8's static analysis can't see that edge, so the generated
# serializer classes and their `Companion.serializer()` accessor must be
# kept explicitly. Mirrors kotlinx.serialization's own recommended rules
# (see https://github.com/Kotlin/kotlinx.serialization/blob/master/rules/common.pro).
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class com.codingpit.muviss.** {
    *** Companion;
}
-keepclasseswithmembers class com.codingpit.muviss.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.codingpit.muviss.**$$serializer { *; }

# ---------------------------------------------------------------------------
# Ktor (okhttp engine)
# ---------------------------------------------------------------------------
# ktor-client-core resolves its HTTP engine (OkHttp here) via
# java.util.ServiceLoader against META-INF/services/io.ktor.client.*; R8's
# tree shrinker doesn't follow ServiceLoader edges, so the engine + its
# container must be kept or the client fails at runtime with "no engine
# found" even though it compiles fine.
-keep class io.ktor.client.engine.okhttp.OkHttpEngine { *; }
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer { *; }

# kotlinx-coroutines ships a JVM-only debug-agent hook
# (kotlinx.coroutines.debug.internal) that references
# java.lang.instrument.ClassFileTransformer, which doesn't exist on Android.
# coroutines-core already guards the call behind a runtime class-lookup, so
# this is a build-time-only warning, never a runtime crash.
-dontwarn kotlinx.coroutines.debug.internal.**

# OkHttp probes optional TLS providers (Conscrypt/OpenJSSE/BouncyCastle) we
# don't ship as dependencies; OkHttp's own consumer rules cover most of
# this already, these just fill the gap for provider classes referenced
# from newer OkHttp releases.
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.bouncycastle.**

# ---------------------------------------------------------------------------
# SQLDelight (Android driver)
# ---------------------------------------------------------------------------
# The Android driver wraps android.database.sqlite.* directly — no
# reflection, and it ships its own consumer-proguard-rules — so no project
# rules are needed. Verified by a full `assembleRelease` run completing
# without SQLDelight-related R8 warnings.

# ---------------------------------------------------------------------------
# Coil3
# ---------------------------------------------------------------------------
# We wire Coil's Ktor network fetcher explicitly in MuvissApp
# (KtorNetworkFetcherFactory); keep it and its interfaces so R8 can't treat
# it as unused when it's only referenced via a `components { add(...) }`
# builder call.
-keep class coil3.network.ktor3.** { *; }

# ---------------------------------------------------------------------------
# Koin
# ---------------------------------------------------------------------------
# Koin's DSL (`module { single { ... } }`) captures dependencies in lambdas
# resolved at runtime by declared type, not by reflection, so R8 already
# keeps everything reachable from Koin's kept entry points. No project
# rules needed; verified by a full `assembleRelease` run completing without
# Koin-related R8 warnings.

# ---------------------------------------------------------------------------
# Sentry Kotlin Multiplatform
# ---------------------------------------------------------------------------
# Sentry's Android/JVM artifacts ship their own consumer-proguard-rules
# covering their reflective bits (breadcrumb integrations, OkHttp
# instrumentation); no project rules needed.

# ---------------------------------------------------------------------------
# WorkManager (EPIC 5 background worker)
# ---------------------------------------------------------------------------
# WorkManager's default WorkerFactory instantiates a worker by reflection —
# `Class.forName(the worker's fully-qualified name)` then its
# `(Context, WorkerParameters)` constructor — using the class name stored in
# the enqueued WorkSpec, not a compile-time reference R8 can trace. Without
# this rule R8 renames/strips NewEpisodesWorker and the periodic job fails at
# runtime with a ClassNotFoundException. WorkManager's own consumer rules
# keep the library's base classes but can't know about this app's subclass.
-keep class com.codingpit.muviss.notifications.NewEpisodesWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# The hourly sync worker (EPIC 40) is instantiated the same way, by class name
# from the persisted WorkSpec, so it needs the same rule. Forgetting it is the
# trap EPIC 30 found for the midnight widget worker: R8 strips the class, the
# job is enqueued fine, and every run fails with ClassNotFoundException in a
# release build only.
-keep class com.codingpit.muviss.sync.SyncWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# The midnight widget refresh (EPIC 22) is the worker EPIC 30's audit found
# without this rule: armed every night, ClassNotFoundException every night,
# release builds only — so the "aired by today" boundary never moved on the
# widget. Every CoroutineWorker in this module needs a line here.
-keep class com.codingpit.muviss.widget.WidgetMidnightRefreshWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# WorkManager's WorkManagerInitializer (an AndroidX Startup ContentProvider,
# runs unconditionally before Application.onCreate) instantiates its
# Room-generated WorkDatabase_Impl reflectively via a no-arg constructor.
# R8 normally infers that reachability from Kotlin @Metadata, but the R8
# bundled with AGP 9.0.1 cannot parse Kotlin 2.4's metadata format (a build-
# time warning, "An error occurred when parsing kotlin metadata") and falls
# back to static analysis, which sees the constructor as unused and strips
# it — crashing every release install before any app code runs (#147).
# Re-checked on AGP 9.1.1 (#192): R8 no longer logs that warning, but nobody has
# run a minified build on a device without these two rules, so they stay until
# someone does (a mapping.txt inspection cannot show a stripped constructor).
# The broader RoomDatabase rule covers any other AndroidX library that
# generates a Room _Impl the same way under this R8/Kotlin combination, since
# this app doesn't call Room directly and can't enumerate them by hand.
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(); }

# The Settings > About test crash (docs/RELEASING.md "Sentry test crash"). R8's
# class merging folded it into androidx.datastore's protobuf
# UninitializedMessageException — both are bare RuntimeExceptions — and Sentry
# can only name the class that survived, so the issue was titled after protobuf.
-keep class com.codingpit.muviss.feature.settings.ui.MuvissTestCrash

# Every app-defined exception keeps its own class (#199). R8 merges classes
# with no members of their own into one another, and a merged exception
# reaches Sentry under the surviving class's name and groups with it: the test
# crash above once filed itself as protobuf's UninitializedMessageException,
# and two of the app's own failure types could collapse into one issue. Keeping
# them blocks the merge; allowobfuscation still renames them (the uploaded
# mapping restores the name) and allowshrinking still drops the unused ones.
-keep,allowobfuscation,allowshrinking class com.codingpit.muviss.** extends java.lang.Throwable

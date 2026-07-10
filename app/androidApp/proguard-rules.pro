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

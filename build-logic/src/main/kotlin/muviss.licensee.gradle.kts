import app.cash.licensee.LicenseeExtension

// The one licence allow-list for every module whose runtime graph ships to a user (#615): :app:androidApp,
// :app:desktopApp, :app:webApp and :app:shared (the iOS framework). Each of them gets licensee's report, and
// `verifyOssLicenses` in :app:androidApp unions those reports into About > Licenses. Keeping the policy here
// means a licence allowed for one target is allowed for all of them, and a licence that one target's graph
// brings in fails that target's own report instead of slipping through an allow-list written for another.
//
// Only the licence classes every target agrees on belong here. Android-only terms (Play Core, GMS) stay in
// :app:androidApp's build file, by exact coordinates, because no other target's graph contains them.
pluginManager.apply("app.cash.licensee")

extensions.configure<LicenseeExtension>("licensee") {
    allow("Apache-2.0")
    allow("MIT")
    // EPIC 22 / EPIC 46: Glance's and KSafe's repackaged protobuf (the `external-protobuf` artifacts) are the
    // only BSD-3-Clause artifacts in the graph. Permissive, attribution-only, no store implication, so the
    // class is allowed deliberately rather than "anything permissive". Every target ships them, not just Android.
    allow("BSD-3-Clause")
    allowUrl("https://opensource.org/license/mit")
    // EPIC 45: ConfigCat's Kotlin SDK and kotlin-semver are MIT, but their POMs name the licence by the URL of
    // the LICENSE file on GitHub instead of an SPDX id. Pinned to exactly these two URLs, not any
    // raw.githubusercontent.com path.
    allowUrl("https://raw.githubusercontent.com/configcat/kotlin-sdk/main/LICENSE")
    allowUrl("https://raw.githubusercontent.com/z4kn4fein/kotlin-semver/main/LICENSE")
}

require "minitest/autorun"
require_relative "../sentry_gate"

class SentryGateTest < Minitest::Test
  # The Kotlin `crashReleaseNamespace` actuals and this gate's NAMESPACES are kept in step by hand
  # (#431); reading the Kotlin source here is what makes a drift fail a PR check.
  def test_the_gates_namespaces_match_the_apps_per_platform_release_strings
    shared = File.expand_path("../../app/shared/src", __dir__)
    actual = ->(source_set) { File.read(File.join(shared, source_set, "kotlin/com/codingpit/muviss/CrashReleaseNamespace.kt"))[/= "([^"]+)"/, 1] }

    assert_equal SentryGate::NAMESPACES.fetch(:ios), actual.call("iosMain")
    assert_equal SentryGate::NAMESPACES.fetch(:android), actual.call("androidMain")
    %w[jvmMain webMain].each { |source_set| assert_equal SentryGate::NAMESPACES.fetch(:android), actual.call(source_set) }
  end

  def test_release_name_matches_what_the_app_reports
    assert_equal "com.codingpit.muviss@1.1.0+160", SentryGate.release_name(version_name: "1.1.0", version_code: 160)
  end

  def test_a_missing_version_code_names_no_release
    assert_nil SentryGate.release_name(version_name: "1.1.0", version_code: nil)
    assert_nil SentryGate.release_name(version_name: "", version_code: 160)
  end

  def test_no_release_name_holds_without_a_request
    assert_includes SentryGate.check(org: "codingpit", token: "t", release: nil), "no version code"
  end

  def test_query_asks_for_unhandled_issues_first_seen_in_the_release
    query = SentryGate.query("com.codingpit.muviss@1.1.0+160")
    assert_includes query, 'firstRelease:"com.codingpit.muviss@1.1.0+160"'
    assert_includes query, "error.unhandled:true"
  end

  def test_no_new_crash_lets_the_ramp_go_on
    assert_nil SentryGate.hold_reason(issues: [])
  end

  def test_any_new_crash_holds
    reason = SentryGate.hold_reason(issues: [{ "shortId" => "MUVISS-7" }])
    assert_includes reason, "1 new crash issue"
    assert_includes reason, "MUVISS-7"
  end

  def test_a_long_list_is_summarised
    issues = (1..5).map { |n| { "shortId" => "MUVISS-#{n}" } }
    reason = SentryGate.hold_reason(issues: issues)
    assert_includes reason, "MUVISS-3 and 2 more"
    refute_includes reason, "MUVISS-4"
  end

  def test_not_being_able_to_ask_holds
    assert_includes SentryGate.hold_reason(issues: [], error: "Sentry answered HTTP 403"), "HTTP 403"
  end

  def test_a_missing_token_holds_without_a_request
    reason = SentryGate.check(org: "codingpit", token: "", release: "x")
    assert_includes reason, "SENTRY_AUTH_TOKEN"
  end

  # #422: iOS reports its own namespace, so each store's gate reads its own builds.
  def test_ios_names_its_own_release_namespace
    assert_equal "com.codingpit.muviss.ios@1.1.0+1001000",
                 SentryGate.release_name(version_name: "1.1.0", version_code: 1_001_000, platform: :ios)
  end

  def test_android_keeps_the_string_the_gradle_plugin_stamps
    assert_equal "com.codingpit.muviss@1.1.0+1001000",
                 SentryGate.release_name(version_name: "1.1.0", version_code: 1_001_000, platform: :android)
  end

  def test_the_two_platforms_never_share_a_release
    refute_equal SentryGate.release_name(version_name: "1.1.0", version_code: 1, platform: :ios),
                 SentryGate.release_name(version_name: "1.1.0", version_code: 1, platform: :android)
  end

  def test_an_outage_is_told_apart_from_a_crash
    assert SentryGate.unreadable?(SentryGate.hold_reason(issues: [], error: "timeout"))
    refute SentryGate.unreadable?(SentryGate.hold_reason(issues: [{ "shortId" => "APP-1" }]))
    refute SentryGate.unreadable?(nil)
  end

  def test_an_unknown_platform_is_refused_rather_than_guessed
    assert_raises(ArgumentError) { SentryGate.release_name(version_name: "1.1.0", version_code: 1, platform: :web) }
  end
end

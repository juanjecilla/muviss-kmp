require "minitest/autorun"
require_relative "../sentry_gate"

class SentryGateTest < Minitest::Test
  def test_release_name_matches_what_the_app_reports
    assert_equal "com.codingpit.muviss@1.1.0+160", SentryGate.release_name(version_name: "1.1.0", version_code: 160)
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
end

# The iOS runner with fakes for every adapter (GitHub, git, App Store Connect,
# the lanes, Sentry): run with  ruby -Ifastlane fastlane/test/ios_promote_test.rb
require "minitest/autorun"
require "date"
require "stringio"
require_relative "../ios_promote"

class FakeIosGitHub
  attr_accessor :issues, :blockers_by_version, :merged_prs, :merge_error
  attr_reader :updates, :comments, :closed, :merges

  def initialize(issues: [])
    @issues = issues
    @updates = []
    @comments = []
    @closed = []
    @merges = []
    @blockers_by_version = Hash.new { [] }
    @merged_prs = {}
  end

  def release_issues = @issues
  def blockers(version) = @blockers_by_version[version]
  def open_prs_into(_branch) = []
  def run_conclusion(_sha, _workflow) = "success"
  def checks_green?(_sha) = true
  def merged_release_pr(version) = @merged_prs[version]
  def release_pr(_version) = 7
  def open_release_pr(_version) = 7

  def merge(pr, head)
    raise "merge refused" if @merge_error
    @merges << [pr, head]
    @merged_prs[pr] = pr
  end

  def update_issue(number, body)
    @updates << [number, body]
    @issues.each { |i| i["body"] = body if i["number"] == number }
  end

  def comment(number, body) = @comments << [number, body]
  def close_issue(number, body) = @closed << [number, body]
end

class FakeIosGit
  def latest_rc(_version) = "v1.1.0-rc1"
  def sha_of(_ref) = "rc-sha"
  def branch_head(_version) = "rc-sha"
  def version_code(_tag) = 1_001_000
  def draft_locales(_version) = []
end

# App Store Connect as the runner reads it. `status` is the 1.0.0 version's state;
# `phased` is its phased release's state.
class FakeAsc
  attr_accessor :versions, :phased, :build_listed, :beta_review
  attr_reader :calls

  def initialize
    @versions = {}
    @phased = {}
    @build_listed = true
    @beta_review = "APPROVED"
    @calls = []
  end

  def app_id(_bundle) = "app-1"

  def version(_app, version_string)
    row = @versions[version_string]
    row && { "id" => "v-#{version_string}", "state" => row }
  end

  def phased_release(version_id)
    state = @phased[version_id]
    state && { "id" => "p-#{version_id}", "state" => state }
  end

  def set_phased_state(phased_id, state)
    @calls << [:set_phased_state, phased_id, state]
  end

  def build_id(_app, _version, _code) = @build_listed ? "b-1" : nil
  def beta_review_state(_build) = @beta_review

  def remove_from_review(_app, version_id)
    @calls << [:remove_from_review, version_id]
    true
  end
end

class FakeIosLanes
  attr_reader :calls

  def initialize = @calls = []
  def beta_external(tag) = @calls << [:beta_external, tag]
  def submit(tag) = @calls << [:submit, tag]
end

class FakeIosSentry
  attr_accessor :hold_reason
  attr_reader :releases

  def initialize = @releases = []
  def hold(release)
    @releases << release
    @hold_reason
  end
end

class IosPromoteTest < Minitest::Test
  SUNDAY = Date.new(2026, 10, 18)

  def issue_for(state, number: 5)
    { "number" => number, "title" => ReleaseStages.title(state["version"]), "body" => ReleaseStages.render(state), "state" => "open" }
  end

  def internal_state
    ReleaseStages.new_state("1.1.0", "2026-10-12")
  end

  def beta_state(merged: false)
    state = internal_state
    state["ios"] = state["ios"].merge("stage" => "beta", "beta_done_on" => "2026-10-18", "rc_tag" => "v1.1.0-rc1")
    state["stage"] = "beta"
    state["beta_done_on"] = "2026-10-18"
    state["merged_on"] = "2026-10-25" if merged
    state
  end

  def production_state
    state = beta_state
    state["ios"] = state["ios"].merge("stage" => "production", "production_started_on" => "2026-10-25")
    state
  end

  def build(issues:, asc: FakeAsc.new, sentry: FakeIosSentry.new, apply: true, today: SUNDAY, github: nil, git: FakeIosGit.new, lanes: FakeIosLanes.new)
    out = StringIO.new
    gh = github || FakeIosGitHub.new(issues: issues)
    promotion = IosPromotion.new(github: gh, git: git, asc: asc, lanes: lanes, sentry: sentry, today: today, apply: apply, out: out)
    [promotion, gh, asc, lanes, sentry, out]
  end

  # Sunday beta

  def test_the_sunday_beta_sends_the_rc_to_external_testing_and_records_it
    issue = issue_for(internal_state)
    promotion, gh, _asc, lanes, _sentry, _out = build(issues: [issue])
    outcomes = promotion.run
    assert_equal [[:beta_external, "v1.1.0-rc1"]], lanes.calls
    assert_equal :done, outcomes.first.status
    assert_equal "beta", ReleaseStages.ios_of(ReleaseStages.parse(gh.updates.last[1]))["stage"]
  end

  def test_the_beta_step_is_held_by_the_ios_crash_gate_only_for_ios
    sentry = FakeIosSentry.new
    sentry.hold_reason = "2 new crash issue(s) first seen in this release"
    promotion, _gh, _asc, lanes, _sentry, _out = build(issues: [issue_for(internal_state)], sentry: sentry)
    outcome = promotion.run.first
    assert_equal :held, outcome.status
    assert_empty lanes.calls
    assert_equal ["com.codingpit.muviss.ios@1.1.0+1001000"], sentry.releases
  end

  def test_a_dry_run_changes_nothing
    promotion, gh, _asc, lanes, _sentry, _out = build(issues: [issue_for(internal_state)], apply: false)
    outcome = promotion.run.first
    assert_equal :would_do, outcome.status
    assert_empty lanes.calls
    assert_empty gh.updates
  end

  def test_a_version_without_an_ios_track_is_left_alone
    state = internal_state
    state.delete("ios")
    promotion, _gh, _asc, lanes, _sentry, _out = build(issues: [issue_for(state)])
    assert_empty promotion.run
    assert_empty lanes.calls
  end

  # Production (Sunday + 7) with the merge and Beta App Review

  def test_production_waits_for_the_merge_into_main
    promotion, _gh, _asc, lanes, _sentry, _out = build(issues: [issue_for(beta_state)], today: SUNDAY + 7)
    outcome = promotion.run.first
    assert_equal :held, outcome.status
    assert_includes outcome.reasons.join, "is not merged into main yet"
    assert_empty lanes.calls
  end

  def test_production_submits_once_merged_and_approved
    promotion, gh, _asc, lanes, _sentry, _out = build(issues: [issue_for(beta_state(merged: true))], today: SUNDAY + 7)
    outcome = promotion.run.first
    assert_equal :done, outcome.status
    assert_equal [[:submit, "v1.1.0-rc1"]], lanes.calls
    assert_equal "production", ReleaseStages.ios_of(ReleaseStages.parse(gh.updates.last[1]))["stage"]
  end

  def test_production_holds_while_beta_review_has_not_approved
    asc = FakeAsc.new
    asc.beta_review = "WAITING_FOR_REVIEW"
    promotion, _gh, _asc, lanes, _sentry, _out = build(issues: [issue_for(beta_state(merged: true))], asc: asc, today: SUNDAY + 7)
    assert_includes promotion.run.first.reasons.join, "Beta App Review has not approved"
    assert_empty lanes.calls
  end

  def test_a_version_in_review_is_removed_from_review_before_the_next_submits
    asc = FakeAsc.new
    asc.versions = { "1.0.0" => "IN_REVIEW" }
    # 1.0.0 is in the train at production, so the supersede knows about it.
    issues = [issue_for(beta_state(merged: true)), issue_for(production_state_for("1.0.0"), number: 4)]
    promotion, _gh, _asc2, lanes, _sentry, _out = build(issues: issues, asc: asc, today: SUNDAY + 7)
    outcome = promotion.run.find { |o| o.version == "1.1.0" }
    assert_equal :done, outcome.status
    assert_includes asc.calls.map(&:first), :remove_from_review
    assert_equal [[:submit, "v1.1.0-rc1"]], lanes.calls
  end

  def test_a_pending_release_previous_version_holds_the_submit
    asc = FakeAsc.new
    asc.versions = { "1.0.0" => "PENDING_DEVELOPER_RELEASE" }
    issues = [issue_for(beta_state(merged: true)), issue_for(production_state_for("1.0.0"), number: 4)]
    promotion, _gh, _asc2, lanes, _sentry, _out = build(issues: issues, asc: asc, today: SUNDAY + 7)
    outcome = promotion.run.find { |o| o.version == "1.1.0" }
    assert_equal :held, outcome.status
    assert_includes outcome.reasons.join, "1.0.0"
    assert_empty lanes.calls
  end

  def production_state_for(version)
    state = ReleaseStages.new_state(version, "2026-10-05")
    state["ios"] = state["ios"].merge("stage" => "production", "rc_tag" => "v#{version}-rc3", "beta_done_on" => "2026-10-11",
                                      "production_started_on" => "2026-10-18")
    state["stage"] = "production"
    state
  end

  # The daily ramp

  def ramp_issue(phased_state)
    state = production_state
    issue = issue_for(state)
    asc = FakeAsc.new
    asc.versions = { "1.1.0" => "READY_FOR_SALE" }
    asc.phased = { "v-1.1.0" => phased_state }
    [issue, asc]
  end

  def test_the_daily_ramp_pauses_a_live_rollout_when_the_ios_gate_fails
    issue, asc = ramp_issue("ACTIVE")
    sentry = FakeIosSentry.new
    sentry.hold_reason = "1 new crash issue(s) first seen in this release"
    promotion, gh, _asc2, _lanes, _s, _out = build(issues: [issue], asc: asc, sentry: sentry)
    promotion.ramp
    assert_includes asc.calls.map(&:last), "PAUSED"
    track = ReleaseStages.ios_of(ReleaseStages.parse(gh.updates.last[1]))
    assert_equal "gate", track["ramp"]["paused_by"]
  end

  def test_the_daily_ramp_resumes_its_own_pause_once_the_gate_passes
    issue, asc = ramp_issue("PAUSED")
    state = ReleaseStages.parse(issue["body"])
    state["ios"]["ramp"] = { "phased_state" => "PAUSED", "paused_by" => "gate", "paused_on" => "2026-10-17", "paused_days" => 0 }
    issue["body"] = ReleaseStages.render(state)
    promotion, gh, _asc2, _l, _s, _o = build(issues: [issue], asc: asc)
    promotion.ramp
    assert_equal [[:set_phased_state, "p-v-1.1.0", "ACTIVE"]], asc.calls
    assert_nil ReleaseStages.ios_of(ReleaseStages.parse(gh.updates.last[1]))["ramp"]["paused_by"]
  end

  def test_the_ramp_never_touches_a_brake_pause
    issue, asc = ramp_issue("PAUSED")
    state = ReleaseStages.parse(issue["body"])
    state["ios"]["ramp"] = { "phased_state" => "PAUSED", "paused_by" => "brake", "paused_on" => "2026-10-17", "paused_days" => 0 }
    issue["body"] = ReleaseStages.render(state)
    promotion, _gh, _asc2, _l, _s, _o = build(issues: [issue], asc: asc)
    promotion.ramp
    assert_empty asc.calls
  end

  def test_a_dry_ramp_pauses_nothing
    issue, asc = ramp_issue("ACTIVE")
    sentry = FakeIosSentry.new
    sentry.hold_reason = "crash"
    promotion, _gh, _asc2, _l, _s, out = build(issues: [issue], asc: asc, sentry: sentry, apply: false)
    promotion.ramp
    assert_empty asc.calls
    assert_includes out.string, "would pause"
  end

  def test_an_unreadable_sentry_pauses_nothing
    issue, asc = ramp_issue("ACTIVE")
    sentry = FakeIosSentry.new
    sentry.hold_reason = SentryGate.unreadable_reason("SocketError")
    promotion, _gh, _asc2, _l, _s, out = build(issues: [issue], asc: asc, sentry: sentry)
    outcome = promotion.ramp.first
    assert_empty asc.calls
    assert_includes out.string, "left as it is"
    assert_equal :not_due, outcome.status
  end

  def test_a_completed_phased_release_closes_the_ios_track
    issue, asc = ramp_issue("COMPLETE")
    promotion, gh, _asc2, _l, _s, _o = build(issues: [issue], asc: asc)
    promotion.ramp
    assert_equal "done", ReleaseStages.ios_of(ReleaseStages.parse(gh.updates.last[1]))["stage"]
  end

  # The brake

  def test_the_brake_pauses_and_the_gate_will_not_resume_it
    issue, asc = ramp_issue("ACTIVE")
    promotion, gh, _asc2, _l, _s, _o = build(issues: [issue], asc: asc)
    promotion.brake("pause", "1.1.0")
    assert_equal [[:set_phased_state, "p-v-1.1.0", "PAUSED"]], asc.calls
    assert_equal "brake", ReleaseStages.ios_of(ReleaseStages.parse(gh.updates.last[1]))["ramp"]["paused_by"]
  end

  # The iOS-only override (ADR 0033)

  def test_the_override_merges_submits_and_marks_play_as_waiting
    promotion, gh, _asc, lanes, _s, _o = build(issues: [issue_for(beta_state)], today: SUNDAY + 2)
    outcome = promotion.override("1.1.0")
    assert_equal :done, outcome.status
    assert_equal [[7, "rc-sha"]], gh.merges
    assert_equal [[:submit, "v1.1.0-rc1"]], lanes.calls
    marker = ReleaseStages.parse(gh.updates.first[1])
    assert_equal "2026-10-20", marker["ios_override_on"]
  end

  def test_the_override_does_not_submit_when_the_merge_fails
    github = FakeIosGitHub.new(issues: [issue_for(beta_state)])
    github.merge_error = true
    promotion, _gh, _asc, lanes, _s, _o = build(issues: nil, github: github, today: SUNDAY + 2)
    outcome = promotion.override("1.1.0")
    assert_equal :held, outcome.status
    assert_empty lanes.calls
  end

  def test_the_override_is_refused_before_the_ios_beta_was_submitted
    promotion, gh, _asc, lanes, _s, _o = build(issues: [issue_for(internal_state)], today: SUNDAY + 9)
    assert_equal :held, promotion.override("1.1.0").status
    assert_empty gh.merges
    assert_empty lanes.calls
  end

  def test_play_does_not_retry_a_version_the_ios_override_merged
    state = beta_state(merged: true)
    state["ios_override_on"] = "2026-10-18"
    assert_nil ReleaseGates.due_step(state, SUNDAY + 14)
    state.delete("ios_override_on")
    assert_equal :play_retry, ReleaseGates.due_step(state, SUNDAY + 14)
  end
end

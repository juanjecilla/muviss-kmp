# The iOS track's decisions (ADR 0033), pure: run with
#   ruby -Ifastlane fastlane/test/ios_train_test.rb
require "minitest/autorun"
require "date"
require_relative "../ios_train"

class IosTrainTest < Minitest::Test
  SUNDAY = Date.new(2026, 10, 18)

  def state_with(stage:, beta_done_on: nil, ramp: nil, rc_tag: "v1.1.0-rc1")
    state = ReleaseStages.new_state("1.1.0", "2026-10-12")
    state["ios"] = state["ios"].merge("stage" => stage, "beta_done_on" => beta_done_on, "rc_tag" => rc_tag,
                                      "production_started_on" => (stage == "production" ? "2026-10-25" : nil))
    state["ios"]["ramp"] = ramp if ramp
    state
  end

  def approved_facts(overrides = {})
    {
      version: "1.1.0", rc_tag: "v1.1.0-rc1", rc_code: 1_001_000, blockers: [], fix_prs: [], head: "abc", head_is_rc: true,
      rc_built: true, ci_green: true, ios_uploaded: true, sentry_ios: nil,
      beta_review: "APPROVED", merged: true, ios_beta_rc: "v1.1.0-rc1", previous_version: nil, previous_status: nil,
    }.merge(overrides)
  end

  # Due steps

  def test_beta_is_due_on_the_sunday_for_an_internal_track
    assert_equal :beta, IosTrain.due_step(state_with(stage: "internal"), SUNDAY)
    assert_nil IosTrain.due_step(state_with(stage: "internal"), Date.new(2026, 10, 17))
  end

  def test_production_is_due_a_week_after_the_beta_was_submitted
    state = state_with(stage: "beta", beta_done_on: "2026-10-18")
    assert_nil IosTrain.due_step(state, Date.new(2026, 10, 24))
    assert_equal :production, IosTrain.due_step(state, Date.new(2026, 10, 25))
  end

  def test_a_version_without_an_ios_track_has_no_step
    state = ReleaseStages.new_state("1.0.0", "2026-09-07")
    state.delete("ios")
    assert_nil IosTrain.due_step(state, SUNDAY)
  end

  def test_a_finished_ios_track_has_no_step
    assert_nil IosTrain.due_step(state_with(stage: "done"), SUNDAY + 30)
  end

  # Supersede (ADR 0033 rule (a))

  def test_a_version_in_review_is_removed_from_review
    assert_equal :remove_from_review, IosTrain.supersede_plan("WAITING_FOR_REVIEW")[:action]
    assert_equal :remove_from_review, IosTrain.supersede_plan("IN_REVIEW")[:action]
  end

  def test_a_rejected_version_is_recorded_as_superseded_not_resubmitted
    assert_equal :record_superseded, IosTrain.supersede_plan("REJECTED")[:action]
  end

  def test_a_version_pending_developer_release_is_never_superseded
    plan = IosTrain.supersede_plan("PENDING_DEVELOPER_RELEASE")
    assert_equal :hold, plan[:action]
    assert_includes plan[:reason], "Pending Developer Release"
  end

  def test_a_live_or_idle_previous_version_needs_nothing
    assert_equal :none, IosTrain.supersede_plan("READY_FOR_SALE")[:action]
    assert_equal :none, IosTrain.supersede_plan("PREPARE_FOR_SUBMISSION")[:action]
    assert_equal :none, IosTrain.supersede_plan(nil)[:action]
  end

  def test_an_unknown_status_holds_rather_than_acting
    assert_equal :hold, IosTrain.supersede_plan("SOMETHING_NEW")[:action]
  end

  # Holds

  def test_every_gate_passing_holds_nothing
    assert_empty IosTrain.holds(step: :beta, facts: approved_facts)
    assert_empty IosTrain.holds(step: :production, facts: approved_facts)
  end

  def test_a_shared_gate_holds_the_ios_step_too
    facts = approved_facts(blockers: ["#77 crash on launch"])
    assert_includes IosTrain.holds(step: :beta, facts: facts), "release-blocker: #77 crash on launch"
  end

  def test_an_ios_crash_holds_only_through_the_ios_sentry_reason
    facts = approved_facts(sentry_ios: "2 new crash issue(s) first seen in this release")
    assert_equal ["iOS Sentry: 2 new crash issue(s) first seen in this release"], IosTrain.holds(step: :beta, facts: facts)
  end

  def test_an_rc_not_in_testflight_holds_with_the_reason
    reasons = IosTrain.holds(step: :beta, facts: approved_facts(ios_uploaded: false))
    assert_includes reasons.join, "is not in TestFlight"
  end

  def test_an_unreadable_testflight_holds
    assert_includes IosTrain.holds(step: :beta, facts: approved_facts(ios_uploaded: nil)).join, "could not be read from App Store Connect"
  end

  def test_production_waits_for_beta_review_approval
    waiting = IosTrain.holds(step: :production, facts: approved_facts(beta_review: "WAITING_FOR_REVIEW"))
    assert_includes waiting.join, "Beta App Review has not approved"
    assert_includes IosTrain.holds(step: :production, facts: approved_facts(beta_review: nil)).join, "could not be read"
  end

  def test_production_waits_for_the_merge_into_main
    reasons = IosTrain.holds(step: :production, facts: approved_facts(merged: false))
    assert_includes reasons.join, "is not merged into main yet"
  end

  def test_production_holds_while_the_previous_version_is_pending_release
    facts = approved_facts(previous_version: "1.0.0", previous_status: "PENDING_DEVELOPER_RELEASE")
    assert_includes IosTrain.holds(step: :production, facts: facts).join, "Pending Developer Release"
  end

  def test_production_does_not_hold_for_a_previous_version_in_review
    facts = approved_facts(previous_version: "1.0.0", previous_status: "IN_REVIEW")
    assert_empty IosTrain.holds(step: :production, facts: facts)
  end

  def test_a_newer_rc_than_the_one_on_beta_holds_production
    facts = approved_facts(rc_tag: "v1.1.0-rc2", ios_beta_rc: "v1.1.0-rc1")
    assert_includes IosTrain.holds(step: :production, facts: facts).join, "cut a new iOS beta"
  end

  def test_an_unknown_versionCode_holds
    assert_includes IosTrain.holds(step: :beta, facts: approved_facts(rc_code: nil)).join, "could not be read"
  end

  # Phased-release ramp (daily)

  def test_a_crash_gate_failure_pauses_a_live_rollout
    decision = IosTrain.ramp_decision(gate_hold: "3 new crash issue(s)", phased: "ACTIVE", track: nil, today: SUNDAY)
    assert_equal :pause, decision[:action]
  end

  def test_a_passing_gate_leaves_a_live_rollout_alone
    assert_equal :none, IosTrain.ramp_decision(gate_hold: nil, phased: "ACTIVE", track: nil, today: SUNDAY)[:action]
  end

  def test_nothing_is_paused_outside_a_live_rollout
    assert_equal :none, IosTrain.ramp_decision(gate_hold: "crash", phased: "COMPLETE", track: nil, today: SUNDAY)[:action]
    assert_equal :none, IosTrain.ramp_decision(gate_hold: "crash", phased: nil, track: nil, today: SUNDAY)[:action]
  end

  def test_the_gate_resumes_only_a_pause_it_made_once_it_passes
    track = { "ramp" => { "paused_by" => "gate", "paused_on" => "2026-10-19", "paused_days" => 0 } }
    assert_equal :resume, IosTrain.ramp_decision(gate_hold: nil, phased: "PAUSED", track: track, today: SUNDAY)[:action]
    assert_equal :none, IosTrain.ramp_decision(gate_hold: "crash", phased: "PAUSED", track: track, today: SUNDAY)[:action]
  end

  def test_the_gate_never_resumes_the_owners_brake
    track = { "ramp" => { "paused_by" => "brake", "paused_on" => "2026-10-19", "paused_days" => 0 } }
    decision = IosTrain.ramp_decision(gate_hold: nil, phased: "PAUSED", track: track, today: SUNDAY)
    assert_equal :none, decision[:action]
    assert_includes decision[:reason], "only the owner resumes"
  end

  def test_the_pause_budget_is_cumulative_across_pauses
    under = { "ramp" => { "paused_by" => nil, "paused_on" => nil, "paused_days" => 28 } }
    assert_equal :pause, IosTrain.ramp_decision(gate_hold: "crash", phased: "ACTIVE", track: under, today: SUNDAY)[:action]
    spent = { "ramp" => { "paused_by" => nil, "paused_on" => nil, "paused_days" => 30 } }
    assert_equal :budget, IosTrain.ramp_decision(gate_hold: "crash", phased: "ACTIVE", track: spent, today: SUNDAY)[:action]
  end

  def test_days_in_an_open_pause_count_toward_the_budget
    track = { "ramp" => { "paused_by" => "gate", "paused_on" => "2026-09-20", "paused_days" => 5 } }
    assert_equal 33, IosTrain.paused_days_used(track["ramp"], SUNDAY)
  end

  def test_only_apples_paused_spelling_reads_as_paused
    track = { "ramp" => { "paused_by" => "gate", "paused_on" => "2026-10-19", "paused_days" => 0 } }
    assert_equal :resume, IosTrain.ramp_decision(gate_hold: nil, phased: "PAUSED", track: track, today: SUNDAY)[:action]
    assert_equal :none, IosTrain.ramp_decision(gate_hold: nil, phased: "PAUSE", track: track, today: SUNDAY)[:action]
  end

  def test_a_gate_pause_that_still_holds_hands_over_at_the_budget
    open = { "ramp" => { "paused_by" => "gate", "paused_on" => "2026-09-10", "paused_days" => 0 } }
    assert_equal :budget, IosTrain.ramp_decision(gate_hold: "crash", phased: "PAUSED", track: open, today: SUNDAY)[:action]
    recent = { "ramp" => { "paused_by" => "gate", "paused_on" => "2026-10-10", "paused_days" => 0 } }
    assert_equal :none, IosTrain.ramp_decision(gate_hold: "crash", phased: "PAUSED", track: recent, today: SUNDAY)[:action]
    assert_equal :resume, IosTrain.ramp_decision(gate_hold: nil, phased: "PAUSED", track: open, today: SUNDAY)[:action]
  end

  # Brake

  def test_the_brake_pauses_a_live_rollout_and_refuses_otherwise
    assert_equal :pause, IosTrain.brake_decision(action: "pause", phased: "ACTIVE", track: nil, today: SUNDAY)[:action]
    assert_equal :none, IosTrain.brake_decision(action: "pause", phased: "COMPLETE", track: nil, today: SUNDAY)[:action]
    assert_equal :none, IosTrain.brake_decision(action: "pause", phased: "PAUSED", track: nil, today: SUNDAY)[:action]
  end

  def test_the_brake_resumes_any_pause
    assert_equal :resume, IosTrain.brake_decision(action: "resume", phased: "PAUSED", track: nil, today: SUNDAY)[:action]
    assert_equal :none, IosTrain.brake_decision(action: "resume", phased: "ACTIVE", track: nil, today: SUNDAY)[:action]
  end

  def test_an_unknown_brake_action_does_nothing
    assert_equal :none, IosTrain.brake_decision(action: "halt", phased: "ACTIVE", track: nil, today: SUNDAY)[:action]
  end

  # The ramp's bookkeeping

  def test_a_pause_then_a_resume_adds_the_days_paused
    ramp = { "phased_state" => "ACTIVE", "paused_by" => nil, "paused_on" => nil, "paused_days" => 2 }
    paused = IosTrain.ramp_after(ramp, action: :pause, by: "gate", today: SUNDAY)
    assert_equal "PAUSED", paused["phased_state"]
    resumed = IosTrain.ramp_after(paused, action: :resume, by: nil, today: SUNDAY + 3)
    assert_equal 5, resumed["paused_days"]
    assert_nil resumed["paused_by"]
    assert_equal "ACTIVE", resumed["phased_state"]
  end
end

require "minitest/autorun"
require "date"
require "json"
require "tmpdir"
require_relative "../release_stages"

class ReleaseStagesTest < Minitest::Test
  MONDAY = Date.new(2026, 10, 12)

  def test_beta_is_the_sunday_after_a_monday_cut
    assert_equal Date.new(2026, 10, 18), ReleaseStages.beta_on(MONDAY)
    assert_equal "Sunday", ReleaseStages.beta_on(MONDAY).strftime("%A")
  end

  def test_a_sunday_cut_still_gets_the_following_sunday
    assert_equal Date.new(2026, 10, 18), ReleaseStages.beta_on(Date.new(2026, 10, 11))
  end

  def test_production_is_a_week_after_the_beta_that_actually_happened
    assert_equal Date.new(2026, 10, 25), ReleaseStages.production_on(Date.new(2026, 10, 18))
    assert_equal Date.new(2026, 10, 26), ReleaseStages.production_on(Date.new(2026, 10, 19))
  end

  def test_schedule_names_the_three_dates
    assert_equal(
      { "cut_on" => "2026-10-12", "beta_on" => "2026-10-18", "production_on" => "2026-10-25" },
      ReleaseStages.schedule("2026-10-12"),
    )
  end

  def test_a_fresh_cut_is_internal_with_no_holds
    state = ReleaseStages.new_state("1.1.0", "2026-10-12")
    assert_equal "internal", state["stage"]
    assert_equal [], state["holds"]
    assert_equal "2026-10-18", state["beta_on"]
    assert_nil state["rc_tag"]
  end

  def test_the_marker_round_trips_through_the_body
    state = ReleaseStages.new_state("1.1.0", "2026-10-12")
    body = ReleaseStages.render(state)
    assert_equal state, ReleaseStages.parse(body)
    assert_equal "internal", ReleaseStages.stage_of(body)
  end

  def test_a_body_without_the_marker_has_no_state
    assert_nil ReleaseStages.parse("just a hand-written issue")
    assert_nil ReleaseStages.stage_of(nil)
  end

  def test_render_shows_the_schedule_the_holds_and_the_milestone
    state = ReleaseStages.new_state("1.1.0", "2026-10-12")
    state["holds"] = ["open PR #12 into release/1.1.0"]
    body = ReleaseStages.render(state)
    assert_includes body, "# Release 1.1.0"
    assert_includes body, "Milestone: `1.1.0`"
    assert_includes body, "| Open beta | 2026-10-18 | pending |"
    assert_includes body, "| Production (20%, then the daily ramp) | 2026-10-25 | pending |"
    assert_includes body, "- open PR #12 into release/1.1.0"
    assert_includes body, "Held:"
  end

  def test_render_marks_a_finished_step_with_its_date
    state = ReleaseStages.new_state("1.1.0", "2026-10-12")
    state.merge!("stage" => "beta", "beta_done_on" => "2026-10-18")
    body = ReleaseStages.render(state)
    assert_includes body, "| Internal (cut) | 2026-10-12 | done 2026-10-12 |"
    assert_includes body, "| Open beta | 2026-10-18 | current |"
    assert_includes body, "No holds."
  end

  def test_a_finished_release_shows_every_step_done
    state = ReleaseStages.new_state("1.1.0", "2026-10-12")
    state.merge!("stage" => "done", "beta_done_on" => "2026-10-18", "production_started_on" => "2026-10-25")
    body = ReleaseStages.render(state)
    assert_includes body, "| Production (20%, then the daily ramp) | 2026-10-25 | done 2026-10-25 |"
    # The Play table is the part this test is about; the iOS section that follows
    # has its own steps, which nothing has moved yet in this fixture.
    refute_includes body.split("iOS, the same three stages").first, "pending"
  end

  def test_the_train_holds_while_a_version_is_in_internal
    issues = [{ "title" => "Release 1.1.0", "body" => ReleaseStages.render(ReleaseStages.new_state("1.1.0", "2026-10-12")) }]
    plan = ReleaseStages.plan(open: ["1.1.0"], issues: issues)
    assert_match(/1\.1\.0 still in internal/, plan["hold"])
    assert_equal [], plan["adopt"]
  end

  def test_the_train_cuts_once_the_open_version_is_in_beta
    state = ReleaseStages.new_state("1.1.0", "2026-10-12").merge("stage" => "beta")
    issues = [{ "title" => "Release 1.1.0", "body" => ReleaseStages.render(state) }]
    plan = ReleaseStages.plan(open: ["1.1.0"], issues: issues)
    assert_nil plan["hold"]
    assert_equal({ "1.1.0" => "beta" }, plan["stages"])
  end

  def test_an_open_beta_and_a_held_internal_hold_together_on_the_internal_one
    beta = ReleaseStages.new_state("1.1.0", "2026-10-05").merge("stage" => "beta")
    internal = ReleaseStages.new_state("1.2.0", "2026-10-12")
    issues = [
      { "title" => "Release 1.1.0", "body" => ReleaseStages.render(beta) },
      { "title" => "Release 1.2.0", "body" => ReleaseStages.render(internal) },
    ]
    plan = ReleaseStages.plan(open: ["1.1.0", "1.2.0"], issues: issues)
    assert_match(/\A1\.2\.0 still in internal/, plan["hold"])
    refute_match(/1\.1\.0/, plan["hold"])
  end

  def test_a_branch_with_no_issue_is_adopted_and_counts_as_internal
    plan = ReleaseStages.plan(open: ["1.1.0"], issues: [])
    assert_equal ["1.1.0"], plan["adopt"]
    assert_equal({ "1.1.0" => "internal" }, plan["stages"])
    assert_match(/1\.1\.0 still in internal/, plan["hold"])
  end

  def test_a_nearby_title_is_not_the_same_version
    state = ReleaseStages.new_state("1.1.0", "2026-10-12").merge("stage" => "beta")
    issues = [{ "title" => "Release 1.1.01", "body" => ReleaseStages.render(state) }]
    plan = ReleaseStages.plan(open: ["1.1.0"], issues: issues)
    assert_equal ["1.1.0"], plan["adopt"]
  end

  def test_a_hand_broken_marker_holds_the_train_and_names_the_issue
    broken = { "title" => "Release 1.1.0", "body" => "<!-- muviss-release: {\"version\":\"1.1.0\",\"stage\":\"beta\",} -->" }
    plan = ReleaseStages.plan(open: ["1.1.0"], issues: [broken])
    assert_equal ["1.1.0"], plan["unreadable"]
    assert_includes plan["hold"], "Release 1.1.0"
    assert_empty plan["adopt"], "an unreadable issue must not be adopted as a second one"
  end

  def test_no_open_release_means_no_hold
    plan = ReleaseStages.plan(open: [], issues: [])
    assert_nil plan["hold"]
    assert_equal [], plan["adopt"]
  end

  def test_the_cli_plans_from_a_json_file
    path = File.join(Dir.tmpdir, "release-stages-#{$$}.json")
    File.write(path, JSON.generate("open" => ["1.1.0"], "issues" => []))
    out = `ruby #{File.expand_path("../release_stages.rb", __dir__)} plan #{path}`
    assert_equal ["1.1.0"], JSON.parse(out)["adopt"]
  ensure
    File.delete(path) if path && File.exist?(path)
  end

  # ADR 0033: markers written before iOS joined the train have no "ios" object.
  OLD_PLAY_ONLY_MARKER = '<!-- muviss-release: {"cut_on":"2026-09-07","beta_on":"2026-09-13","production_on":"2026-09-20",' \
    '"version":"1.0.0","stage":"beta","rc_tag":"v1.0.0-rc2","beta_done_on":"2026-09-13","production_started_on":null,' \
    '"completed_on":null,"holds":["open PR #9 into release/1.0.0"]} -->'

  def test_a_play_only_marker_still_parses_and_has_no_ios_track
    body = "#{OLD_PLAY_ONLY_MARKER}\n\n# Release 1.0.0\n"
    state = ReleaseStages.parse(body)
    assert_equal "beta", state["stage"]
    assert_equal "v1.0.0-rc2", state["rc_tag"]
    assert_nil ReleaseStages.ios_of(state)
    assert_equal "beta", ReleaseStages.stage_of(body)
  end

  def test_a_play_only_marker_renders_its_iOS_section_as_not_on_the_train
    body = ReleaseStages.render(ReleaseStages.parse(OLD_PLAY_ONLY_MARKER))
    assert_includes body, "iOS: not on this train (cut before ADR 0033)"
    assert_includes body, "Held:\n- open PR #9 into release/1.0.0"
    refute_includes body, "Held on iOS:"
  end

  def test_a_new_version_carries_an_ios_track_at_internal
    track = ReleaseStages.ios_of(ReleaseStages.new_state("1.1.0", "2026-10-12"))
    assert_equal "internal", track["stage"]
    assert_nil track["beta_done_on"]
    assert_equal [], track["holds"]
  end

  def test_an_ios_track_with_missing_keys_reads_with_defaults
    state = ReleaseStages.new_state("1.1.0", "2026-10-12")
    state["ios"] = { "stage" => "beta", "beta_done_on" => "2026-10-18" }
    track = ReleaseStages.ios_of(state)
    assert_equal "beta", track["stage"]
    assert_equal [], track["holds"]
    assert_equal 0, track["ramp"]["paused_days"]
  end

  def test_ios_holds_are_listed_apart_from_play_holds
    state = ReleaseStages.new_state("1.1.0", "2026-10-12")
    state["holds"] = ["open PR #12 into release/1.1.0"]
    state["ios"]["holds"] = ["Sentry: 2 new crash issue(s)"]
    body = ReleaseStages.render(state)
    assert_includes body, "Held:\n- open PR #12 into release/1.1.0"
    assert_includes body, "Held on iOS:\n- Sentry: 2 new crash issue(s)"
  end

  def test_the_ios_rows_name_the_beta_and_production_dates_once_they_happened
    state = ReleaseStages.new_state("1.1.0", "2026-10-12")
    state["ios"].merge!("stage" => "production", "rc_tag" => "v1.1.0-rc1", "beta_done_on" => "2026-10-18",
                        "production_started_on" => "2026-10-25")
    body = ReleaseStages.render(state)
    assert_includes body, "| App Store (7-day phased release) | 2026-10-25 | current |"
    assert_includes body, "| TestFlight external (Beta App Review) | 2026-10-18 | done 2026-10-18 |"
    assert_includes body, "Latest iOS RC: v1.1.0-rc1."
  end

  def test_the_marker_round_trips_with_the_ios_track
    state = ReleaseStages.new_state("1.1.0", "2026-10-12")
    state["ios"]["ramp"] = { "phased_state" => "PAUSED", "paused_by" => "gate", "paused_on" => "2026-10-26", "paused_days" => 3 }
    assert_equal state, ReleaseStages.parse(ReleaseStages.render(state))
    assert_includes ReleaseStages.render(state), "paused by gate since 2026-10-26 (3 of 30 paused days used)"
  end
end

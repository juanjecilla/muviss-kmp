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
    refute_includes body, "pending"
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
end

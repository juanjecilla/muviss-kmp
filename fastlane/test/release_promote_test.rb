require "minitest/autorun"
require "date"
require "json"
require "stringio"
require_relative "../release_promote"

# Fakes for the four adapters. Each records what it was asked; a value set to
# an Exception makes that call raise, which is how a failed read or write shows.
class FakeGitHub
  attr_reader :calls
  attr_accessor :issues, :blockers_by_version, :fix_prs_by_branch, :conclusions, :green,
                :release_prs, :merge_error, :merged_prs

  def initialize(issues: [])
    @issues = issues
    @calls = []
    @blockers_by_version = Hash.new { [] }
    @fix_prs_by_branch = Hash.new { [] }
    @conclusions = Hash.new("success")
    @green = Hash.new(true)
    @release_prs = {}
    @merge_error = nil
    @merged_prs = {}
  end

  def merged_release_pr(version)
    @merged_prs[version]
  end

  def release_issues
    @calls << [:release_issues]
    @issues.map(&:dup)
  end

  def blockers(version)
    raise @blockers_by_version[version] if @blockers_by_version[version].is_a?(Exception)

    @blockers_by_version[version]
  end

  def open_prs_into(branch)
    @fix_prs_by_branch[branch]
  end

  def run_conclusion(sha, workflow)
    @calls << [:run_conclusion, sha, workflow]
    @conclusions[sha]
  end

  def checks_green?(sha)
    @green[sha]
  end

  def release_pr(version)
    @release_prs[version]
  end

  def open_release_pr(version)
    @calls << [:open_release_pr, version]
    @release_prs[version] = 40 + @calls.count { |c| c.first == :open_release_pr }
  end

  def merge(pr, head_sha)
    @calls << [:merge, pr, head_sha]
    raise @merge_error if @merge_error

    true
  end

  def update_issue(number, body)
    @calls << [:update_issue, number]
    @issues.find { |i| i["number"] == number }["body"] = body
  end

  def comment(number, body)
    @calls << [:comment, number, body]
  end

  def close_issue(number, body)
    @calls << [:close_issue, number, body]
    issue = @issues.find { |i| i["number"] == number }
    issue["closed"] = true
    issue["state"] = "CLOSED"
  end
end

class FakeGit
  attr_accessor :rc_by_version, :heads, :shas, :drafts, :codes, :raise_on_draft

  def initialize
    @rc_by_version = {}
    @heads = {}
    @shas = {}
    @drafts = Hash.new { [] }
    @codes = {}
  end

  def latest_rc(version)
    @rc_by_version[version]
  end

  def sha_of(ref)
    @shas.fetch(ref)
  end

  def branch_head(version)
    @heads.fetch(version)
  end

  def version_code(tag)
    @codes.fetch(tag)
  end

  def draft_locales(version)
    raise @raise_on_draft if @raise_on_draft

    @drafts[version]
  end
end

class FakePlay
  attr_reader :calls
  attr_accessor :error

  def initialize
    @calls = []
  end

  def promote_to_beta(code)
    raise @error if @error

    @calls << code
  end

  def promote_to_production(code)
    raise @error if @error

    @calls << [:production, code]
  end

  # Unset tracks carry whatever is asked, so only the tests about #349 set one.
  attr_accessor :beta_latest, :track_error

  def carries?(track, code)
    raise @track_error if @track_error
    return true if track != "beta" || @beta_latest.nil?

    @beta_latest == code
  end
end

class FakeSentry
  attr_reader :asked
  attr_accessor :answer

  def initialize
    @asked = []
  end

  def hold(release)
    @asked << release
    @answer
  end
end

class ReleasePromotionTest < Minitest::Test
  CUT = "2026-10-12".freeze # a Monday: beta is Sunday 2026-10-18, production 2026-10-25
  SUNDAY_BETA = Date.new(2026, 10, 18)

  def setup
    @github = FakeGitHub.new
    @git = FakeGit.new
    @play = FakePlay.new
    @sentry = FakeSentry.new
    @out = StringIO.new
    add_version("1.1.0", "internal")
    add_rc("1.1.0", rc: 3, code: 1_010_003)
  end

  # ---- fixtures -----------------------------------------------------------

  def add_version(version, stage, extra = {})
    state = ReleaseStages.new_state(version, CUT).merge("stage" => stage).merge(extra)
    @github.issues << { "number" => 100 + @github.issues.size, "title" => ReleaseStages.title(version),
                        "body" => ReleaseStages.render(state) }
  end

  def add_rc(version, rc:, code:)
    tag = "v#{version}-rc#{rc}"
    sha = "sha-#{version}-rc#{rc}"
    @git.rc_by_version[version] = tag
    @git.shas[tag] = sha
    @git.heads[version] = sha
    @git.codes[tag] = code
  end

  def state_of(version)
    issue = @github.issues.find { |i| i["title"] == ReleaseStages.title(version) }
    ReleaseStages.parse(issue["body"])
  end

  def run_on(today, apply: true)
    promotion = ReleasePromotion.new(github: @github, git: @git, play: @play, sentry: @sentry,
                                     today: today, apply: apply, out: @out)
    promotion.run
  end

  def outcome_for(outcomes, version)
    outcomes.find { |o| o.version == version }
  end

  # ---- the Sunday step ----------------------------------------------------

  def test_nothing_is_due_before_its_sunday
    outcomes = run_on(Date.new(2026, 10, 17))
    assert_equal :not_due, outcome_for(outcomes, "1.1.0").status
    assert_equal [], @play.calls
    assert_equal "internal", state_of("1.1.0")["stage"]
  end

  def test_a_clear_internal_version_goes_to_open_beta_and_is_recorded
    outcomes = run_on(SUNDAY_BETA)
    assert_equal :done, outcome_for(outcomes, "1.1.0").status
    assert_equal [1_010_003], @play.calls
    state = state_of("1.1.0")
    assert_equal "beta", state["stage"]
    assert_equal "2026-10-18", state["beta_done_on"]
    assert_equal "v1.1.0-rc3", state["rc_tag"]
    assert_equal [], state["holds"]
  end

  def test_a_dry_run_evaluates_the_gates_and_changes_nothing
    outcomes = run_on(SUNDAY_BETA, apply: false)
    assert_equal :would_do, outcome_for(outcomes, "1.1.0").status
    assert_equal [], @play.calls
    assert_equal "internal", state_of("1.1.0")["stage"]
    assert_includes @out.string, "would beta 1.1.0"
  end

  def test_production_is_a_full_week_after_the_beta_that_happened
    add_version("1.2.0", "beta", "beta_done_on" => "2026-10-18")
    assert_equal :not_due, outcome_for(run_on(Date.new(2026, 10, 24)), "1.2.0").status
    add_rc("1.2.0", rc: 1, code: 1_020_001)
    assert_equal :done, outcome_for(run_on(Date.new(2026, 10, 25)), "1.2.0").status
  end

  def test_a_held_beta_pushes_production_back_with_it
    @github.issues.clear
    add_version("1.2.0", "beta", "beta_done_on" => "2026-10-25")
    add_rc("1.2.0", rc: 1, code: 1_020_001)
    assert_equal :not_due, outcome_for(run_on(Date.new(2026, 10, 25)), "1.2.0").status
    assert_equal :done, outcome_for(run_on(Date.new(2026, 11, 1)), "1.2.0").status
  end

  def test_production_opens_and_merges_the_release_pr_as_the_app
    @github.issues.clear
    add_version("1.1.0", "beta", "beta_done_on" => "2026-10-18", "rc_tag" => "v1.1.0-rc3")
    add_rc("1.1.0", rc: 3, code: 1_010_003)
    outcomes = run_on(Date.new(2026, 10, 25))
    assert_equal :done, outcome_for(outcomes, "1.1.0").status
    assert_includes @github.calls, [:open_release_pr, "1.1.0"]
    assert_includes @github.calls.map(&:first), :merge
    assert_equal [[:production, 1_010_003]], @play.calls, "the Sunday step promotes, not release.yml"
    state = state_of("1.1.0")
    assert_equal "production", state["stage"]
    assert_equal "2026-10-25", state["production_started_on"]
    assert_equal "2026-10-25", state["merged_on"]
  end

  def test_a_failed_promotion_after_the_merge_stays_pending_on_beta
    @github.issues.clear
    add_version("1.1.0", "beta", "beta_done_on" => "2026-10-18")
    add_rc("1.1.0", rc: 3, code: 1_010_003)
    @play.error = RuntimeError.new("Play said no")
    outcome = outcome_for(run_on(Date.new(2026, 10, 25)), "1.1.0")
    assert_equal :held, outcome.status
    assert_equal ["could not promote v1.1.0-rc3 from beta to production (RuntimeError)"], outcome.reasons
    state = state_of("1.1.0")
    assert_equal "beta", state["stage"], "Play still has it on beta"
    assert_equal "2026-10-25", state["merged_on"], "the merge is remembered, so it is not repeated"
    assert_equal ["could not promote v1.1.0-rc3 from beta to production (RuntimeError)"], state["holds"]
  end

  def test_a_merge_whose_play_promotion_failed_retries_only_the_promotion
    @github.issues.clear
    add_version("1.1.0", "beta", "beta_done_on" => "2026-10-18", "merged_on" => "2026-10-25", "rc_tag" => "v1.1.0-rc3")
    add_rc("1.1.0", rc: 3, code: 1_010_003)
    @github.calls.clear
    outcome = outcome_for(run_on(Date.new(2026, 11, 1)), "1.1.0")
    assert_equal :done, outcome.status
    assert_equal [[:production, 1_010_003]], @play.calls
    refute_includes @github.calls.map(&:first), :merge, "the release PR is not merged again"
    assert_equal "production", state_of("1.1.0")["stage"]
  end

  def test_a_failed_retry_stays_pending_and_says_why
    @github.issues.clear
    add_version("1.1.0", "beta", "beta_done_on" => "2026-10-18", "merged_on" => "2026-10-25", "rc_tag" => "v1.1.0-rc3")
    add_rc("1.1.0", rc: 3, code: 1_010_003)
    @play.error = RuntimeError.new("Play said no")
    outcome = outcome_for(run_on(Date.new(2026, 11, 1)), "1.1.0")
    assert_equal :held, outcome.status
    assert_equal ["could not promote v1.1.0-rc3 from beta to production (RuntimeError)"], outcome.reasons
    state = state_of("1.1.0")
    assert_equal "beta", state["stage"]
    assert_equal "2026-10-25", state["merged_on"]
  end

  def test_a_refused_merge_holds_the_production_step_with_its_reason
    @github.issues.clear
    add_version("1.1.0", "beta", "beta_done_on" => "2026-10-18")
    add_rc("1.1.0", rc: 3, code: 1_010_003)
    @github.merge_error = RuntimeError.new("https://api.github.com/secret?key=abc")
    outcome = outcome_for(run_on(Date.new(2026, 10, 25)), "1.1.0")
    assert_equal :held, outcome.status
    assert_match(/could not merge release\/1\.1\.0 into main \(RuntimeError\)/, outcome.reasons.first)
    refute_includes outcome.reasons.join, "secret", "a request error's message must not reach the issue"
    assert_equal "beta", state_of("1.1.0")["stage"]
  end

  def test_a_failed_beta_promotion_holds_with_the_reason
    @play.error = RuntimeError.new("Play said no")
    outcome = outcome_for(run_on(SUNDAY_BETA), "1.1.0")
    assert_equal :held, outcome.status
    assert_equal ["could not promote v1.1.0-rc3 to open beta (RuntimeError)"], outcome.reasons
    assert_equal "internal", state_of("1.1.0")["stage"]
  end

  def test_production_runs_before_beta_so_the_beta_track_is_free
    @github.issues.clear
    add_version("1.1.0", "beta", "beta_done_on" => "2026-10-11")
    add_rc("1.1.0", rc: 3, code: 1_010_003)
    add_version("1.2.0", "internal")
    add_rc("1.2.0", rc: 1, code: 1_020_001)
    outcomes = run_on(SUNDAY_BETA)
    assert_equal :done, outcome_for(outcomes, "1.1.0").status
    assert_equal :done, outcome_for(outcomes, "1.2.0").status
    assert_equal [[:production, 1_010_003], 1_020_001], @play.calls, "production leaves beta before the next version takes it"
    assert_equal "production", state_of("1.1.0")["stage"]
  end

  def test_a_pending_production_promotion_holds_the_next_beta
    @github.issues.clear
    add_version("1.1.0", "beta", "beta_done_on" => "2026-10-11")
    add_rc("1.1.0", rc: 3, code: 1_010_003)
    add_version("1.2.0", "internal")
    add_rc("1.2.0", rc: 1, code: 1_020_001)
    @play.error = RuntimeError.new("Play said no")
    outcomes = run_on(SUNDAY_BETA)
    assert_equal :held, outcome_for(outcomes, "1.1.0").status
    assert_equal :held, outcome_for(outcomes, "1.2.0").status
    assert_includes outcome_for(outcomes, "1.2.0").reasons.join, "1.1.0 is still on the beta track"
  end

  def test_a_version_still_on_the_beta_track_holds_a_new_beta
    @github.issues.clear
    add_version("1.1.0", "beta", "beta_done_on" => "2026-10-18")
    add_rc("1.1.0", rc: 3, code: 1_010_003)
    add_version("1.2.0", "internal")
    add_rc("1.2.0", rc: 1, code: 1_020_001)
    outcome = outcome_for(run_on(SUNDAY_BETA), "1.2.0")
    assert_equal :held, outcome.status
    assert_includes outcome.reasons.join, "1.1.0 is still on the beta track"
    assert_equal [], @play.calls
  end

  def test_a_version_with_no_release_candidate_holds
    @git.rc_by_version.clear
    outcome = outcome_for(run_on(SUNDAY_BETA), "1.1.0")
    assert_equal ["no release candidate has been built for this version"], outcome.reasons
  end

  # ---- every gate holds, and says why -------------------------------------

  def held_reasons(&setup)
    setup.call
    outcome = outcome_for(run_on(SUNDAY_BETA), "1.1.0")
    assert_equal :held, outcome.status
    assert_equal [], @play.calls
    outcome.reasons.join(" | ")
  end

  def test_an_open_release_blocker_holds
    reasons = held_reasons { @github.blockers_by_version["1.1.0"] = ["#77 Crash on rotate"] }
    assert_includes reasons, "release-blocker: #77 Crash on rotate"
  end

  def test_an_open_pr_into_the_branch_holds
    reasons = held_reasons { @github.fix_prs_by_branch["release/1.1.0"] = ["#88 fix the sync"] }
    assert_includes reasons, "fix in flight, a PR into the branch: #88 fix the sync"
  end

  def test_a_branch_that_moved_after_its_rc_holds
    reasons = held_reasons { @git.heads["1.1.0"] = "a-newer-commit" }
    assert_includes reasons, "the branch moved after v1.1.0-rc3 was built: a newer candidate is needed"
  end

  def test_an_rc_whose_build_failed_holds
    reasons = held_reasons { @github.conclusions["sha-1.1.0-rc3"] = "failure" }
    assert_includes reasons, "v1.1.0-rc3 did not build or upload successfully (or its run could not be read)"
  end

  def test_red_ci_on_the_head_holds
    reasons = held_reasons { @github.green["sha-1.1.0-rc3"] = false }
    assert_includes reasons, "CI on the branch head is not green"
  end

  def test_draft_store_notes_hold_the_beta_too
    reasons = held_reasons { @git.drafts["1.1.0"] = %w[en-US es-ES] }
    assert_includes reasons, "the store notes are still a DRAFT (or missing) for en-US, es-ES"
  end

  def test_a_sentry_hold_holds_and_asks_about_the_rc_build
    reasons = held_reasons { @sentry.answer = "2 new crash issue(s) first seen in this release: ABC-1, ABC-2" }
    assert_includes reasons, "Sentry: 2 new crash issue(s) first seen in this release: ABC-1, ABC-2"
    assert_equal ["com.codingpit.muviss@1.1.0+1010003"], @sentry.asked
  end

  def test_a_gate_that_cannot_be_read_holds_rather_than_passing
    reasons = held_reasons { @github.blockers_by_version["1.1.0"] = RuntimeError.new("network") }
    assert_includes reasons, "the release-blocker list could not be read"
    assert_includes @out.string, "could not read blockers: RuntimeError"
  end

  def test_unreadable_notes_hold
    reasons = held_reasons { @git.raise_on_draft = RuntimeError.new("git") }
    assert_includes reasons, "the store notes could not be read"
  end

  def test_a_held_step_is_recorded_on_the_issue_once
    @git.drafts["1.1.0"] = %w[en-US]
    run_on(SUNDAY_BETA)
    comments = @github.calls.count { |c| c.first == :comment }
    run_on(SUNDAY_BETA)
    assert_equal comments, @github.calls.count { |c| c.first == :comment }, "an unchanged hold is not commented again"
    assert_equal 1, comments
    assert_includes @github.issues.first["body"], "DRAFT (or missing) for en-US"
  end

  # ---- the other two entry points -----------------------------------------

  def test_complete_closes_the_issue_at_full_rollout
    add_version("1.0.0", "beta", "beta_done_on" => "2026-10-01", "production_started_on" => "2026-10-08")
    promotion = ReleasePromotion.new(github: @github, git: @git, play: @play, sentry: @sentry,
                                     today: Date.new(2026, 10, 12), apply: true, out: @out)
    promotion.complete("1.0.0")
    issue = @github.issues.find { |i| i["title"] == "Release 1.0.0" }
    assert issue["closed"]
    assert_equal "done", ReleaseStages.stage_of(issue["body"])
  end

  def test_record_production_marks_a_promotion_done_outside_the_sunday_step
    promotion = ReleasePromotion.new(github: @github, git: @git, play: @play, sentry: @sentry,
                                     today: Date.new(2026, 10, 25), apply: true, out: @out)
    promotion.record_production("1.1.0")
    state = state_of("1.1.0")
    assert_equal "production", state["stage"]
    assert_equal "2026-10-25", state["production_started_on"]
  end

  def test_record_production_for_an_unknown_version_says_so
    promotion = ReleasePromotion.new(github: @github, git: @git, play: @play, sentry: @sentry,
                                     today: Date.new(2026, 10, 25), apply: true, out: @out)
    promotion.record_production("9.9.9")
    assert_includes @out.string, "no release issue for 9.9.9"
  end

  def test_a_hand_broken_marker_skips_that_version_and_still_promotes_the_others
    add_version("1.0.0", "beta", "beta_done_on" => "2026-10-11")
    broken = @github.issues.find { |i| i["title"] == "Release 1.0.0" }
    broken["body"] = broken["body"].sub(/\}(\s*-->)/, ',}\\1')
    outcomes = run_on(SUNDAY_BETA)
    assert_nil outcome_for(outcomes, "1.0.0")
    assert_equal :done, outcome_for(outcomes, "1.1.0").status
    assert_includes @out.string, "\"Release 1.0.0\" is not valid JSON"
  end

  def test_complete_on_a_hand_broken_marker_says_so_instead_of_crashing
    add_version("1.0.0", "production")
    broken = @github.issues.find { |i| i["title"] == "Release 1.0.0" }
    broken["body"] = broken["body"].sub(/\}(\s*-->)/, ',}\\1')
    promotion = ReleasePromotion.new(github: @github, git: @git, play: @play, sentry: @sentry,
                                     today: Date.new(2026, 10, 25), apply: true, out: @out)
    promotion.complete("1.0.0")
    assert_includes @out.string, "no readable marker; nothing to close"
    refute broken["closed"]
  end

  def test_an_unrecorded_merge_retries_only_the_promotion
    @github.issues.clear
    add_version("1.1.0", "beta", "beta_done_on" => "2026-10-18", "rc_tag" => "v1.1.0-rc3")
    add_rc("1.1.0", rc: 3, code: 1_010_003)
    @git.heads.delete("1.1.0") # release.yml deleted the branch after the merge
    @github.merged_prs["1.1.0"] = 41
    outcome = outcome_for(run_on(Date.new(2026, 10, 25)), "1.1.0")
    assert_equal :done, outcome.status
    assert_equal :play_retry, outcome.step
    refute_includes @github.calls.map(&:first), :merge
    refute_includes @github.calls.map(&:first), :open_release_pr
    assert_equal [[:production, 1_010_003]], @play.calls
    state = state_of("1.1.0")
    assert_equal "production", state["stage"]
    assert_equal 41, state["release_pr"]
  end

  def test_an_unreadable_version_code_holds_before_play_is_asked
    @git.codes.delete("v1.1.0-rc3")
    outcome = outcome_for(run_on(SUNDAY_BETA), "1.1.0")
    assert_equal :held, outcome.status
    assert_includes outcome.reasons, "the versionCode of v1.1.0-rc3 could not be read"
    assert_equal [], @play.calls
  end

  def test_complete_twice_closes_once
    add_version("1.0.0", "production")
    promotion = ReleasePromotion.new(github: @github, git: @git, play: @play, sentry: @sentry,
                                     today: Date.new(2026, 10, 12), apply: true, out: @out)
    promotion.complete("1.0.0")
    promotion.complete("1.0.0")
    assert_equal 1, @github.calls.count { |c| c.first == :close_issue }
    assert_includes @out.string, "1.0.0 is already done"
  end

  def test_production_waits_while_the_beta_track_carries_another_build
    @github.issues.clear
    add_version("1.1.0", "beta", "beta_done_on" => "2026-10-18")
    add_rc("1.1.0", rc: 3, code: 1_010_003)
    @play.beta_latest = 1_010_002 # an older candidate is still the beta build
    outcome = outcome_for(run_on(Date.new(2026, 10, 25)), "1.1.0")
    assert_equal :held, outcome.status
    assert_includes outcome.reasons, "the beta track's newest build is not v1.1.0-rc3 (versionCode 1010003): production promotes from beta"
    refute_includes @github.calls.map(&:first), :merge, "nothing is merged into main while Play could not follow (#349)"
    assert_equal "beta", state_of("1.1.0")["stage"]
  end

  def test_production_waits_when_the_beta_track_cannot_be_read
    @github.issues.clear
    add_version("1.1.0", "beta", "beta_done_on" => "2026-10-18")
    add_rc("1.1.0", rc: 3, code: 1_010_003)
    @play.track_error = RuntimeError.new("Play is down")
    outcome = outcome_for(run_on(Date.new(2026, 10, 25)), "1.1.0")
    assert_equal :held, outcome.status
    assert_includes outcome.reasons, "the beta track could not be read"
    refute_includes @github.calls.map(&:first), :merge
  end

  def test_the_beta_step_does_not_ask_the_beta_track
    @play.track_error = RuntimeError.new("not asked")
    assert_equal :done, outcome_for(run_on(SUNDAY_BETA), "1.1.0").status
  end

  def test_the_sentry_release_name_is_the_one_the_app_reports
    @sentry.answer = nil
    run_on(SUNDAY_BETA)
    assert_equal ["com.codingpit.muviss@1.1.0+1010003"], @sentry.asked
  end

  # ---- the due rule on its own --------------------------------------------

  def test_due_step_for_each_stage
    internal = ReleaseStages.new_state("1.1.0", CUT)
    assert_nil ReleaseGates.due_step(internal, Date.new(2026, 10, 17))
    assert_equal :beta, ReleaseGates.due_step(internal, SUNDAY_BETA)
    beta = internal.merge("stage" => "beta", "beta_done_on" => "2026-10-18")
    assert_nil ReleaseGates.due_step(beta, Date.new(2026, 10, 24))
    assert_equal :production, ReleaseGates.due_step(beta, Date.new(2026, 10, 25))
    assert_nil ReleaseGates.due_step(internal.merge("stage" => "production"), Date.new(2027, 1, 1))
  end
end

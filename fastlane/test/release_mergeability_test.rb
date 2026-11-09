require "minitest/autorun"
require "fileutils"
require "open3"
require "stringio"
require "tmpdir"
require_relative "../release_mergeability"
require_relative "../release_stages"

# The issue side of the nightly check, in memory. Blockers are the issues a
# label and milestone make; comments are kept per issue, so a test can count them.
class FakeIssues
  attr_reader :calls, :blockers, :comments_by

  def initialize(versions: [])
    @release_issues = versions.each_with_index.map do |(version, stage), i|
      state = ReleaseStages.new_state(version, "2026-10-12").merge("stage" => stage)
      { "number" => 500 + i, "title" => ReleaseStages.title(version), "body" => ReleaseStages.render(state), "state" => "OPEN" }
    end
    @blockers = []
    @comments_by = Hash.new { |h, k| h[k] = [] }
    @calls = []
    @next = 900
  end

  def release_issues
    @release_issues.map(&:dup)
  end

  def release_issue(version)
    @release_issues.find { |i| i["title"] == ReleaseStages.title(version) }
  end

  def open_blocker(version)
    @blockers.find { |b| b["state"] == "open" && b["title"] == ReleaseMergeability.blocker_title(version) }&.dup
  end

  def create_blocker(version, body)
    @calls << [:create_blocker, version]
    @next += 1
    @blockers << { "number" => @next, "title" => ReleaseMergeability.blocker_title(version), "body" => body, "state" => "open" }
  end

  def update_blocker_body(number, body)
    @calls << [:update_blocker_body, number]
    @blockers.find { |b| b["number"] == number }["body"] = body
  end

  def clear_blocker(version)
    @calls << [:clear_blocker, version]
    blocker = @blockers.find { |b| b["state"] == "open" && b["title"] == ReleaseMergeability.blocker_title(version) }
    blocker["state"] = "closed"
  end

  def comments(number)
    @comments_by[number].join("\n")
  end

  def comment(number, body)
    @calls << [:comment, number]
    @comments_by[number] << body
  end
end

# A repository whose main is origin/main, as on the runner (the real Repo
# fetches origin first, which a throwaway repository does not have).
class LocalRepo < ReleaseMerge::Repo
  def main_sha
    rev_parse("origin/main")
  end
end

class ReleaseMergeabilityTest < Minitest::Test
  BASE_CHANGELOG = "# Changelog\n\nHeader.\n\n## 1.1.0 — 2026-10-05\n\nold\n".freeze

  def setup
    @dir = Dir.mktmpdir
    git("init", "-q", "-b", "develop")
    git("config", "user.email", "t@example.com")
    git("config", "user.name", "test")
    git("config", "commit.gpgsign", "false")
    write("CHANGELOG.md", BASE_CHANGELOG)
    write("app.txt", "base\n")
    write("F.txt", "x\n")
    git("add", "-A")
    git("commit", "-q", "-m", "base")
    @base = git("rev-parse", "HEAD")
    point("origin/main", @base)
    @issues = FakeIssues.new(versions: [["1.3.0", "internal"]])
  end

  def teardown
    FileUtils.remove_entry(@dir) if @dir && File.directory?(@dir)
  end

  def checker(issues: @issues)
    ReleaseMergeability::Checker.new(git: LocalRepo.new(dir: @dir), issues: issues, out: StringIO.new)
  end

  def verdicts(issues: @issues)
    checker(issues: issues).run.to_h { |r| [r.version, r.verdict] }
  end

  # A release branch cut from base with `files` changed, and remembered as
  # origin/release/VERSION, the ref the nightly check reads.
  def release(version, files)
    git("switch", "-q", "--detach", @base)
    files.each { |path, text| write(path, text) }
    git("add", "-A")
    git("commit", "-q", "-m", "release #{version}")
    point("origin/release/#{version}", git("rev-parse", "HEAD"))
    git("switch", "-q", "develop")
  end

  # Advances origin/main by one commit that changes `files`.
  def main_moves(files)
    git("switch", "-q", "--detach", "origin/main")
    files.each { |path, text| write(path, text) }
    git("add", "-A")
    git("commit", "-q", "-m", "main moves")
    point("origin/main", git("rev-parse", "HEAD"))
    git("switch", "-q", "develop")
  end

  def test_a_branch_that_merges_cleanly_is_a_no_op
    release("1.3.0", "n.txt" => "new\n")
    assert_equal({ "1.3.0" => :clean }, verdicts)
    assert_equal [], @issues.calls
  end

  def test_a_changelog_only_conflict_is_a_no_op
    main_moves("CHANGELOG.md" => "# Changelog\n\nHeader.\n\n## 1.2.0 — 2026-10-19\n\nmain\n\n## 1.1.0 — 2026-10-05\n\nold\n")
    release("1.3.0", "CHANGELOG.md" => "# Changelog\n\nHeader.\n\n## 1.3.0 — 2026-10-26\n\nbranch\n\n## 1.1.0 — 2026-10-05\n\nold\n")
    assert_equal({ "1.3.0" => :allowed }, verdicts)
    assert_equal [], @issues.calls, "CHANGELOG.md is resolved by the promotion, so nothing is raised"
  end

  def test_a_code_conflict_raises_one_blocker_and_one_comment
    main_moves("app.txt" => "main\n")
    release("1.3.0", "app.txt" => "branch\n")
    results = checker.run
    assert_equal :blocked, results.first.verdict
    assert_equal ["app.txt"], results.first.paths
    assert checker.failed?(results), "the run fails visibly"
    assert_equal 1, @issues.blockers.size
    blocker = @issues.blockers.first
    assert_equal "Release 1.3.0 does not merge into main", blocker["title"]
    assert_includes blocker["body"], "app.txt"
    assert_equal 1, @issues.comments_by[500].size, "the Release 1.3.0 issue is told once"
    assert_includes @issues.comments(500), "app.txt"
  end

  def test_the_blocker_and_the_comment_are_not_repeated_on_a_rerun
    main_moves("app.txt" => "main\n")
    release("1.3.0", "app.txt" => "branch\n")
    checker.run
    @issues.calls.clear
    results = checker.run
    assert checker.failed?(results), "still failing"
    assert_equal [], @issues.calls.reject { |c| c.first == :update_blocker_body }, "no second blocker, no second comment"
    assert_equal 1, @issues.blockers.size
    assert_equal 1, @issues.comments_by[500].size
  end

  def test_a_predecessor_is_merged_before_the_next_version_is_judged
    # Each branch adds a line after `x` in F.txt. Against main both are clean;
    # once 1.3.0 is on main, 1.4.0 collides with it, which is what Sunday hits.
    issues = FakeIssues.new(versions: [["1.3.0", "internal"], ["1.4.0", "internal"]])
    release("1.3.0", "F.txt" => "x\nA\n")
    release("1.4.0", "F.txt" => "x\nB\n")
    assert_equal [], LocalRepo.new(dir: @dir).conflicts("origin/main", "origin/release/1.4.0"), "clean against main alone"
    result = verdicts(issues: issues)
    assert_equal :clean, result["1.3.0"]
    assert_equal :blocked, result["1.4.0"], "judged against main with 1.3.0 merged, not against main alone"
  end

  def test_a_fix_clears_the_blocker_and_closes_it
    main_moves("app.txt" => "main\n")
    release("1.3.0", "app.txt" => "branch\n")
    checker.run
    release("1.3.0", "app.txt" => "main\n")
    @issues.calls.clear
    results = checker.run
    refute checker.failed?(results)
    assert_equal :clean, results.first.verdict
    assert_equal [[:clear_blocker, "1.3.0"]], @issues.calls
    assert_equal "closed", @issues.blockers.first["state"]
  end

  def test_only_live_versions_are_checked
    issues = FakeIssues.new(versions: [["1.0.0", "production"], ["1.3.0", "internal"]])
    release("1.0.0", "app.txt" => "conflicting\n")
    main_moves("app.txt" => "main\n")
    release("1.3.0", "n.txt" => "new\n")
    assert_equal ["1.3.0"], verdicts(issues: issues).keys
  end

  def test_a_version_with_no_branch_is_skipped
    @issues = FakeIssues.new(versions: [["1.3.0", "internal"]])
    assert_equal({}, verdicts)
  end

  private

  def write(path, text)
    File.write(File.join(@dir, path), text)
  end

  def point(ref, sha)
    git("update-ref", "refs/remotes/#{ref}", sha)
  end

  def git(*args)
    out, err, status = Open3.capture3("git", *args, chdir: @dir)
    raise "git #{args.join(' ')} failed: #{err}" unless status.success?

    out.strip
  end
end

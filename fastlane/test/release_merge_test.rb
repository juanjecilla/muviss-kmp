require "minitest/autorun"
require "fileutils"
require "open3"
require "stringio"
require "tmpdir"
require_relative "../release_merge"

# ChangelogUnion is the pure rule: sections are keyed by version, a version on
# one side only is added, a version on both sides with the same text is kept
# once, and a version whose text differs on the two sides is refused rather
# than dropped. The git tests below run the real plumbing in a throwaway repo.
class ChangelogUnionTest < Minitest::Test
  HEADER = "# Changelog\n\nFrozen history.\n\n".freeze

  def union(main, branch)
    ReleaseMerge::ChangelogUnion.merge(main, branch)
  end

  def test_a_branch_section_lands_above_the_mains_newer_ones
    main = HEADER + "## 1.3.0 — 2026-10-26\n\nmain 1.3.0\n\n## 1.2.0 — 2026-10-19\n\nold\n"
    branch = HEADER + "## 1.4.0 — 2026-11-02\n\nbranch 1.4.0\n\n## 1.2.0 — 2026-10-19\n\nold\n"
    expected = HEADER + "## 1.4.0 — 2026-11-02\n\nbranch 1.4.0\n\n## 1.3.0 — 2026-10-26\n\nmain 1.3.0\n\n## 1.2.0 — 2026-10-19\n\nold\n"
    assert_equal expected, union(main, branch)
  end

  def test_a_section_both_sides_carry_with_the_same_text_is_kept_once
    text = HEADER + "## 1.2.0 — x\n\nold\n"
    assert_equal text, union(text, text)
  end

  def test_a_version_whose_text_differs_on_the_two_sides_is_refused_not_dropped
    main = HEADER + "## 1.3.0 — x\n\nmain says this\n"
    branch = HEADER + "## 1.3.0 — x\n\nbranch says that\n"
    error = assert_raises(ReleaseMerge::Unresolvable) { union(main, branch) }
    assert_match(/1\.3\.0/, error.message)
  end

  def test_sections_come_out_newest_first_even_when_the_branch_is_older
    main = HEADER + "## 1.2.0 — x\n\nold\n"
    branch = HEADER + "## 1.10.0 — y\n\nten\n\n## 1.2.1 — z\n\nfix\n"
    versions = union(main, branch).scan(/^## (\d+\.\d+\.\d+)/).flatten
    assert_equal %w[1.10.0 1.2.1 1.2.0], versions
  end

  def test_a_header_that_names_the_frozen_history_wins_over_an_older_one
    main = "# Changelog\n\nold header\n\n## 1.2.0 — x\n\nold\n"
    branch = "# Changelog\n\nFrozen history: see docs/changelog/.\n\n## 1.4.0 — y\n\nnew\n"
    assert_match(/docs\/changelog/, union(main, branch).lines.first(4).join)
  end

  def test_a_heading_without_a_version_is_refused
    error = assert_raises(ReleaseMerge::Unresolvable) { union(HEADER + "## Unreleased\n", HEADER) }
    assert_match(/Unreleased/, error.message)
  end
end

# push_main is the only call that moves main. Against a real origin it must
# refuse a main that moved, and never force.
class ReleaseMergePushTest < Minitest::Test
  def setup
    @origin = Dir.mktmpdir
    @dir = Dir.mktmpdir
    git_in(@origin, "init", "-q", "--bare", "-b", "main")
    git("init", "-q", "-b", "main")
    git("config", "user.email", "t@example.com")
    git("config", "user.name", "test")
    git("config", "commit.gpgsign", "false")
    git("remote", "add", "origin", @origin)
    write("a.txt", "one\n")
    git("add", "-A")
    git("commit", "-q", "-m", "base")
    git("push", "-q", "origin", "main")
    @base = git("rev-parse", "HEAD")
  end

  def teardown
    FileUtils.remove_entry(@origin) if @origin && File.directory?(@origin)
    FileUtils.remove_entry(@dir) if @dir && File.directory?(@dir)
  end

  def repo = ReleaseMerge::Repo.new(dir: @dir)

  def test_a_fast_forward_pushes_and_moves_main
    write("a.txt", "two\n")
    git("commit", "-q", "-am", "next")
    tip = git("rev-parse", "HEAD")
    repo.push_main(tip, expected: @base)
    assert_equal tip, git_in(@origin, "rev-parse", "refs/heads/main")
  end

  def test_a_main_that_moved_is_refused_and_nothing_is_forced
    write("a.txt", "theirs\n")
    git("commit", "-q", "-am", "someone else's merge")
    theirs = git("rev-parse", "HEAD")
    git("push", "-q", "origin", "main")
    write("a.txt", "mine\n")
    git("commit", "-q", "-am", "ours")
    ours = git("rev-parse", "HEAD")
    assert_raises(ReleaseMerge::MainMoved) { repo.push_main(ours, expected: @base) }
    assert_equal theirs, git_in(@origin, "rev-parse", "refs/heads/main"), "origin keeps its own main"
  end

  def test_a_sha_that_does_not_descend_from_main_is_refused_by_git
    git("switch", "-q", "--orphan", "unrelated")
    write("b.txt", "unrelated\n")
    git("add", "-A")
    git("commit", "-q", "-m", "unrelated")
    unrelated = git("rev-parse", "HEAD")
    assert_raises(RuntimeError) { repo.push_main(unrelated, expected: @base) }
    assert_equal @base, git_in(@origin, "rev-parse", "refs/heads/main")
  end

  private

  def write(path, text)
    File.write(File.join(@dir, path), text)
  end

  def git(*args)
    git_in(@dir, *args)
  end

  def git_in(dir, *args)
    out, err, status = Open3.capture3("git", *args, chdir: dir)
    raise "git #{args.join(' ')} failed: #{err}" unless status.success?

    out.force_encoding(Encoding::UTF_8).strip
  end
end

# The plumbing against a real repository: the merge commit is built from the
# objects alone, so no ref moves until the caller pushes it.
class ReleaseMergeRepoTest < Minitest::Test
  def setup
    @dir = Dir.mktmpdir
    git("init", "-q", "-b", "main")
    git("config", "user.email", "t@example.com")
    git("config", "user.name", "test")
    git("config", "commit.gpgsign", "false")
    write("CHANGELOG.md", "# Changelog\n\nHeader.\n\n## 1.2.0 — x\n\nold\n")
    write("app.txt", "base\n")
    git("add", "-A")
    git("commit", "-q", "-m", "base")
    @base = git("rev-parse", "HEAD")
    write("CHANGELOG.md", "# Changelog\n\nHeader.\n\n## 1.3.0 — y\n\nmain 1.3.0\n\n## 1.2.0 — x\n\nold\n")
    git("commit", "-q", "-am", "main: 1.3.0")
    @main = git("rev-parse", "HEAD")
    git("switch", "-q", "-c", "release", @base)
    write("CHANGELOG.md", "# Changelog\n\nHeader.\n\n## 1.4.0 — z\n\nbranch 1.4.0\n\n## 1.2.0 — x\n\nold\n")
    write("feature.txt", "from the branch\n")
    git("add", "-A")
    git("commit", "-q", "-m", "release: 1.4.0")
    @branch = git("rev-parse", "HEAD")
    git("switch", "-q", "main")
  end

  def teardown
    FileUtils.remove_entry(@dir) if @dir && File.directory?(@dir)
  end

  def repo = ReleaseMerge::Repo.new(dir: @dir)

  def test_a_changelog_only_conflict_becomes_a_merge_commit_with_both_sides
    result = repo.merge_commit(@main, @branch, message: "Merge release/1.4.0")
    assert_equal ["CHANGELOG.md"], result[:conflicts]
    refute_nil result[:commit]
    assert_equal [@main, @branch].join(" "), git("log", "-1", "--format=%P", result[:commit]), "main first, then the branch"
    changelog = git("show", "#{result[:commit]}:CHANGELOG.md")
    assert_operator changelog.index("## 1.4.0"), :<, changelog.index("## 1.3.0"), "the branch's section sits above main's"
    assert_equal 1, changelog.scan("## 1.2.0").size, "a section both sides carry is kept once"
    assert_equal "from the branch", git("show", "#{result[:commit]}:feature.txt")
  end

  def test_building_the_commit_moves_no_ref
    before = git("rev-parse", "refs/heads/main")
    repo.merge_commit(@main, @branch, message: "m")
    assert_equal before, git("rev-parse", "refs/heads/main")
  end

  def test_a_conflict_in_another_file_builds_nothing
    write("app.txt", "main edit\n")
    git("commit", "-q", "-am", "main: app")
    main = git("rev-parse", "HEAD")
    git("switch", "-q", "release")
    write("app.txt", "branch edit\n")
    git("commit", "-q", "-am", "release: app")
    branch = git("rev-parse", "HEAD")
    result = repo.merge_commit(main, branch, message: "m")
    assert_includes result[:conflicts], "app.txt"
    assert_nil result[:commit], "a path that is not allow-listed is never resolved here"
  end

  def test_a_clean_merge_needs_no_resolution
    git("switch", "-q", "-c", "clean", @base)
    write("other.txt", "only the branch\n")
    git("add", "-A")
    git("commit", "-q", "-m", "clean")
    clean = git("rev-parse", "HEAD")
    result = repo.merge_commit(@base, clean, message: "m")
    assert_equal [], result[:conflicts]
    assert_equal "only the branch", git("show", "#{result[:commit]}:other.txt")
  end

  private

  def write(path, text)
    File.write(File.join(@dir, path), text)
  end

  def git(*args)
    out, err, status = Open3.capture3("git", *args, chdir: @dir)
    raise "git #{args.join(' ')} failed: #{err}" unless status.success?

    out.force_encoding(Encoding::UTF_8).strip
  end
end

# The required contexts of main, read from its rules, judged on the head (#793 decision A): a context
# with no passing run is not green, for CLEAN and DIRTY alike. No network: `gh` is a fake keyed by path.
class RequiredChecksTest < Minitest::Test
  SHA = ("a" * 40).freeze
  SLUG = "owner/repo".freeze
  RULES = "repos/owner/repo/rules/branches/main".freeze
  RUNS = "repos/owner/repo/commits/#{SHA}/check-runs".freeze
  STATUSES = "repos/owner/repo/commits/#{SHA}/statuses".freeze

  def run_of(name, conclusion, id: 1, status: "completed")
    { "id" => id, "name" => name, "status" => status, "conclusion" => conclusion }
  end

  def missing(required, runs: [], statuses: [])
    ReleaseMerge::RequiredChecks.missing(required, runs, statuses)
  end

  # Replies keyed by path. Rules, runs and statuses are the lines a `gh api --paginate` would print.
  def gh_with(rules: [], runs: [], statuses: [], fail: nil)
    replies = { RULES => rules, RUNS => runs, STATUSES => statuses }
    lambda do |*args|
      path = args.first.sub(/\?.*/, "")
      next [nil, 1] if fail == path

      lines = replies.fetch(path) { raise "unexpected #{path}" }
      [lines.map { |l| l.is_a?(String) ? l : JSON.generate(l) }.join("\n"), 0]
    end
  end

  def test_a_context_whose_newest_run_passed_is_green
    assert_empty missing(["build"], runs: [run_of("build", "success")])
  end

  def test_a_skipped_context_is_green
    assert_empty missing(["ios"], runs: [run_of("ios", "skipped")])
  end

  def test_a_context_with_no_run_is_not_green
    assert_equal ["Release tooling tests"], missing(["Release tooling tests"], runs: [run_of("build", "success")])
  end

  def test_a_context_still_running_is_not_green
    assert_equal ["build"], missing(["build"], runs: [run_of("build", nil, status: "in_progress")])
  end

  def test_a_context_that_only_cancelled_is_not_green
    assert_equal ["build"], missing(["build"], runs: [run_of("build", "cancelled")])
  end

  def test_a_newer_failure_outweighs_an_older_pass
    runs = [run_of("build", "success", id: 1), run_of("build", "failure", id: 2)]
    assert_equal ["build"], missing(["build"], runs: runs)
  end

  def test_a_rerun_that_passed_after_a_failure_is_green
    runs = [run_of("build", "failure", id: 1), run_of("build", "success", id: 2)]
    assert_empty missing(["build"], runs: runs)
  end

  def test_a_passing_run_does_not_cover_a_failing_status
    statuses = [{ "context" => "build", "state" => "failure" }]
    assert_equal ["build"], missing(["build"], runs: [run_of("build", "success")], statuses: statuses)
  end

  def test_a_passing_commit_status_is_green
    assert_empty missing(["Analyze"], statuses: [{ "context" => "Analyze", "state" => "success" }])
  end

  def test_unmet_names_the_contexts_missing_on_the_head
    gh = gh_with(rules: ["build", "Release tooling tests"], runs: [run_of("build", "success")])
    assert_equal ["Release tooling tests"], ReleaseMerge::RequiredChecks.unmet(sha: SHA, slug: SLUG, gh: gh)
  end

  def test_an_unreadable_rules_endpoint_holds_rather_than_passing
    gh = gh_with(fail: RULES)
    assert_raises(ReleaseMerge::RequiredChecks::Unreadable) { ReleaseMerge::RequiredChecks.unmet(sha: SHA, slug: SLUG, gh: gh) }
  end

  def test_main_with_no_required_contexts_is_an_error_not_a_pass
    gh = gh_with(rules: [])
    assert_raises(ReleaseMerge::RequiredChecks::Unreadable) { ReleaseMerge::RequiredChecks.unmet(sha: SHA, slug: SLUG, gh: gh) }
  end

  def test_a_malformed_sha_or_slug_is_refused_before_any_call
    gh = ->(*) { raise "no call expected" }
    assert_raises(ReleaseMerge::RequiredChecks::Unreadable) { ReleaseMerge::RequiredChecks.unmet(sha: "main", slug: SLUG, gh: gh) }
    assert_raises(ReleaseMerge::RequiredChecks::Unreadable) { ReleaseMerge::RequiredChecks.unmet(sha: SHA, slug: "a b", gh: gh) }
  end

  def cli_unmet(gh, sha: SHA)
    out = StringIO.new
    err = StringIO.new
    code = ReleaseMerge::Cli.run(["unmet", sha], repo: nil, out: out, err: err, slug: SLUG, gh: gh)
    [code, err.string]
  end

  def test_the_cli_exits_0_when_every_required_context_is_green
    code, _err = cli_unmet(gh_with(rules: ["build"], runs: [run_of("build", "success")]))
    assert_equal 0, code
  end

  def test_the_cli_holds_with_exit_2_and_names_the_missing_context
    code, err = cli_unmet(gh_with(rules: ["build", "Release notes are final"], runs: [run_of("build", "success")]))
    assert_equal ReleaseMerge::Cli::HELD, code
    assert_includes err, "Release notes are final"
  end

  def test_the_cli_exits_1_when_the_rules_cannot_be_read_without_echoing_stderr
    code, err = cli_unmet(gh_with(fail: RULES))
    assert_equal 1, code
    assert_includes err, "gh api exited 1"
  end

  def test_the_cli_refuses_a_missing_sha_with_usage
    out = StringIO.new
    err = StringIO.new
    assert_equal 64, ReleaseMerge::Cli.run(["unmet"], repo: nil, out: out, err: err, slug: SLUG, gh: gh_with)
  end
end

# The command line the workflows call (#717 manual merge, #732 rehearsal), run in
# process against a real origin: what it prints, what it exits with, and whether
# origin's main moved. Exit codes: 0 done, 2 held, 3 git merges it cleanly.
class ReleaseMergeCliTest < Minitest::Test
  CHANGELOG_HEAD = "# Changelog\n\nHeader.\n\n".freeze

  def setup
    @origin = Dir.mktmpdir
    @dir = Dir.mktmpdir
    git_in(@origin, "init", "-q", "--bare", "-b", "main")
    git("init", "-q", "-b", "main")
    git("config", "user.email", "t@example.com")
    git("config", "user.name", "test")
    git("config", "commit.gpgsign", "false")
    git("remote", "add", "origin", @origin)
    write("CHANGELOG.md", CHANGELOG_HEAD + "## 1.2.0 — x\n\nold\n")
    write("app.txt", "base\n")
    git("add", "-A"); git("commit", "-q", "-m", "base")
    git("push", "-q", "origin", "main")
    @base = git("rev-parse", "HEAD")
    write("CHANGELOG.md", CHANGELOG_HEAD + "## 1.3.0 — y\n\nmain 1.3.0\n\n## 1.2.0 — x\n\nold\n")
    git("commit", "-q", "-am", "main: 1.3.0")
    git("push", "-q", "origin", "main")
    git("switch", "-q", "-c", "release/1.4.0", @base)
    write("CHANGELOG.md", CHANGELOG_HEAD + "## 1.4.0 — z\n\nbranch 1.4.0\n\n## 1.2.0 — x\n\nold\n")
    write("feature.txt", "from the branch\n")
    git("add", "-A"); git("commit", "-q", "-m", "release: 1.4.0")
    git("push", "-q", "origin", "release/1.4.0")
    @branch = git("rev-parse", "HEAD")
    git("switch", "-q", "main")
    @main = git("rev-parse", "HEAD")
  end

  def teardown
    FileUtils.remove_entry(@origin) if @origin && File.directory?(@origin)
    FileUtils.remove_entry(@dir) if @dir && File.directory?(@dir)
  end

  def test_conflicts_names_a_changelog_only_conflict_and_holds_nothing
    code, out, err = cli("conflicts", "release/1.4.0")
    assert_equal 0, code, err
    assert_includes out, "conflicts: CHANGELOG.md"
  end

  def test_conflicts_holds_a_path_outside_the_allow_list_with_its_name
    write_and_push_main_edit("app.txt", "main edit\n")
    git("switch", "-q", "-c", "release/1.5.0", @base)
    write("app.txt", "branch edit\n"); git("commit", "-q", "-am", "release: app")
    git("push", "-q", "origin", "release/1.5.0")
    code, _out, err = cli("conflicts", "release/1.5.0")
    assert_equal ReleaseMerge::Cli::HELD, code
    assert_includes err, "app.txt"
  end

  def test_union_pushes_the_union_to_main_when_only_the_changelog_conflicts
    code, out, err = cli("union", "release/1.4.0", "--expect-head", @branch)
    assert_equal 0, code, err
    assert_includes out, "CHANGELOG.md resolved"
    tip = git_in(@origin, "rev-parse", "refs/heads/main")
    assert_equal [@main, @branch].join(" "), git_in(@origin, "log", "-1", "--format=%P", tip), "main first, then the branch"
    changelog = git_in(@origin, "show", "#{tip}:CHANGELOG.md")
    assert_operator changelog.index("## 1.4.0"), :<, changelog.index("## 1.3.0")
  end

  def test_union_holds_and_pushes_nothing_when_another_path_conflicts
    write_and_push_main_edit("app.txt", "main edit\n")
    main_now = git_in(@origin, "rev-parse", "refs/heads/main")
    git("switch", "-q", "-c", "release/1.5.0", @base)
    write("app.txt", "branch edit\n"); git("commit", "-q", "-am", "release: app")
    git("push", "-q", "origin", "release/1.5.0")
    code, _out, err = cli("union", "release/1.5.0", "--expect-head", git_in(@dir, "rev-parse", "HEAD"))
    assert_equal ReleaseMerge::Cli::HELD, code
    assert_includes err, "app.txt"
    assert_equal main_now, git_in(@origin, "rev-parse", "refs/heads/main")
  end

  def test_union_refuses_to_guess_when_git_merges_cleanly
    git("switch", "-q", "-c", "release/1.6.0", @base)
    write("other.txt", "only the branch\n"); git("add", "-A"); git("commit", "-q", "-m", "clean")
    git("push", "-q", "origin", "release/1.6.0")
    code, _out, err = cli("union", "release/1.6.0", "--expect-head", git_in(@dir, "rev-parse", "HEAD"))
    assert_equal ReleaseMerge::Cli::CLEAN_IN_GIT, code
    assert_includes err, "nothing pushed"
    assert_equal @main, git_in(@origin, "rev-parse", "refs/heads/main")
  end

  def test_rehearse_prints_the_verdict_and_tree_and_moves_nothing
    code, out, err = cli("rehearse", "1.4.0")
    assert_equal 0, code, err
    assert_includes out, "verdict: union of both sides for CHANGELOG.md"
    assert_match(/^tree [0-9a-f]{40}$/, out)
    assert_includes out, "changes on main"
    assert_includes out, "not pushed"
    assert_equal @main, git_in(@origin, "rev-parse", "refs/heads/main")
    assert_empty git_in(@origin, "for-each-ref", "refs/heads/rehearsal")
  end

  def test_rehearse_push_puts_the_merge_on_a_throwaway_ref_and_deletes_it
    code, out, err = cli("rehearse", "1.4.0", "--push")
    assert_equal 0, code, err
    assert_includes out, "pushed to refs/heads/rehearsal/1.4.0"
    assert_empty git_in(@origin, "for-each-ref", "refs/heads/rehearsal"), "the ref is deleted after"
    assert_equal @main, git_in(@origin, "rev-parse", "refs/heads/main"), "main does not move"
  end

  def test_rehearse_holds_a_branch_with_a_path_outside_the_allow_list
    write_and_push_main_edit("app.txt", "main edit\n")
    git("switch", "-q", "-c", "release/1.5.0", @base)
    write("app.txt", "branch edit\n"); git("commit", "-q", "-am", "release: app")
    git("push", "-q", "origin", "release/1.5.0")
    code, out, _err = cli("rehearse", "1.5.0", "--push")
    assert_equal ReleaseMerge::Cli::HELD, code
    assert_includes out, "verdict: held"
    assert_empty git_in(@origin, "for-each-ref", "refs/heads/rehearsal")
  end

  def test_union_refuses_a_branch_whose_head_moved_after_approval
    code, _out, err = cli("union", "release/1.4.0", "--expect-head", @main)
    assert_equal 1, code
    assert_includes err, "not the approved"
    assert_equal @main, git_in(@origin, "rev-parse", "refs/heads/main")
  end

  def test_union_without_an_approved_head_is_a_usage_error
    code, _out, err = cli("union", "release/1.4.0")
    assert_equal 64, code
    assert_includes err, "usage"
    assert_equal @main, git_in(@origin, "rev-parse", "refs/heads/main")
  end

  def test_delete_ref_ignores_a_ref_that_is_already_gone
    # Raises, and fails the test, unless a missing ref is treated as deleted.
    ReleaseMerge::Repo.new(dir: @dir).delete_ref("refs/heads/rehearsal/never-pushed")
  end

  def test_rehearse_push_reports_a_ref_that_will_not_delete
    # A pre-receive hook on origin refuses every deletion, so the push lands and the cleanup cannot.
    hook = File.join(@origin, "hooks", "pre-receive")
    File.write(hook, "#!/bin/sh\nwhile read old new ref; do\n  [ \"$new\" = 0000000000000000000000000000000000000000 ] && exit 1\ndone\nexit 0\n")
    File.chmod(0o755, hook)
    code, out, err = cli("rehearse", "1.4.0", "--push")
    assert_equal 1, code
    assert_includes out, "pushed to refs/heads/rehearsal/1.4.0"
    assert_includes err, "may remain on origin"
    assert_equal "refs/heads/rehearsal/1.4.0", git_in(@origin, "for-each-ref", "--format=%(refname)", "refs/heads/rehearsal")
  end

  def test_a_branch_that_is_not_a_release_or_hotfix_name_is_refused_before_any_git_call
    code, _out, err = cli("union", "main:refs/heads/main", "--expect-head", @main)
    assert_equal 64, code
    assert_includes err, "usage"
    assert_equal @main, git_in(@origin, "rev-parse", "refs/heads/main")
  end

  def test_a_version_that_is_not_x_y_z_is_refused
    code, _out, err = cli("rehearse", "1.4.0 --push")
    assert_equal 64, code
    assert_includes err, "usage"
    assert_empty git_in(@origin, "for-each-ref", "refs/heads/rehearsal")
  end

  def test_an_unknown_command_is_a_usage_error
    code, _out, err = cli("bogus")
    assert_equal 64, code
    assert_includes err, "usage"
  end

  private

  # Moves origin's main by one commit that edits `path`, as a later merge into main would.
  def write_and_push_main_edit(path, text)
    git("switch", "-q", "main")
    write(path, text); git("commit", "-q", "-am", "main: #{path}")
    git("push", "-q", "origin", "main")
    @main = git("rev-parse", "HEAD")
  end

  def cli(*argv)
    out = StringIO.new
    err = StringIO.new
    code = ReleaseMerge::Cli.run(argv, repo: ReleaseMerge::Repo.new(dir: @dir), out: out, err: err)
    [code, out.string, err.string]
  end

  def write(path, text)
    File.write(File.join(@dir, path), text)
  end

  def git(*args)
    git_in(@dir, *args)
  end

  def git_in(dir, *args)
    out, err, status = Open3.capture3("git", *args, chdir: dir)
    raise "git #{args.join(' ')} failed: #{err}" unless status.success?

    out.force_encoding(Encoding::UTF_8).strip
  end
end

require "minitest/autorun"
require "fileutils"
require "open3"
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

    out.strip
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

    out.strip
  end
end

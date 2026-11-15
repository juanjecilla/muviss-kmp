# Merging a release branch into main when git can only refuse it (#712).
#
# The Sunday step merges release/X.Y.Z into main with a merge commit. GitHub
# refuses a merge it cannot create cleanly, and the only paths that conflict
# between two release branches are generated files that each branch changed at
# the same spot. For those, the result is known: the union of both sides'
# version sections. ReleaseMerge builds that merge commit from git objects only,
# so the caller decides whether to move main with it (release_promote.rb pushes
# it as the release App; release_mergeability.rb never pushes at all).
#
# ALLOWED_CONFLICTS is the whole list. A conflict in any other path is not
# resolved here, and the caller holds with the path named.
#
# The manual path (ship-release.yml, #717) and the Sunday rehearsal (release-promote.yml,
# #732) use the same plumbing through the command line at the bottom of this file, so
# neither has its own copy of the rule.
require "json"
require "open3"
require "tmpdir"

module ReleaseMerge
  ALLOWED_CONFLICTS = %w[CHANGELOG.md].freeze

  # A conflict this module will not guess at. The message names what it is.
  class Unresolvable < StandardError; end

  # A push that did not move main. Never retried with force.
  class MainMoved < StandardError; end

  # The release branch moved after the caller's approval. Nothing is merged from it.
  class HeadMoved < StandardError; end

  # CHANGELOG.md: one `## X.Y.Z — date` section per version, under a header. The
  # union keeps every version once, newest first. The header comes from main,
  # unless the branch's is the one that points at docs/changelog/ (the frozen
  # note, #711), which is the newer text. A version whose text differs on the two
  # sides is refused: keeping one would drop the other.
  module ChangelogUnion
    HEADING = /\A## (\d+\.\d+\.\d+)\b/
    FROZEN_NOTE = "docs/changelog".freeze

    module_function

    def merge(main, branch)
      main_head, main_sections = split(main)
      branch_head, branch_sections = split(branch)

      by_version = {}
      main_sections.each { |version, text| by_version[version] = text }
      branch_sections.each do |version, text|
        if by_version.key?(version)
          raise Unresolvable, "#{version} has different text on each side of the merge" unless by_version[version] == text
        else
          by_version[version] = text
        end
      end

      ordered = by_version.sort_by { |version, _| Gem::Version.new(version) }.reverse
      header(main_head, branch_head) + ordered.map(&:last).join
    end

    def header(main_head, branch_head)
      return main_head if main_head == branch_head
      return branch_head if branch_head.include?(FROZEN_NOTE) && !main_head.include?(FROZEN_NOTE)

      main_head
    end

    # [header text, [[version, section text], ...]] for one file.
    def split(text)
      head = +""
      sections = []
      text.each_line do |line|
        if line.start_with?("## ")
          match = HEADING.match(line)
          raise Unresolvable, "a section heading names no version: #{line.strip.inspect}" unless match

          version = match[1]
          raise Unresolvable, "#{version} appears twice on one side" if sections.any? { |v, _| v == version }

          sections << [version, +line]
        elsif sections.empty?
          head << line
        else
          sections.last[1] << line
        end
      end
      [head, sections]
    end
  end

  # Git plumbing for one repository. `dir` is the repository; nil is the working
  # directory. Every method reads or writes objects only, except push_main and
  # main_sha, which talk to origin.
  class Repo
    RESOLVERS = { "CHANGELOG.md" => ->(main, branch) { ChangelogUnion.merge(main, branch) } }.freeze

    def initialize(dir: nil)
      @dir = dir
    end

    # The paths a merge of `branch` into `main` would conflict on; [] when clean.
    def conflicts(main, branch)
      merge_tree(main, branch)[:conflicts]
    end

    # {conflicts:, commit:}. The commit is a two-parent merge (main first) whose
    # tree is the clean merge, or, when every conflict is allow-listed, the same
    # tree with each conflicted file replaced by its resolution. It is nil when a
    # conflict is not allow-listed: nothing is guessed at for those. No ref moves.
    def merge_commit(main, branch, message:)
      merged = merge_tree(main, branch)
      conflicts = merged[:conflicts]
      return { conflicts: conflicts, commit: nil } unless (conflicts - ALLOWED_CONFLICTS).empty?

      tree = conflicts.empty? ? merged[:tree] : resolved_tree(merged[:tree], main, branch, conflicts)
      { conflicts: conflicts, commit: commit_tree(tree, [main, branch], message) }
    end

    # The commit a ref names, or nil when there is no such ref.
    def rev_parse(ref)
      out, _err, status = run("git", "rev-parse", "--verify", "--quiet", "#{ref}^{commit}")
      status.success? ? out.strip : nil
    end

    # origin's refs/heads/<branch>, fetched now; its sha.
    def fetch_branch(branch)
      git("fetch", "--quiet", "origin", "+refs/heads/#{branch}:refs/remotes/origin/#{branch}")
      git("rev-parse", "origin/#{branch}")
    end

    # The tree of a commit.
    def tree_of(commit)
      git("rev-parse", "#{commit}^{tree}")
    end

    # The paths that differ between two commits (no renames).
    def changed_paths(from, to)
      git("diff", "--no-renames", "--name-only", from, to).lines.map(&:strip).reject(&:empty?)
    end

    # Pushes a sha to a ref that is not main, with no force. Only the rehearsal
    # uses it, on a throwaway ref it deletes again.
    def push_ref(sha, ref)
      git("push", "--quiet", "origin", "#{sha}:#{ref}")
    end

    # Deletes a ref on origin. A ref that is already gone is not an error; one
    # still on origin after a failed delete is, and the message says so.
    def delete_ref(ref)
      git("push", "--quiet", "origin", ":#{ref}")
    rescue RuntimeError
      raise "#{ref} may remain on origin; delete it by hand" unless git("ls-remote", "origin", ref).empty?
    end

    # origin's main, fetched now. The merge is judged against this sha.
    def main_sha
      git("fetch", "--quiet", "origin", "main")
      git("rev-parse", "origin/main")
    end

    # A plain push: git refuses it unless `sha` descends from main, so a main
    # that moved is refused, never overwritten. `expected` is checked first, for
    # a reason that names the move.
    def push_main(sha, expected:)
      remote = git("ls-remote", "origin", "refs/heads/main").split.first
      raise MainMoved, "main is at #{remote}, not #{expected}" unless remote == expected

      git("push", "--quiet", "origin", "#{sha}:refs/heads/main")
    end

    private

    # `git merge-tree --write-tree` exits 0 when clean and 1 on conflicts; the
    # first line of its output is the tree either way, then one conflicted path
    # per line up to a blank line.
    def merge_tree(main, branch)
      out, _err, status = run("git", "merge-tree", "--write-tree", "--name-only", "--no-messages", main, branch)
      raise "git merge-tree exited #{status.exitstatus}" unless [0, 1].include?(status.exitstatus)

      lines = out.split("\n")
      tree = lines.shift
      { tree: tree, conflicts: lines.take_while { |line| !line.empty? } }
    end

    # The merge tree with each conflicted path replaced by its resolution. The
    # index is a temporary file, so the repository's own index is not touched.
    def resolved_tree(tree, main, branch, conflicts)
      Dir.mktmpdir do |dir|
        env = { "GIT_INDEX_FILE" => File.join(dir, "index") }
        git("read-tree", tree, env: env)
        conflicts.each do |path|
          resolver = RESOLVERS.fetch(path) { raise Unresolvable, "no resolution for #{path}" }
          text = resolver.call(show(main, path), show(branch, path))
          blob = git("hash-object", "-w", "--stdin", stdin: text)
          git("update-index", "--add", "--cacheinfo", "100644,#{blob},#{path}", env: env)
        end
        git("write-tree", env: env)
      end
    end

    def commit_tree(tree, parents, message)
      args = ["commit-tree", tree]
      parents.each { |parent| args += ["-p", parent] }
      git(*args, stdin: message)
    end

    # A file at a revision, or "" when that side does not have it.
    def show(rev, path)
      out, _err, status = run("git", "show", "#{rev}:#{path}")
      status.success? ? out : ""
    end

    def git(*args, env: {}, stdin: nil)
      out, _err, status = run("git", *args, env: env, stdin: stdin)
      # Only the exit code is kept: git's stderr can carry remote URLs.
      raise "git #{args.first} exited #{status.exitstatus}" unless status.success?

      out.strip
    end

    # git hands back the changelog's bytes as they are, and it is UTF-8 (the
    # section headings carry an em dash). Under a C locale Open3 would tag them
    # US-ASCII, and matching a heading would raise on the first dash.
    def run(*cmd, env: {}, stdin: nil)
      out, err, status = Open3.capture3(env, *cmd, chdir: @dir || Dir.pwd, stdin_data: stdin.to_s)
      [out.force_encoding(Encoding::UTF_8), err, status]
    end
  end

  # The checks a merge needs green on its head (#793). The contexts are read from the rules of main and
  # develop when the merge is judged, so no second list exists to drift from them. A context is green when
  # its newest completed, non-cancelled run passed and no status on the head says otherwise; one with no
  # such run (not reported, still running, only cancelled) is not green. A run that failed and a status
  # that passed do not add up to green: any source that says no keeps it unmet. A context the rules pin to
  # an integration is met only by that integration's check run.
  module RequiredChecks
    GREEN = %w[success skipped neutral].freeze
    SHA = /\A[0-9a-f]{40}\z/
    SLUG = %r{\A[\w.-]+/[\w.-]+\z}

    # The rules or the check list could not be read, so nothing can be judged. Holds the merge.
    class Unreadable < StandardError; end

    # The real reader: `gh api ARGS`, the token from the environment. Returns [stdout, exit status].
    RUNNER = lambda do |*args|
      out, _err, status = Open3.capture3("gh", "api", *args)
      [out, status.exitstatus]
    end

    module_function

    # main is what a release lands on and develop what a back-merge lands on. Both lists are judged for
    # every merge, so a merge is never judged by fewer contexts than main's alone (fail closed).
    BRANCHES = %w[main develop].freeze

    # The required contexts of `slug` that are not green on `sha`, as names. Empty when every one is.
    def unmet(sha:, slug:, gh: RUNNER)
      raise Unreadable, "not a commit sha: #{sha.inspect}" unless SHA.match?(sha.to_s)
      raise Unreadable, "not a repository: #{slug.inspect}" unless SLUG.match?(slug.to_s)

      required = BRANCHES.flat_map { |branch| required_contexts(slug, branch, gh) }.uniq
      raise Unreadable, "no required status checks on #{BRANCHES.join(' or ')}, so the merge cannot be judged" if required.empty?

      runs = read(gh, "repos/#{slug}/commits/#{sha}/check-runs?filter=all&per_page=100",
                  "--paginate", "--jq", ".check_runs[]|{id,name,status,conclusion,app_id:.app.id}")
      statuses = read(gh, "repos/#{slug}/commits/#{sha}/statuses?per_page=100",
                      "--paginate", "--jq", ".[]|{context,state}")
      missing(required, runs, statuses).map { |req| req["context"] }
    end

    # True when an approving review of the pull request was given on `sha`. reviewDecision is per PR, so an
    # approval of an older commit still reads APPROVED after a push: only an approval of this head counts.
    def approved_on?(sha:, slug:, pr:, gh: RUNNER)
      raise Unreadable, "not a commit sha: #{sha.inspect}" unless SHA.match?(sha.to_s)
      raise Unreadable, "not a repository: #{slug.inspect}" unless SLUG.match?(slug.to_s)
      raise Unreadable, "not a pull request number: #{pr.inspect}" unless pr.to_s.match?(/\A\d+\z/)

      read(gh, "repos/#{slug}/pulls/#{pr}/reviews", "--paginate", "--jq",
           '.[]|select(.state=="APPROVED")|.commit_id').include?(sha)
    end

    # The contexts a branch's rules require, each with the integration it is pinned to (nil: any app).
    def required_contexts(slug, branch, gh)
      read(gh, "repos/#{slug}/rules/branches/#{branch}", "--paginate", "--jq",
           '.[]|select(.type=="required_status_checks")|.parameters.required_status_checks[]?|{context,integration_id}')
    end

    # Each line of the reply, parsed when it is an object. A non-zero exit keeps gh's stderr out of the message.
    def read(gh, *args)
      out, code = gh.call(*args)
      raise Unreadable, "gh api exited #{code} reading #{args.first.to_s.sub(/\?.*/, '')}" unless code.zero?

      out.lines.map(&:strip).reject(&:empty?).map { |line| line.start_with?("{") ? JSON.parse(line) : line }
    rescue JSON::ParserError
      raise Unreadable, "an unreadable reply from gh api for #{args.first.to_s.sub(/\?.*/, '')}"
    end

    # The requirements (hashes with "context" and "integration_id") that are not met. A pinned one is met
    # only by a check run of its integration: a commit status has no App identity, and a same-name run from
    # another App does not count. An unpinned one is met by a run or a status of that name.
    def missing(required, runs, statuses)
      required.reject do |req|
        name = req.fetch("context")
        pin = req["integration_id"]
        own = runs.select { |r| r["name"] == name && (pin.nil? || r["app_id"] == pin) }
        verdicts = []
        latest = own.select { |r| r["status"] == "completed" && r["conclusion"] != "cancelled" }
                    .max_by { |r| r["id"].to_i }
        verdicts << GREEN.include?(latest["conclusion"]) if latest
        unless pin
          status = statuses.find { |s| s["context"] == name }
          verdicts << (status["state"] == "success") if status
        end
        verdicts.any? && verdicts.all?
      end
    end
  end

  # The command line the workflows call. Run it from the checkout, with origin
  # reachable. Only `union` and `rehearse --push` write to origin, as whoever the
  # checkout's git credentials are (the release App, in the workflows).
  #
  #   release_merge.rb approved PR SHA            exit 0 when an approving review of PR is on SHA; 2 when none is (a
  #                                               review of an older commit does not approve the head); 1 when unreadable
  #   release_merge.rb unmet SHA                  exit 0 when every context main's rules require is green on SHA;
  #                                               exit 2 naming the ones that are not; 1 when they cannot be read
  #   release_merge.rb conflicts BRANCH           the paths BRANCH conflicts on with origin/main;
  #                                               exit 2 when one is not allow-listed
  #   release_merge.rb union BRANCH --expect-head SHA
  #                                               push origin/main + BRANCH's union, when GitHub
  #                                               refused the merge: 0 pushed, 2 held (paths named),
  #                                               3 git merges it cleanly (not explained: nothing pushed),
  #                                               1 BRANCH is not at SHA, the head that was approved (nothing pushed)
  #   release_merge.rb rehearse VERSION [--push]  judge and print the Sunday merge of release/VERSION
  #                                               without moving main; --push also puts the merge
  #                                               commit on rehearsal/VERSION as the App, then deletes it
  module Cli
    HELD = 2
    CLEAN_IN_GIT = 3
    USAGE = "usage: release_merge.rb approved PR SHA | unmet SHA | conflicts BRANCH | union BRANCH --expect-head SHA | rehearse VERSION [--push]".freeze
    # The names the workflows pass. They become refspecs and ref names, so anything else is refused here.
    BRANCH_NAME = %r{\A(release|hotfix)/\d+\.\d+\.\d+\z}
    VERSION_NAME = /\A\d+\.\d+\.\d+\z/

    module_function

    def run(argv, repo:, out:, err:, slug: ENV["GITHUB_REPOSITORY"], gh: RequiredChecks::RUNNER)
      command, *args = argv
      case command
      when "unmet"
        sha = args.fetch(0) { return usage(err) }
        return unmet(sha, slug, gh, out, err)
      when "approved"
        pr = args.fetch(0) { return usage(err) }
        sha = args.fetch(1) { return usage(err) }
        return approved(pr, sha, slug, gh, err)
      when "conflicts" then conflicts(repo, branch_arg(args) { return usage(err) }, out, err)
      when "union"
        expect = option(args, "--expect-head")
        branch = branch_arg(args) { return usage(err) }
        return usage(err) unless expect
        union(repo, branch, expect, out, err)
      when "rehearse"
        version = args.fetch(0) { return usage(err) }
        return usage(err) unless VERSION_NAME.match?(version)
        rehearse(repo, version, args.include?("--push"), out, err)
      else usage(err)
      end
    rescue Unresolvable, MainMoved, HeadMoved, RequiredChecks::Unreadable, RuntimeError => e
      err.puts "release_merge: #{e.message}"
      1
    end

    def usage(err)
      err.puts USAGE
      64
    end

    # 0 when an approving review of pull request PR is on sha; HELD (2) when none is.
    def approved(pr, sha, slug, gh, err)
      return 0 if RequiredChecks.approved_on?(sha: sha, slug: slug, pr: pr, gh: gh)

      err.puts "held: no approving review of ##{pr} is on #{sha}"
      HELD
    end

    # 0 when every required context of main is green on sha; HELD (2) naming the ones that are not,
    # one per line on stdout and in the message on stderr.
    def unmet(sha, slug, gh, out, err)
      names = RequiredChecks.unmet(sha: sha, slug: slug, gh: gh)
      return 0 if names.empty?

      names.each { |name| out.puts name }
      err.puts "held: required checks not green on #{sha}: #{names.join(', ')}"
      HELD
    end

    # The branch argument when it is a release/X.Y.Z or hotfix/X.Y.Z name; otherwise the block's value (usage).
    def branch_arg(args)
      branch = args.fetch(0) { return yield }
      BRANCH_NAME.match?(branch) ? branch : yield
    end

    # The value after a flag, or nil when the flag is absent or has no value.
    def option(args, flag)
      index = args.index(flag)
      index && args[index + 1]
    end

    def conflicts(repo, branch, out, err)
      main = repo.main_sha
      head = repo.fetch_branch(branch)
      paths = repo.conflicts(main, head)
      out.puts "main #{main}"
      out.puts "#{branch} #{head}"
      out.puts(paths.empty? ? "conflicts: none" : "conflicts: #{paths.join(', ')}")
      blocked = paths - ALLOWED_CONFLICTS
      return 0 if blocked.empty?

      err.puts "held: #{blocked.join(', ')} conflict and only #{ALLOWED_CONFLICTS.join(', ')} can be resolved; resolve #{branch} by hand"
      HELD
    end

    # `expect` is the head the caller approved and checked. The union is built
    # from the head fetched here, so a head that moved since then is refused.
    def union(repo, branch, expect, out, err)
      main = repo.main_sha
      head = repo.fetch_branch(branch)
      raise HeadMoved, "#{branch} is at #{head}, not the approved #{expect}; nothing pushed" unless head == expect
      built = repo.merge_commit(main, head, message: "Merge #{branch} into main")
      if built[:conflicts].empty?
        err.puts "git merges #{branch} into main cleanly, so GitHub's refusal is not explained: nothing pushed"
        return CLEAN_IN_GIT
      end
      unless built[:commit]
        err.puts "held: #{(built[:conflicts] - ALLOWED_CONFLICTS).join(', ')} conflict and only #{ALLOWED_CONFLICTS.join(', ')} can be resolved; nothing pushed"
        return HELD
      end
      repo.push_main(built[:commit], expected: main)
      out.puts "pushed #{built[:commit]} to main: #{built[:conflicts].join(', ')} resolved as the union of both sides"
      0
    end

    def rehearse(repo, version, push, out, err)
      branch = "release/#{version}"
      main = repo.main_sha
      head = repo.fetch_branch(branch)
      built = repo.merge_commit(main, head, message: "Rehearsal: merge #{branch} into main")
      out.puts "main #{main}"
      out.puts "#{branch} #{head}"
      blocked = built[:conflicts] - ALLOWED_CONFLICTS
      unless blocked.empty?
        out.puts "verdict: held, #{blocked.join(', ')} conflict and only #{ALLOWED_CONFLICTS.join(', ')} can be resolved"
        return HELD
      end
      out.puts(built[:conflicts].empty? ? "verdict: clean, git merges it; no union needed" : "verdict: union of both sides for #{built[:conflicts].join(', ')}")
      commit = built[:commit]
      out.puts "merge commit #{commit}"
      out.puts "tree #{repo.tree_of(commit)}"
      changed = repo.changed_paths(main, commit)
      # A release branch that is months old changes most of the tree, so the list is capped.
      shown = changed.first(25)
      out.puts "changes on main (#{changed.size}): #{shown.join(', ')}#{changed.size > shown.size ? ', ...' : ''}"
      push ? rehearsal_push(repo, version, commit, out, err) : out.puts("not pushed (rehearse --push pushes it to rehearsal/#{version} and deletes it)")
      0
    end

    # The throwaway ref is deleted whether or not the push worked, and a delete
    # that fails is reported (the ref may remain). A failed push is the error
    # reported, so a cleanup failure after it is only printed.
    def rehearsal_push(repo, version, commit, out, err)
      ref = "refs/heads/rehearsal/#{version}"
      repo.delete_ref(ref)
      begin
        repo.push_ref(commit, ref)
      rescue RuntimeError => e
        begin
          repo.delete_ref(ref)
        rescue RuntimeError => cleanup
          err.puts "release_merge: #{cleanup.message}"
        end
        raise e
      end
      out.puts "pushed to #{ref} as the caller's credentials; deleting it"
      repo.delete_ref(ref)
    end
  end
end

if $PROGRAM_NAME == __FILE__
  exit ReleaseMerge::Cli.run(ARGV, repo: ReleaseMerge::Repo.new, out: $stdout, err: $stderr)
end

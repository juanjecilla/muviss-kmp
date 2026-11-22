# The nightly check that every live release branch still merges into main (#713).
#
# The Sunday step is the first time a release branch meets main's merge, a week
# after the conflict was made. This runs every night, for every version that is
# still in internal or beta, in version order. Each branch is merged onto the
# base the versions before it leave behind (their merges, built in memory and
# never pushed), so a version is judged as the Sunday step will judge it.
#
#   - a branch that merges cleanly, or whose only conflicts the promotion
#     resolves itself (ReleaseMerge::ALLOWED_CONFLICTS), is clean: any blocker
#     raised for that version is closed and its release-blocker label removed;
#   - any other conflict raises one "Release X.Y.Z does not merge into main"
#     issue with release-blocker, in the version's milestone (which holds the
#     Sunday step), and a single comment on "Release X.Y.Z" naming the paths;
#   - the run exits non-zero while any version is blocked, so it fails visibly.
#
# Nothing is pushed and no ref moves: the merges exist only as objects.
#
#   ruby fastlane/release_mergeability.rb        GH_REPO and GH_TOKEN set (release-train.yml)
require "json"
require "stringio"
require_relative "release_merge"
require_relative "release_stages"

module ReleaseMergeability
  BLOCKER_LABEL = "release-blocker".freeze
  Result = Struct.new(:version, :verdict, :paths, :reason)

  def self.blocker_title(version)
    "Release #{version} does not merge into main"
  end

  # Hidden in the comment so a rerun can find it. It names the paths, so a
  # different conflict is a new comment, and the same one is not repeated.
  def self.marker(paths)
    "<!-- release-mergeability: #{paths.sort.join(', ')} -->"
  end

  class Checker
    def initialize(git:, issues:, out: $stdout)
      @git = git
      @issues = issues
      @out = out
    end

    # One Result per live version whose branch exists, in the order judged.
    def run
      base = @git.main_sha
      live_versions.each_with_object([]) do |version, results|
        head = @git.rev_parse("origin/release/#{version}")
        next unless head

        result, base = judge(version, base, head)
        apply(result)
        @out.puts line(result)
        results << result
      end
    end

    def failed?(results)
      results.any? { |r| %i[blocked unknown].include?(r.verdict) }
    end

    private

    # [Result, the base the next version is judged on]. A version that cannot
    # merge leaves the base where it was, so the ones after it are judged on
    # the last merge that could be built, and say so in their reason.
    def judge(version, base, head)
      merged = @git.merge_commit(base, head, message: "Merge release/#{version} into main (nightly check, not pushed)")
      if merged[:commit].nil?
        [Result.new(version, :blocked, merged[:conflicts], "conflicts in #{merged[:conflicts].join(', ')}"), base]
      else
        verdict = merged[:conflicts].empty? ? :clean : :allowed
        [Result.new(version, verdict, merged[:conflicts], nil), merged[:commit]]
      end
    rescue ReleaseMerge::Unresolvable => e
      [Result.new(version, :blocked, ["CHANGELOG.md"], e.message), base]
    rescue StandardError
      [Result.new(version, :unknown, [], "the merge could not be checked"), base]
    end

    def apply(result)
      case result.verdict
      when :blocked then raise_blocker(result)
      when :clean, :allowed then clear_blocker(result.version)
      end
    end

    def raise_blocker(result)
      body = blocker_body(result)
      existing = @issues.open_blocker(result.version)
      if existing
        @issues.update_blocker_body(existing["number"], body) unless existing["body"] == body
      else
        @issues.create_blocker(result.version, body)
      end
      comment_once(result)
    end

    def clear_blocker(version)
      @issues.clear_blocker(version) if @issues.open_blocker(version)
    end

    def comment_once(result)
      issue = @issues.release_issue(result.version)
      return @out.puts("no release issue for #{result.version}; nothing to comment on") unless issue

      marker = ReleaseMergeability.marker(result.paths)
      return if @issues.comments(issue["number"]).include?(marker)

      @issues.comment(issue["number"], <<~TEXT)
        #{marker}
        Release #{result.version} no longer merges into main: #{result.paths.join(', ')} conflict. The nightly check raised "#{ReleaseMergeability.blocker_title(result.version)}" (release-blocker). It clears that issue once release/#{result.version} merges cleanly again.
      TEXT
    end

    def blocker_body(result)
      <<~TEXT
        Release #{result.version} does not merge into main. #{result.reason}.

        Conflicting paths:
        #{result.paths.map { |p| "- #{p}" }.join("\n")}

        The Sunday step can only resolve #{ReleaseMerge::ALLOWED_CONFLICTS.join(', ')} by itself, so this needs a change on release/#{result.version}: a PR into the branch, or a rebase onto main. The nightly check (#713) closes this issue and removes release-blocker once the branch merges again.

        While it is open the Sunday step holds release #{result.version}.
      TEXT
    end

    def line(result)
      case result.verdict
      when :clean then "release #{result.version}: merges into main"
      when :allowed then "release #{result.version}: merges, resolving #{result.paths.join(', ')}"
      when :blocked then "release #{result.version}: does NOT merge: #{result.reason}"
      when :unknown then "release #{result.version}: #{result.reason}"
      end
    end

    # Live versions (internal or beta) in version order. A marker that does not
    # parse is skipped, as the promotion skips it, rather than failing the run.
    def live_versions
      @issues.release_issues.filter_map do |issue|
        state = begin
          ReleaseStages.parse(issue["body"])
        rescue JSON::ParserError
          nil
        end
        next unless state && [ReleaseStages::INTERNAL, ReleaseStages::BETA].include?(state["stage"])

        state["version"]
      end.sort_by { |version| Gem::Version.new(version) }
    end
  end

  # The real issue side, through gh. The release issues are read by the
  # promotion's own adapter, so both see the same list.
  class GitHubIssues
    def initialize(repo:, token:)
      require_relative "release_promote"
      @repo = repo
      @env = { "GH_TOKEN" => token }
      @release = ReleasePromotionAdapters::GitHub.new(repo: repo, token: token)
    end

    def release_issues
      @release.release_issues
    end

    def release_issue(version)
      release_issues.find { |issue| issue["title"] == ReleaseStages.title(version) }
    end

    def open_blocker(version)
      title = ReleaseMergeability.blocker_title(version)
      found = JSON.parse(gh("issue", "list", "--repo", @repo, "--state", "open", "--label", BLOCKER_LABEL,
                            "--search", %(in:title "#{title}"), "--json", "number,title,body"))
      found.find { |issue| issue["title"] == title }
    end

    def create_blocker(version, body)
      gh("issue", "create", "--repo", @repo, "--title", ReleaseMergeability.blocker_title(version),
         "--label", BLOCKER_LABEL, "--milestone", version, "--body-file", "-", stdin: body)
    end

    def update_blocker_body(number, body)
      gh("issue", "edit", number.to_s, "--repo", @repo, "--body-file", "-", stdin: body)
    end

    # Removes the label, then closes the issue: a closed issue is no longer one
    # the next run looks for, so a clear is not repeated either.
    def clear_blocker(version)
      number = open_blocker(version)&.fetch("number")
      return unless number

      gh("issue", "edit", number.to_s, "--repo", @repo, "--remove-label", BLOCKER_LABEL)
      gh("issue", "close", number.to_s, "--repo", @repo,
         "--comment", "#{ReleaseMergeability.blocker_title(version)} no longer applies: the branch merges into main again (nightly check, #713).")
    end

    # The bodies of an issue's comments, as one string: enough to search for a marker.
    def comments(number)
      gh("api", "--paginate", "repos/#{@repo}/issues/#{number}/comments", "--jq", ".[].body")
    end

    def comment(number, body)
      gh("issue", "comment", number.to_s, "--repo", @repo, "--body-file", "-", stdin: body)
    end

    private

    def gh(*args, stdin: nil)
      ReleasePromotionAdapters::Shell.capture("gh", *args, stdin: stdin, env: @env)
    end
  end
end

if __FILE__ == $PROGRAM_NAME
  repo = ENV.fetch("GH_REPO") { ENV.fetch("GITHUB_REPOSITORY", "") }
  checker = ReleaseMergeability::Checker.new(
    git: ReleaseMerge::Repo.new,
    issues: ReleaseMergeability::GitHubIssues.new(repo: repo, token: ENV["GH_TOKEN"].to_s),
  )
  results = checker.run
  if (summary = ENV["GITHUB_STEP_SUMMARY"])
    File.open(summary, "a") { |f| results.each { |r| f.puts "- #{r.version}: #{r.verdict}#{r.reason ? " — #{r.reason}" : ''}" } }
  end
  exit(checker.failed?(results) ? 1 : 0)
end

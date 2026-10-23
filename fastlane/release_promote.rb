# The Sunday step of the staged releases (ADR 0030): promote every version whose
# step is due, when every gate passes, and hold it (saying why on its release
# issue) when one does not. A held version is retried the next Sunday, or by a
# dispatch of release-promote.yml.
#
#   ruby fastlane/release_promote.rb run                       DRY_RUN=false acts; anything else only prints
#   ruby fastlane/release_promote.rb complete X.Y.Z            the ramp reached 100%: close the release issue
#   ruby fastlane/release_promote.rb record-production X.Y.Z   release.yml promoted it (the manual path)
#
# ReleaseGates is pure. ReleasePromotion decides and acts through four adapters
# (GitHub, git, Play, Sentry) that the tests replace with fakes; the real ones
# shell out to gh, git and fastlane and never print a token.
require "date"
require "json"
require "open3"
require "stringio"
require "tmpdir"
require_relative "release_stages"
require_relative "sentry_gate"

module ReleaseGates
  BRANCH_PREFIX = "release/".freeze

  module_function

  # The step due for a state today, or nil. Production is a full week after the
  # beta that actually happened, so a held beta step moves production with it.
  def due_step(state, today)
    case state["stage"]
    when ReleaseStages::INTERNAL
      state["beta_on"] && today >= Date.iso8601(state["beta_on"]) ? :beta : nil
    when ReleaseStages::BETA
      # An iOS-only override merged this version without Play (ADR 0033): Play
      # waits for the owner, so nothing here may promote it.
      return nil if state["ios_override_on"] && state["merged_on"]
      # Merged into main, but the Play promotion after it failed: retry only
      # the promotion, never the merge.
      return :play_retry if state["merged_on"]

      done = state["beta_done_on"]
      done && today >= ReleaseStages.production_on(Date.iso8601(done)) ? :production : nil
    end
  end

  # Why the step must wait, empty when every gate passes. A fact that could not
  # be read is nil and holds: "we could not look" is not "nothing is wrong".
  def holds(facts:)
    reasons = shared_holds(facts)
    return reasons if facts[:rc_tag].nil?

    reasons << "the versionCode of #{facts[:rc_tag]} could not be read" if facts[:rc_code].nil?
    if facts[:expect_beta] && facts[:rc_code]
      if facts[:beta_has_rc].nil?
        reasons << "the beta track could not be read"
      elsif facts[:beta_has_rc] == false
        reasons << "the beta track's newest build is not #{facts[:rc_tag]} (versionCode #{facts[:rc_code]}): production promotes from beta"
      end
    end
    reasons << "Sentry: #{facts[:sentry]}" if facts[:sentry]
    Array(facts[:others_in_beta]).each do |other|
      reasons << "#{other} is still on the beta track; it moves to production first"
    end
    reasons.compact
  end

  # The gates both stores share (ADR 0033): the release-blocker list, fixes in
  # flight, the branch head being the RC, the RC built, CI green, the notes
  # written. Nothing platform-specific, so iOS (ios_train.rb) calls this too.
  def shared_holds(facts)
    return ["no release candidate has been built for this version"] if facts[:rc_tag].nil?

    reasons = []
    if facts[:blockers].nil?
      reasons << "the release-blocker list could not be read"
    else
      facts[:blockers].each { |item| reasons << "release-blocker: #{item}" }
    end
    if facts[:fix_prs].nil?
      reasons << "the open PRs into the branch could not be read"
    else
      facts[:fix_prs].each { |item| reasons << "fix in flight, a PR into the branch: #{item}" }
    end
    reasons << branch_reason(facts) if facts[:head_is_rc] != true
    reasons << rc_reason(facts) unless facts[:rc_built] == true
    reasons << ci_reason(facts) unless facts[:ci_green] == true
    reasons << notes_reason(facts) unless facts[:draft_locales] == []
    reasons.compact
  end

  def branch_reason(facts)
    return "the branch head could not be compared with #{facts[:rc_tag]}" if facts[:head_is_rc].nil?

    "the branch moved after #{facts[:rc_tag]} was built: a newer candidate is needed"
  end

  def rc_reason(facts)
    return "#{facts[:rc_tag]} did not build or upload successfully (or its run could not be read)"
  end

  def ci_reason(facts)
    return "CI on the branch head is not green" if facts[:ci_green] == false

    "CI on the branch head could not be read"
  end

  def notes_reason(facts)
    return "the store notes could not be read" if facts[:draft_locales].nil?

    "the store notes are still a DRAFT (or missing) for #{facts[:draft_locales].join(', ')}"
  end
end

class ReleasePromotion
  Outcome = Struct.new(:version, :step, :status, :reasons)

  def initialize(github:, git:, play:, sentry:, today:, apply:, out: $stdout)
    @github = github
    @git = git
    @play = play
    @sentry = sentry
    @today = today
    @apply = apply
    @out = out
  end

  # Every live version (internal or beta) whose step is due, in the order a
  # Sunday must run them: production before beta, so the beta track is free.
  def run(only: nil)
    records = live_records
    ordered = records.sort_by { |_, state| [state["stage"] == ReleaseStages::BETA ? 0 : 1, Gem::Version.new(state["version"])] }
    ordered.map do |issue, state|
      next nil if only && state["version"] != only

      step = ReleaseGates.due_step(state, @today)
      next Outcome.new(state["version"], nil, :not_due, []) unless step

      # A merge that happened but was never recorded (the issue update failed
      # after it): the branch is gone by now, so the gates could only hold.
      # Record it and retry the promotion alone.
      if step == :production
        merged = safe("merged release PR") { @github.merged_release_pr(state["version"]) }
        if merged
          state = state.merge("merged_on" => @today.iso8601, "release_pr" => merged, "rc_tag" => state["rc_tag"] || safe("latest RC") { @git.latest_rc(state["version"]) })
          if @apply
            persist(issue, state, "release/#{state['version']} was already merged into main (PR ##{merged}); retrying only the promotion to production.")
          end
          step = :play_retry
        end
      end

      # The merge already passed every gate; what is left is Play alone.
      facts = step == :play_retry ? retry_facts(state) : gather(state, step)
      reasons = step == :play_retry ? retry_holds(facts) : ReleaseGates.holds(facts: facts)
      unless reasons.empty?
        hold!(issue, state, reasons)
        next Outcome.new(state["version"], step, :held, reasons)
      end
      unless @apply
        @out.puts "would #{step} #{state['version']}"
        next Outcome.new(state["version"], step, :would_do, [])
      end
      problem, state = perform!(step, issue, state, facts)
      if problem
        hold!(issue, state, [problem])
        next Outcome.new(state["version"], step, :held, [problem])
      end
      Outcome.new(state["version"], step, :done, [])
    end.compact
  end

  # The ramp reached 100%: the version is finished and its issue closes.
  def complete(version)
    issue, state = find(version)
    return @out.puts("no release issue for #{version}; nothing to close") unless issue
    return @out.puts("the release issue for #{version} has no readable marker; nothing to close") unless state
    return @out.puts("#{version} is already done; nothing to close") if state["stage"] == ReleaseStages::DONE && issue["state"].to_s.casecmp?("closed")

    state = state.merge("stage" => ReleaseStages::DONE, "completed_on" => @today.iso8601, "holds" => [])
    @github.update_issue(issue["number"], ReleaseStages.render(state))
    if ios_still_ramping?(state)
      # iOS has its own track (ADR 0033); `complete_ios` in ios_promote.rb closes the issue when it finishes.
      @github.comment(issue["number"], "#{version} reached 100% of production on Play. The iOS phased release is still on its own track; this issue closes when it finishes.")
    else
      @github.close_issue(issue["number"], "#{version} reached 100% of production. Closed.")
    end
  end

  # release.yml promoted the version itself (the manual path): record it.
  def record_production(version)
    issue, state = find(version)
    return @out.puts("no release issue for #{version}; nothing to record") unless issue
    return @out.puts("the release issue for #{version} has no readable marker; nothing to record") unless state
    return if %w[production done].include?(state["stage"])

    state = state.merge("stage" => ReleaseStages::PRODUCTION, "production_started_on" => @today.iso8601, "holds" => [])
    @github.update_issue(issue["number"], ReleaseStages.render(state))
    @github.comment(issue["number"], "Promoted to production at 20% by release.yml (a manual merge, outside the Sunday step).")
  end

  private

  # iOS is on the train for this version when it has an RC or has left its first stage, and has neither
  # finished nor been superseded. A version that never reached TestFlight is not waiting on iOS (#430).
  def ios_still_ramping?(state)
    track = ReleaseStages.ios_of(state)
    return false unless track
    return false if track["stage"] == ReleaseStages::DONE || track["superseded_on"]

    !track["rc_tag"].nil? || track["stage"] != ReleaseStages::INTERNAL
  end

  def live_records
    @github.release_issues.map { |issue| [issue, state_of(issue)] }
      .select { |_, state| state && [ReleaseStages::INTERNAL, ReleaseStages::BETA].include?(state["stage"]) }
  end

  def find(version)
    issue = @github.release_issues.find { |i| i["title"] == ReleaseStages.title(version) }
    return [nil, nil] unless issue

    [issue, state_of(issue)]
  end

  # People edit the marker by hand to move a date, so it can stop parsing. That
  # version is skipped, loudly and by name, rather than crashing the step for
  # every other version (the train holds on it too, ReleaseStages.plan).
  def state_of(issue)
    ReleaseStages.parse(issue["body"])
  rescue JSON::ParserError
    @out.puts "::warning::the marker in issue ##{issue['number']} \"#{issue['title']}\" is not valid JSON; skipping it until it is fixed"
    nil
  end

  # Every fact the gates need. A read that fails becomes nil (holds), not a crash.
  def gather(state, step = nil)
    version = state["version"]
    branch = "#{ReleaseGates::BRANCH_PREFIX}#{version}"
    rc = safe("latest RC") { @git.latest_rc(version) }
    facts = {
      rc_tag: rc,
      others_in_beta: others_in_beta(version),
      blockers: safe("blockers") { @github.blockers(version) },
      fix_prs: safe("PRs into the branch") { @github.open_prs_into(branch) },
    }
    return facts unless rc

    head = safe("branch head") { @git.branch_head(version) }
    facts[:head] = head
    rc_sha = safe("RC commit") { @git.sha_of(rc) }
    facts[:head_is_rc] = head && rc_sha ? head == rc_sha : nil
    facts[:rc_built] = safe("RC run") { @github.run_conclusion(rc_sha, "release-rc.yml") == "success" } if rc_sha
    facts[:ci_green] = safe("CI") { head && @github.checks_green?(head) } if head
    facts[:draft_locales] = safe("notes") { @git.draft_locales(version) }
    facts[:rc_code] = safe("version code") { @git.version_code(rc) }
    facts[:sentry] = facts[:rc_code] ? sentry_hold(version, facts[:rc_code]) : nil
    # Production promotes from the beta track, so it must carry this RC before
    # anything is merged (#349): checked after the merge, a mismatch would
    # leave main released and Play not.
    if step == :production
      facts[:expect_beta] = true
      facts[:beta_has_rc] = facts[:rc_code] ? safe("beta track") { @play.carries?("beta", facts[:rc_code]) } : nil
    end
    facts
  end

  def retry_facts(state)
    rc = state["rc_tag"]
    { rc_tag: rc, rc_code: rc && safe("version code") { @git.version_code(rc) } }
  end

  def retry_holds(facts)
    facts[:rc_code] ? [] : ["the merged RC's versionCode could not be read"]
  end

  # Read again each time, not from the start of the run: a production step that
  # ran a moment ago has already left the beta track.
  def others_in_beta(version)
    live_records.map { |_, s| s }
      .select { |s| s["stage"] == ReleaseStages::BETA && s["version"] != version }
      .map { |s| s["version"] }
  end

  def sentry_hold(version, code)
    release = SentryGate.release_name(version_name: version, version_code: code)
    @sentry.hold(release)
  rescue StandardError => e
    "could not be asked (#{e.class}); holding until it can"
  end

  def safe(what)
    yield
  rescue StandardError => e
    @out.puts "could not read #{what}: #{e.class}"
    nil
  end

  # Returns nil when the step went through, or the reason it did not (the
  # version is then held with that reason, and retried next Sunday).
  def perform!(step, issue, state, facts)
    return promote_to_production!(issue, state, facts) if step == :play_retry

    version = state["version"]
    rc = facts[:rc_tag]
    if step == :beta
      problem = act("promote #{rc} to open beta") { @play.promote_to_beta(facts[:rc_code]) }
      return [problem, state] if problem

      state = state.merge("stage" => ReleaseStages::BETA, "beta_done_on" => @today.iso8601, "rc_tag" => rc, "holds" => [])
      persist(issue, state, "Promoted #{rc} (versionCode #{facts[:rc_code]}) from internal to open beta.")
      [nil, state]
    else
      pr = nil
      problem = act("open the PR into main") { pr = @github.release_pr(version) || @github.open_release_pr(version) }
      return [problem, state] if problem

      problem = act("merge release/#{version} into main") { @github.merge(pr, facts[:head]) }
      return [problem, state] if problem

      # Recorded before Play is asked: if the promotion fails, the next run
      # retries only the promotion, and never merges again.
      state = state.merge("merged_on" => @today.iso8601, "release_pr" => pr, "rc_tag" => rc, "holds" => [])
      persist(issue, state, "Merged release/#{version} into main (PR ##{pr}); release.yml tags v#{version}.")
      promote_to_production!(issue, state, facts)
    end
  end

  # Beta → production here, not in release.yml: the run then reaches a newer
  # version's beta step only once this one has left the beta track, so the
  # two never race for it (ADR 0030). The version stays `beta` until Play
  # took it, which is what keeps a failed promotion pending.
  def promote_to_production!(issue, state, facts)
    rc = facts[:rc_tag]
    problem = act("promote #{rc} from beta to production") { @play.promote_to_production(facts[:rc_code]) }
    return [problem, state] if problem

    state = state.merge("stage" => ReleaseStages::PRODUCTION, "production_started_on" => @today.iso8601, "holds" => [])
    persist(issue, state, "Promoted #{rc} (versionCode #{facts[:rc_code]}) from beta to production at 20%. The daily ramp takes it to 100%.")
    [nil, state]
  end

  # Runs one side effect. A failure becomes the hold's reason, without the
  # exception's message: a request error can carry a URL with a key in it.
  def act(what)
    yield
    nil
  rescue StandardError => e
    "could not #{what} (#{e.class})"
  end

  def hold!(issue, state, reasons)
    line = reasons.map { |r| "- #{r}" }.join("\n")
    if reasons == Array(state["holds"])
      @out.puts "#{state['version']} held (unchanged): #{reasons.join('; ')}"
      return
    end
    @out.puts "#{state['version']} held: #{reasons.join('; ')}"
    return unless @apply

    state = state.merge("holds" => reasons)
    persist(issue, state, "Held, and will be retried next Sunday:\n\n#{line}")
  end

  def persist(issue, state, comment)
    @github.update_issue(issue["number"], ReleaseStages.render(state))
    @github.comment(issue["number"], comment)
  end
end

# The real adapters. Each shells out; none prints its arguments on failure.
module ReleasePromotionAdapters
  class Shell
    def self.capture(*cmd, stdin: nil, env: {})
      out, err, status = Open3.capture3(env, *cmd, stdin_data: stdin.to_s)
      raise "#{cmd.first} exited #{status.exitstatus}" unless status.success?

      out.strip
    end
  end

  class GitHub
    def initialize(repo:, token:)
      @repo = repo
      @env = { "GH_TOKEN" => token }
    end

    def gh(*args, stdin: nil)
      Shell.capture("gh", *args, stdin: stdin, env: @env)
    end

    def release_issues
      # Every page: `gh issue list --limit` is a maximum, and a live issue
      # missed here would let a newer RC past the beta gate.
      out = gh("api", "--paginate", "repos/#{@repo}/issues?labels=release&state=all&per_page=100",
               "--jq", ".[] | select(.pull_request | not) | {number, title, body, state}")
      out.lines.map(&:strip).reject(&:empty?).map { |line| JSON.parse(line) }
    end

    def blockers(version)
      query = "repo:#{@repo} is:open label:release-blocker milestone:\"#{version}\""
      gh("api", "-X", "GET", "search/issues", "-f", "q=#{query}", "--jq", ".items[] | \"#\\(.number) \\(.title)\"").lines.map(&:strip).reject(&:empty?)
    end

    def open_prs_into(branch)
      JSON.parse(gh("pr", "list", "--repo", @repo, "--base", branch, "--state", "open", "--json", "number,title"))
        .map { |pr| "##{pr['number']} #{pr['title']}" }
    end

    def run_conclusion(sha, workflow)
      out = gh("api", "repos/#{@repo}/actions/workflows/#{workflow}/runs?head_sha=#{sha}&per_page=5", "--jq", ".workflow_runs[0].conclusion // \"\"")
      out.empty? ? nil : out
    end

    def checks_green?(sha)
      rows = gh("api", "--paginate", "repos/#{@repo}/commits/#{sha}/check-runs?per_page=100", "--jq", ".check_runs[] | \"\\(.status) \\(.conclusion)\"")
      lines = rows.lines.map(&:strip).reject(&:empty?)
      return false if lines.empty?

      lines.all? { |row| row.start_with?("completed ") && %w[success skipped neutral].include?(row.split.last) }
    end

    def release_pr(version)
      pr_number(version, "open")
    end

    def merged_release_pr(version)
      pr_number(version, "merged")
    end

    # Only PRs whose head is this repository: a fork can open one from a branch
    # with the same name.
    def pr_number(version, state)
      number = gh("pr", "list", "--repo", @repo, "--base", "main", "--head", "release/#{version}", "--state", state,
                  "--json", "number,isCrossRepository", "--jq", "[.[]|select(.isCrossRepository|not)][0].number // \"\"")
      number.empty? ? nil : Integer(number)
    end

    def open_release_pr(version)
      body = "Ships **#{version}** to production (ADR 0030). Opened by the Sunday promotion (release-promote.yml). Merged with a merge commit by the release App; release.yml tags v#{version}, and the Sunday step promotes the beta build to production."
      gh("label", "create", "auto-release", "--repo", @repo, "--color", "1D76DB", "--description", "Merged by the Sunday promotion, which promotes it itself; release.yml does not (ADR 0030)", "--force")
      url = gh("pr", "create", "--repo", @repo, "--base", "main", "--head", "release/#{version}", "--title", "release: #{version}", "--label", "auto-release", "--body", body)
      Integer(url.split("/").last)
    end

    def merge(pr, head_sha)
      gh("pr", "merge", pr.to_s, "--repo", @repo, "--merge", "--match-head-commit", head_sha)
    end

    def update_issue(number, body)
      gh("issue", "edit", number.to_s, "--repo", @repo, "--body-file", "-", stdin: body)
    end

    def comment(number, body)
      gh("issue", "comment", number.to_s, "--repo", @repo, "--body-file", "-", stdin: body)
    end

    def close_issue(number, body)
      gh("issue", "close", number.to_s, "--repo", @repo, "--comment", body)
    end
  end

  class Git
    def latest_rc(version)
      tags = Shell.capture("git", "tag", "-l", "v#{version}-rc*").lines.map(&:strip).reject(&:empty?)
      tags.max_by { |tag| tag[/-rc(\d+)\z/, 1].to_i }
    end

    def sha_of(ref)
      Shell.capture("git", "rev-parse", "#{ref}^{commit}")
    end

    def branch_head(version)
      Shell.capture("git", "rev-parse", "origin/release/#{version}")
    end

    def version_code(tag)
      Integer(Shell.capture("scripts/release/version-code.sh", tag))
    end

    # Locales whose notes still carry the DRAFT: marker, or have none.
    def draft_locales(version)
      branch = "origin/release/#{version}"
      locales = Shell.capture("git", "ls-tree", "--name-only", branch, "fastlane/metadata/android/").lines.map { |l| File.basename(l.strip) }
      locales.reject(&:empty?).select do |locale|
        notes = begin
          Shell.capture("git", "show", "#{branch}:fastlane/release-notes/v#{version}/#{locale}.txt")
        rescue StandardError
          nil
        end
        notes.nil? || notes.lines.any? { |line| line.start_with?("DRAFT:") }
      end
    end
  end

  class Play
    def initialize(env)
      @env = env
    end

    # Internal → open beta, for the RC with this versionCode.
    def promote_to_beta(version_code)
      Shell.capture("bundle", "exec", "fastlane", "android", "beta", "version_code:#{version_code}", env: @env)
    end

    # Whether the track's newest release is this versionCode.
    def carries?(track, version_code)
      Dir.mktmpdir do |dir|
        file = File.join(dir, "latest")
        Shell.capture("bundle", "exec", "fastlane", "android", "track_latest", "track:#{track}",
                      env: @env.merge("TRACK_RESULT_FILE" => file))
        File.read(file).strip == version_code.to_s
      end
    end

    # Open beta → production at 20%, for the RC with this versionCode.
    def promote_to_production(version_code)
      Shell.capture("bundle", "exec", "fastlane", "android", "promote", "rollout:0.2", "version_code:#{version_code}", env: @env)
    end
  end

  class Sentry
    def hold(release)
      SentryGate.check(org: ENV["SENTRY_ORG"], token: ENV["SENTRY_AUTH_TOKEN"], release: release)
    end
  end
end

if __FILE__ == $PROGRAM_NAME
  command, *args = ARGV
  repo = ENV.fetch("GH_REPO") { ENV.fetch("GITHUB_REPOSITORY", "") }
  token = ENV["GH_TOKEN"].to_s
  github = ReleasePromotionAdapters::GitHub.new(repo: repo, token: token)
  case command
  when "run"
    apply = ENV["DRY_RUN"] == "false"
    if apply && ENV["PLAY_SERVICE_ACCOUNT_JSON"].to_s.empty?
      puts "::notice::PLAY_SERVICE_ACCOUNT_JSON is not set; nothing to promote."
      exit 0
    end
    promotion = ReleasePromotion.new(
      github: github,
      git: ReleasePromotionAdapters::Git.new,
      play: ReleasePromotionAdapters::Play.new(ENV.to_h.select { |k, _| %w[PLAY_SERVICE_ACCOUNT_JSON PLAY_RELEASE_STATUS].include?(k) }),
      sentry: ReleasePromotionAdapters::Sentry.new,
      today: Date.today,
      apply: apply,
    )
    puts(apply ? "Applying (DRY_RUN=false)." : "Dry run: no change is made (set DRY_RUN=false to act).")
    outcomes = promotion.run(only: ENV["VERSION"].to_s.empty? ? nil : ENV["VERSION"])
    outcomes.each { |o| puts "#{o.version}: #{o.status}#{o.step ? " (#{o.step})" : ''}" }
    if (summary = ENV["GITHUB_STEP_SUMMARY"])
      File.open(summary, "a") { |f| outcomes.each { |o| f.puts "- #{o.version}: #{o.status}#{o.step ? " (#{o.step})" : ''}#{o.reasons.empty? ? '' : " — #{o.reasons.join('; ')}"}" } }
    end
  when "complete", "record-production"
    promotion = ReleasePromotion.new(github: github, git: nil, play: nil, sentry: nil, today: Date.today, apply: true)
    command == "complete" ? promotion.complete(args.fetch(0)) : promotion.record_production(args.fetch(0))
  else
    warn "usage: release_promote.rb run | complete X.Y.Z | record-production X.Y.Z"
    exit 2
  end
end

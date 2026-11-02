# The iOS track of the staged train (ADR 0033): the Sunday beta and production
# steps, the daily phased-release ramp, the owner's brake, and the iOS-only
# override. ios_train.rb decides; this file reads the facts, performs the calls
# and records the outcome on the release issue, the same marker the Play train
# writes (release_stages.rb).
#
#   ruby fastlane/ios_promote.rb run                  Sunday: beta, then production when due (DRY_RUN=false acts)
#   ruby fastlane/ios_promote.rb ramp                 daily: pause or resume each live phased release (DRY_RUN=false acts)
#   ruby fastlane/ios_promote.rb brake pause|resume X.Y.Z   the owner's brake (app-store-brake.yml)
#   ruby fastlane/ios_promote.rb override X.Y.Z       iOS only: merge release/X.Y.Z and submit iOS past a Play hold
#
# Every step is recorded on the issue. Nothing here touches Play, except that the
# override marks Play as waiting for the owner, so the Sunday Play step never
# promotes a version whose merge the iOS override made.
require "date"
require "json"
require_relative "release_stages"
require_relative "release_promote"
require_relative "sentry_gate"
require_relative "ios_train"
require_relative "app_store_connect"

class IosPromotion
  Outcome = Struct.new(:version, :step, :status, :reasons)

  BUNDLE_ID = "com.codingpit.muviss".freeze

  # `github`, `git`, `asc`, `lanes` and `sentry` are the adapters (real or fake).
  def initialize(github:, git:, asc:, lanes:, sentry:, today:, apply:, out: $stdout)
    @github = github
    @git = git
    @asc = asc
    @lanes = lanes
    @sentry = sentry
    @today = today
    @apply = apply
    @out = out
    @app_id = nil
  end

  # The Sunday step: the due iOS step of every live version, oldest first, so
  # an older version's supersede happens before the newer one is submitted.
  def run(only: nil)
    records = live_records.sort_by { |_, state| Gem::Version.new(state["version"]) }
    records.map do |issue, state|
      next nil if only && state["version"] != only

      step = IosTrain.due_step(state, @today)
      next Outcome.new(state["version"], nil, :not_due, []) unless step

      facts = gather(state, step)
      reasons = IosTrain.holds(step: step, facts: facts)
      unless reasons.empty?
        hold!(issue, state, reasons)
        next Outcome.new(state["version"], step, :held, reasons)
      end
      next Outcome.new(state["version"], step, :would_do, []) unless @apply

      problem, state = perform!(step, issue, state, facts)
      if problem
        hold!(issue, state, [problem])
        next Outcome.new(state["version"], step, :held, [problem])
      end
      Outcome.new(state["version"], step, :done, [])
    end.compact
  end

  # Daily: every live iOS rollout is paused or resumed by the crash gate.
  def ramp
    production_records.map do |issue, state|
      track = ReleaseStages.ios_of(state)
      version = state["version"]
      app = app_id
      version_row = safe("App Store version #{version}") { @asc.version(app, version) }
      unless version_row
        @out.puts "::notice::no App Store version #{version} to ramp yet"
        next Outcome.new(version, :ramp, :not_due, [])
      end
      phased = safe("phased release #{version}") { @asc.phased_release(version_row["id"]) }
      if phased && phased["state"] == IosTrain::PHASED_COMPLETE
        next complete_ios(issue, state, version)
      end

      gate = ios_gate(state)
      if SentryGate.unreadable?(gate)
        # An outage is not a crash: nothing is paused or resumed on a day Sentry
        # cannot be read, so the 30-day budget is not spent on a Sentry outage.
        @out.puts "::notice::iOS #{version}: #{gate}; the rollout is left as it is"
        next Outcome.new(version, :ramp, :not_due, [gate])
      end
      decision = IosTrain.ramp_decision(gate_hold: gate, phased: phased && phased["state"], track: track, today: @today)
      next Outcome.new(version, :ramp, :not_due, []) if decision[:action] == :none

      if decision[:action] == :budget
        hold!(issue, state, [decision[:reason]], :ios)
        next Outcome.new(version, :ramp, :held, [decision[:reason]])
      end
      unless @apply
        @out.puts "would #{decision[:action]} the iOS rollout of #{version}: #{decision[:reason]}"
        next Outcome.new(version, :ramp, :would_do, [])
      end
      by = decision[:action] == :pause ? "gate" : track["ramp"]["paused_by"]
      problem = act("#{decision[:action]} the phased release of #{version}") do
        @asc.set_phased_state(phased["id"], decision[:action] == :pause ? IosTrain::PHASED_PAUSED : IosTrain::PHASED_ACTIVE)
      end
      if problem
        hold!(issue, state, [problem], :ios)
        next Outcome.new(version, :ramp, :held, [problem])
      end
      ramp_state = IosTrain.ramp_after(track["ramp"], action: decision[:action], by: by, today: @today)
      state = with_track(state, track.merge("ramp" => ramp_state, "holds" => []))
      persist(issue, state, "iOS phased release of #{version} #{decision[:action] == :pause ? 'paused' : 'resumed'}: #{decision[:reason]}.")
      Outcome.new(version, :ramp, :done, [])
    end.compact
  end

  # The owner's brake: pause or resume the live rollout of one version.
  def brake(action, version)
    issue, state = find(version)
    return @out.puts("no release issue for #{version}; nothing to #{action}") unless issue && state && ReleaseStages.ios_of(state)

    track = ReleaseStages.ios_of(state)
    version_row = @asc.version(app_id, version)
    phased = version_row && @asc.phased_release(version_row["id"])
    decision = IosTrain.brake_decision(action: action, phased: phased && phased["state"], track: track, today: @today)
    @out.puts "#{version}: #{decision[:action]} (#{decision[:reason]})"
    return Outcome.new(version, :brake, decision[:action], [decision[:reason]]) if decision[:action] == :none || !@apply

    if decision[:action] == :budget
      hold!(issue, state, [decision[:reason]], :ios)
      return Outcome.new(version, :brake, :held, [decision[:reason]])
    end
    @asc.set_phased_state(phased["id"], decision[:action] == :pause ? IosTrain::PHASED_PAUSED : IosTrain::PHASED_ACTIVE)
    ramp_state = IosTrain.ramp_after(track["ramp"], action: decision[:action], by: decision[:action] == :pause ? "brake" : nil, today: @today)
    state = with_track(state, track.merge("ramp" => ramp_state))
    persist(issue, state, "iOS phased release of #{version} #{decision[:action] == :pause ? 'paused' : 'resumed'} by the owner (app-store-brake.yml).")
    Outcome.new(version, :brake, :done, [])
  end

  # The iOS-only path past a Play hold (ADR 0033): merge release/X into main,
  # then submit iOS to production. The same iOS gates apply, minus the merge
  # requirement, which this run performs. Play is marked as waiting for the owner.
  def override(version)
    issue, state = find(version)
    return @out.puts("no release issue for #{version}; nothing to override") unless issue && state && ReleaseStages.ios_of(state)

    track = ReleaseStages.ios_of(state)
    unless track["stage"] == IosTrain::BETA
      @out.puts "#{version}: the iOS beta step has not been submitted; the override only submits a beta that passed review"
      return Outcome.new(version, :production, :held, ["the iOS beta step is not done"])
    end

    # The merge this run performs is what IosTrain.production_holds asks for,
    # so the facts say merged only once the merge has happened (below).
    facts = gather(state, :production).merge(merged: true)
    reasons = IosTrain.holds(step: :production, facts: facts)
    unless reasons.empty?
      hold!(issue, state, reasons)
      return Outcome.new(version, :production, :held, reasons)
    end
    return Outcome.new(version, :production, :would_do, []) unless @apply

    pr = nil
    problem = act("open the PR into main") { pr = @github.release_pr(version) || @github.open_release_pr(version) }
    problem ||= act("merge release/#{version} into main") { @github.merge(pr, facts[:head]) }
    if problem
      hold!(issue, state, [problem], :ios)
      return Outcome.new(version, :production, :held, [problem])
    end
    state = state.merge("merged_on" => @today.iso8601, "release_pr" => pr, "ios_override_on" => @today.iso8601)
    state = with_track(state, track.merge("holds" => []))
    persist(issue, state, "Merged release/#{version} into main (PR ##{pr}) for the iOS-only override. Play is NOT promoted by this: the Sunday Play step will not run for #{version}; promote it by hand (play-promote.yml) when its gates pass.")
    problem, state = submit_ios_production(issue, state, facts)
    if problem
      hold!(issue, state, [problem], :ios)
      return Outcome.new(version, :production, :held, [problem])
    end

    Outcome.new(version, :production, :done, [])
  end

  private

  def live_records
    @github.release_issues.map { |issue| [issue, state_of(issue)] }
      .select { |_, state| state && (track = ReleaseStages.ios_of(state)) && %w[internal beta].include?(track["stage"]) }
  end

  def production_records
    @github.release_issues.map { |issue| [issue, state_of(issue)] }
      .select { |_, state| state && (track = ReleaseStages.ios_of(state)) && track["stage"] == IosTrain::PRODUCTION }
  end

  def find(version)
    issue = @github.release_issues.find { |i| i["title"] == ReleaseStages.title(version) }
    return [nil, nil] unless issue

    [issue, state_of(issue)]
  end

  def state_of(issue)
    ReleaseStages.parse(issue["body"])
  rescue JSON::ParserError
    @out.puts "::warning::the marker in issue ##{issue['number']} \"#{issue['title']}\" is not valid JSON; skipping it until it is fixed"
    nil
  end

  def app_id
    @app_id ||= @asc.app_id(BUNDLE_ID)
  end

  def with_track(state, track)
    state.merge(ReleaseStages::IOS_KEY => track)
  end

  # Every fact the iOS gates read. A read that fails is nil, which holds.
  def gather(state, step)
    version = state["version"]
    track = ReleaseStages.ios_of(state)
    branch = "#{ReleaseGates::BRANCH_PREFIX}#{version}"
    # Once release/X is merged into main (Play's step, or the override) the
    # branch is gone: the merge already passed the branch gates, so the RC is
    # the one that was merged and those gates are not asked again.
    merged = step == :production && state["merged_on"]
    rc = (state["rc_tag"] || track["rc_tag"] if merged) || safe("latest RC") { @git.latest_rc(version) }
    facts = {
      version: version,
      rc_tag: rc,
      blockers: safe("blockers") { @github.blockers(version) },
      fix_prs: merged ? [] : safe("PRs into the branch") { @github.open_prs_into(branch) },
    }
    return facts unless rc

    rc_sha = safe("RC commit") { @git.sha_of(rc) }
    facts[:rc_built] = safe("RC run") { @github.run_conclusion(rc_sha, "release-rc.yml") == "success" } if rc_sha
    if merged
      facts.merge!(head_is_rc: true, ci_green: true)
    else
      head = safe("branch head") { @git.branch_head(version) }
      facts[:head] = head
      facts[:head_is_rc] = head && rc_sha ? head == rc_sha : nil
      facts[:ci_green] = safe("CI") { head && @github.checks_green?(head) } if head
    end
    facts[:rc_code] = safe("version code") { @git.version_code(rc) }
    facts[:sentry_ios] = facts[:rc_code] ? sentry_hold(version, facts[:rc_code]) : nil
    facts[:ios_uploaded] = safe("TestFlight build") { build_listed?(version, facts[:rc_code]) } if facts[:rc_code]
    if step == :production
      # Merged: by Play's step (merged_on), or by this run's own merge of a
      # PR that landed without the marker being written (the Play recovery).
      facts[:merged] = state["merged_on"] ? true : merged_on_main?(version)
      facts[:ios_beta_rc] = track["rc_tag"]
      facts[:beta_review] = facts[:rc_code] ? safe("Beta App Review") { beta_review(version, facts[:rc_code]) } : nil
      facts[:previous_version], facts[:previous_status] = previous_status(version)
    end
    facts
  end

  def merged_on_main?(version)
    !safe("merged release PR") { @github.merged_release_pr(version) }.nil?
  end

  def build_listed?(version, code)
    !safe("build lookup") { @asc.build_id(app_id, version, code) }.nil?
  end

  def beta_review(version, code)
    build = @asc.build_id(app_id, version, code)
    return nil unless build

    @asc.beta_review_state(build)
  end

  # The version shipped before this one whose iOS release has been submitted but
  # is not yet done; its App Store state decides whether it is superseded.
  def previous_status(version)
    earlier = production_records.map { |_, s| s["version"] }
      .select { |v| Gem::Version.new(v) < Gem::Version.new(version) }
      .max_by { |v| Gem::Version.new(v) }
    return [nil, nil] unless earlier

    row = safe("App Store version #{earlier}") { @asc.version(app_id, earlier) }
    [earlier, row && row["state"]]
  end

  def ios_gate(state)
    version = state["version"]
    tag = ReleaseStages.ios_of(state)["rc_tag"]
    code = tag && safe("version code") { @git.version_code(tag) }
    return SentryGate.unreadable_reason("the iOS build's versionCode could not be read") if code.nil?

    sentry_hold(version, code)
  end

  def sentry_hold(version, code)
    release = SentryGate.release_name(version_name: version, version_code: code, platform: :ios)
    @sentry.hold(release)
  rescue StandardError => e
    SentryGate.unreadable_reason(e.class.to_s)
  end

  def safe(what)
    yield
  rescue StandardError => e
    @out.puts "could not read #{what}: #{e.class}"
    nil
  end

  def act(what)
    yield
    nil
  rescue StandardError => e
    "could not #{what} (#{e.class})"
  end

  # The beta step: Beta App Review for the newest RC's build, distributed to the
  # external group with a public link. Counted as done when it is submitted.
  def perform!(step, issue, state, facts)
    return submit_ios_production(issue, state, facts) if step == :production

    version = state["version"]
    problem = act("send #{facts[:rc_tag]} to iOS open beta") do
      @lanes.beta_external(facts[:rc_tag])
    end
    return [problem, state] if problem

    track = ReleaseStages.ios_of(state).merge("stage" => IosTrain::BETA, "beta_done_on" => @today.iso8601,
                                             "rc_tag" => facts[:rc_tag], "holds" => [])
    state = with_track(state, track)
    persist(issue, state, "Sent #{facts[:rc_tag]} (build #{facts[:rc_code]}) to the TestFlight external group and submitted it to Beta App Review. Review latency is not published: record the time it took here.")
    [nil, state]
  end

  # The App Store submit: first the older version is superseded if it is in
  # the way (ADR 0033 rule (a)), then this one goes in for automatic release
  # with a 7-day phased rollout.
  def submit_ios_production(issue, state, facts)
    version = state["version"]
    plan = IosTrain.previous_action(previous_version: facts[:previous_version], previous_status: facts[:previous_status])
    superseded = nil
    if plan && plan[:action] == :remove_from_review
      prev_row = safe("App Store version #{plan[:version]}") { @asc.version(app_id, plan[:version]) }
      return ["could not find #{plan[:version]} in App Store Connect to remove it from review", state] unless prev_row

      removed = false
      problem = act("remove #{plan[:version]} from review") { removed = @asc.remove_from_review(app_id, prev_row["id"]) }
      problem ||= "found no review submission holding #{plan[:version]}, so it was not removed from review" unless removed
      return [problem, state] if problem

      superseded = plan
    elsif plan && plan[:action] == :record_superseded
      superseded = plan
    end

    problem = act("submit #{version} for App Store review") { @lanes.submit(facts[:rc_tag]) }
    return [problem, state] if problem

    track = ReleaseStages.ios_of(state).merge(
      "stage" => IosTrain::PRODUCTION, "production_started_on" => @today.iso8601, "rc_tag" => facts[:rc_tag], "holds" => [],
    )
    state = with_track(state, track)
    note = ""
    if superseded
      how = superseded[:action] == :remove_from_review ? "removed from review" : "rejected, not resubmitted"
      note = " #{superseded[:version]} was superseded (#{how}) to make room."
      supersede!(superseded[:version], version, how)
    end
    persist(issue, state, "Submitted #{facts[:rc_tag]} to the App Store for review: automatic release on approval, 7-day phased rollout.#{note} Apple's supersede and phased-release rules are unverified (ADR 0033): see the first-live-run checklist.")
    [nil, state]
  end

  # The superseded version's own iOS track ends here; its issue says by what.
  def supersede!(old_version, new_version, how)
    issue, old_state = find(old_version)
    return unless issue && old_state && ReleaseStages.ios_of(old_state)

    track = ReleaseStages.ios_of(old_state).merge("stage" => ReleaseStages::DONE, "superseded_on" => @today.iso8601, "holds" => [])
    old_state = with_track(old_state, track)
    persist(issue, old_state, "iOS #{old_version} was superseded by #{new_version} (#{how}); its App Store track ends here.")
    @github.close_issue(issue["number"], "#{old_version} finished on Play and was superseded on iOS. Closed.") if old_state["stage"] == ReleaseStages::DONE
  end

  def complete_ios(issue, state, version)
    track = ReleaseStages.ios_of(state).merge("stage" => ReleaseStages::DONE, "completed_on" => @today.iso8601, "holds" => [])
    state = with_track(state, track)
    if state["stage"] == ReleaseStages::DONE
      ReleaseStages.save(@github, issue, state)
      @github.close_issue(issue["number"], "#{version} finished on both stores. Closed.")
    else
      persist(issue, state, "iOS phased release of #{version} reached 100%. Play still has its own track; this issue closes when it finishes.")
    end
    Outcome.new(version, :ramp, :done, [])
  end

  # Records the iOS holds on the issue. Only a change is written, as Play's holds are.
  def hold!(issue, state, reasons, _platform = :ios)
    track = ReleaseStages.ios_of(state)
    if reasons == Array(track["holds"])
      @out.puts "#{state['version']} iOS held (unchanged): #{reasons.join('; ')}"
      return
    end
    @out.puts "#{state['version']} iOS held: #{reasons.join('; ')}"
    return unless @apply

    state = with_track(state, track.merge("holds" => reasons))
    persist(issue, state, "iOS held, and will be retried next run:\n\n#{reasons.map { |r| "- #{r}" }.join("\n")}")
  end

  def persist(issue, state, comment)
    ReleaseStages.save(@github, issue, state)
    @github.comment(issue["number"], comment)
  end
end

# The real lane runner: each call is one fastlane lane, with its env.
module IosPromotionAdapters
  class Lanes
    def initialize(env)
      @env = env
    end

    # The Sunday beta step: the RC's build to the external group, with a public link.
    def beta_external(tag)
      Shell.capture("bundle", "exec", "fastlane", "ios", "beta_external", env: @env.merge("RELEASE_TAG" => tag))
    end

    # The App Store submit: automatic release on approval, 7-day phased release.
    def submit(tag)
      Shell.capture("bundle", "exec", "fastlane", "ios", "release", "submit_for_review:true", env: @env.merge("RELEASE_TAG" => tag))
    end
  end

  # The App Store Connect calls, with the key CI holds.
  def self.asc_from(env)
    AppStoreConnect::Client.new(
      key_id: env.fetch("APP_STORE_CONNECT_KEY_ID"),
      issuer_id: env.fetch("APP_STORE_CONNECT_ISSUER_ID"),
      key_pem: env.fetch("APP_STORE_CONNECT_KEY_P8"),
    )
  end
end

if __FILE__ == $PROGRAM_NAME
  command, *args = ARGV
  repo = ENV.fetch("GH_REPO") { ENV.fetch("GITHUB_REPOSITORY", "") }
  github = ReleasePromotionAdapters::GitHub.new(repo: repo, token: ENV["GH_TOKEN"].to_s)
  apply = ENV["DRY_RUN"] == "false"
  # Until the Apple secrets exist the iOS legs skip with a notice, in every mode,
  # and the Play train runs exactly as before (ADR 0033).
  keys = %w[APP_STORE_CONNECT_KEY_ID APP_STORE_CONNECT_ISSUER_ID APP_STORE_CONNECT_KEY_P8]
  if keys.any? { |k| ENV[k].to_s.strip.empty? }
    puts "::notice title=iOS skipped::The App Store Connect key is not set (APP_STORE_CONNECT_KEY_ID, _ISSUER_ID, _KEY_P8); nothing to do on iOS."
    exit 0
  end
  build = lambda do
    IosPromotion.new(
      github: github, git: ReleasePromotionAdapters::Git.new,
      asc: IosPromotionAdapters.asc_from(ENV),
      lanes: IosPromotionAdapters::Lanes.new(ENV.to_h.select { |k, _| k.start_with?("APP_STORE_CONNECT_", "TESTFLIGHT_", "SENTRY_", "APPLE_") }),
      sentry: ReleasePromotionAdapters::Sentry.new, today: Date.today, apply: apply,
    )
  end
  case command
  when "run"
    puts(apply ? "Applying iOS steps (DRY_RUN=false)." : "Dry run: no iOS change is made (set DRY_RUN=false to act).")
    outcomes = build.call.run(only: ENV["VERSION"].to_s.empty? ? nil : ENV["VERSION"])
    outcomes.each { |o| puts "iOS #{o.version}: #{o.status}#{o.step ? " (#{o.step})" : ''}" }
  when "ramp"
    puts(apply ? "Applying the iOS ramp (DRY_RUN=false)." : "Dry run: the iOS ramp is not changed (set DRY_RUN=false to act).")
    build.call.ramp
  when "brake"
    action, version = args
    raise ArgumentError, "usage: brake pause|resume X.Y.Z" unless %w[pause resume].include?(action) && version.to_s.match?(/\A\d+\.\d+\.\d+\z/)

    build.call.brake(action, version)
  when "override"
    version = args.fetch(0)
    raise ArgumentError, "usage: override X.Y.Z" unless version.match?(/\A\d+\.\d+\.\d+\z/)

    build.call.override(version)
  else
    warn "usage: ios_promote.rb run | ramp | brake pause|resume X.Y.Z | override X.Y.Z"
    exit 2
  end
end

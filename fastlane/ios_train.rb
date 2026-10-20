# The iOS track of the staged train (ADR 0033): which step is due, what holds it,
# how an older App Store version is superseded, and when the phased release is
# paused or resumed. Pure: no I/O, no fastlane, so fastlane/test/ios_train_test.rb
# runs under plain ruby. ios_promote.rb makes the App Store Connect, GitHub and
# Sentry calls and feeds these decisions the facts they read.
#
# Apple's behaviour that is not verified yet is recorded where the code relies on
# it, and listed as a first-live-run checklist in docs/RELEASING.md (iOS in the train).
require "date"
require_relative "release_stages"
require_relative "release_promote"

module IosTrain
  INTERNAL = ReleaseStages::INTERNAL
  BETA = ReleaseStages::BETA
  PRODUCTION = ReleaseStages::PRODUCTION

  # Apple's pause budget per version, cumulative across pauses (ADR 0033, assumption 11).
  PAUSE_BUDGET_DAYS = 30

  # App Store Connect's appStoreVersion states, as the rules here read them.
  # Anything else is unknown, and holds rather than acts (see supersede_plan).
  LIVE_STATES = %w[READY_FOR_SALE].freeze
  IN_REVIEW_STATES = %w[WAITING_FOR_REVIEW IN_REVIEW].freeze
  REJECTED_STATES = %w[REJECTED].freeze
  PENDING_RELEASE_STATES = %w[PENDING_DEVELOPER_RELEASE].freeze
  # States that are not in review, not live and not awaiting release: a version
  # still being prepared, or one Developer Rejected. They are nothing to supersede.
  IDLE_STATES = %w[PREPARE_FOR_SUBMISSION DEVELOPER_REJECTED REMOVED_FROM_SALE].freeze

  # Phased release states: Apple's documented PhasedReleaseState (ADR 0033
  # assumption 2), INACTIVE, ACTIVE, PAUSED and COMPLETE.
  PHASED_ACTIVE = "ACTIVE".freeze
  PHASED_PAUSED = "PAUSED".freeze
  PHASED_COMPLETE = "COMPLETE".freeze

  BETA_APPROVED = "APPROVED".freeze

  module_function

  # The iOS step due for a state today, or nil. Beta is due on the Sunday after
  # the cut, as on Play; production a full week after the beta was submitted.
  def due_step(state, today)
    track = ReleaseStages.ios_of(state)
    return nil unless track

    case track["stage"]
    when INTERNAL
      state["beta_on"] && today >= Date.iso8601(state["beta_on"]) ? :beta : nil
    when BETA
      done = track["beta_done_on"]
      done && today >= ReleaseStages.production_on(Date.iso8601(done)) ? :production : nil
    end
  end

  # What supersedes an older App Store version (ADR 0033 rule (a)) when the next
  # one is due. The returned action is what ios_promote.rb does; `:hold` names the
  # version and waits, because submitting over it could end its rollout.
  #   :none               nothing to do (no older version, or it is live or idle)
  #   :remove_from_review take it out of review, then mark it superseded
  #   :record_superseded  it was rejected: mark it superseded, copy the reason across
  #   :hold               it is Pending Developer Release, or a status is unknown
  def supersede_plan(previous_status)
    status = previous_status.to_s
    return { action: :none, reason: nil } if previous_status.nil?
    return { action: :none, reason: nil } if LIVE_STATES.include?(status) || IDLE_STATES.include?(status)
    return { action: :remove_from_review, reason: nil } if IN_REVIEW_STATES.include?(status)
    return { action: :record_superseded, reason: nil } if REJECTED_STATES.include?(status)

    if PENDING_RELEASE_STATES.include?(status)
      return { action: :hold, reason: "the previous iOS version is Pending Developer Release; it is not superseded, so this one waits until it is live" }
    end

    { action: :hold, reason: "the previous iOS version's status (#{status}) is not one the train knows; not superseding it" }
  end

  # Why the iOS step must wait; empty when every gate passes. Shared gates come
  # first (ReleaseGates.shared_holds): they hold Play too. The rest are iOS's own:
  # its build in TestFlight, its own crash gate, its Beta App Review, the merge,
  # and an older version still pending release. A fact that could not be read is
  # nil and holds.
  def holds(step:, facts:)
    reasons = ReleaseGates.shared_holds(facts)
    return reasons if facts[:rc_tag].nil?

    case facts[:ios_uploaded]
    when nil then reasons << "the iOS build of #{facts[:rc_tag]} could not be read from App Store Connect"
    when false then reasons << "the iOS build of #{facts[:rc_tag]} is not in TestFlight (the Monday ios-rc job did not upload it)"
    end
    reasons << "iOS Sentry: #{facts[:sentry_ios]}" if facts[:sentry_ios]
    # The iOS build number is the RC's versionCode (ios-rc stamps it), so the
    # Play read covers it; a missing one is held here too.
    reasons << "the versionCode of #{facts[:rc_tag]} could not be read" if facts[:rc_code].nil?

    if step == :production
      reasons.concat(production_holds(facts))
    end
    reasons.compact
  end

  def production_holds(facts)
    reasons = []
    case facts[:beta_review]
    when nil then reasons << "the iOS Beta App Review state could not be read"
    when BETA_APPROVED then nil
    else reasons << "Beta App Review has not approved the iOS build of #{facts[:rc_tag]} (#{facts[:beta_review]})"
    end
    if facts[:ios_beta_rc] && facts[:ios_beta_rc] != facts[:rc_tag]
      reasons << "the iOS beta build is #{facts[:ios_beta_rc]}, but #{facts[:rc_tag]} is newer: App Store submits the build Beta App Review approved; cut a new iOS beta"
    end
    unless facts[:merged] == true
      reasons << "release/#{facts[:version]} is not merged into main yet: Play production merges it, or the iOS-only override does (ADR 0033)"
    end
    plan = supersede_plan(facts[:previous_status])
    reasons << "#{facts[:previous_version]} is in the way: #{plan[:reason]}" if plan[:action] == :hold && facts[:previous_version]
    reasons
  end

  # Whether a version's previous iOS release, submitted this run, needs its
  # supersede action first. Returns the plan with the version named, or nil.
  def previous_action(previous_version:, previous_status:)
    return nil unless previous_version

    plan = supersede_plan(previous_status)
    plan.merge(version: previous_version)
  end

  # The phased-release decision for one version, run daily (ADR 0033).
  #   gate_hold: the iOS Sentry gate's hold reason, or nil when the gate passes
  #   phased:    the phased release's state as App Store Connect reports it, or nil
  #   track:     the iOS track of the version's marker
  #   today:     the run's date
  # Returns { action:, reason: } with action one of :pause, :resume, :none, :budget.
  # Rules: pause only a live rollout (ACTIVE; never during Pending Developer
  # Release, ADR assumption 3); resume only a pause the gate itself made, once the
  # gate passes; a brake pause is the owner's to resume; and a pause may not run
  # past Apple's 30-day budget, which is the owner's call.
  def ramp_decision(gate_hold:, phased:, track:, today:)
    ramp = (track && track["ramp"]) || {}
    return { action: :none, reason: "the phased release state could not be read" } if phased.nil?

    if phased == PHASED_PAUSED
      if ramp["paused_by"] == "gate" && gate_hold.nil?
        return { action: :resume, reason: "the crash gate passed on this run" }
      end
      return { action: :none, reason: "paused by the owner (brake); only the owner resumes it" } if ramp["paused_by"] == "brake"
      if ramp["paused_by"] == "gate"
        return budget_spent if paused_days_used(ramp, today) >= PAUSE_BUDGET_DAYS

        return { action: :none, reason: "paused by the crash gate, which still holds: #{gate_hold}" }
      end

      return { action: :none, reason: "paused, and not by the train: nothing to do" }
    end

    return { action: :none, reason: "the phased release is #{phased}; nothing to pause" } unless phased == PHASED_ACTIVE
    return { action: :none, reason: "the crash gate passed" } if gate_hold.nil?

    return budget_spent if paused_days_used(ramp, today) >= PAUSE_BUDGET_DAYS

    { action: :pause, reason: gate_hold }
  end

  def budget_spent
    { action: :budget, reason: "the phased release has used its #{PAUSE_BUDGET_DAYS} paused days; an owner decides (ADR 0033)" }
  end

  # Days already paused before today, across every pause of this version.
  def paused_days_used(ramp, today)
    total = ramp["paused_days"].to_i
    total += (today - Date.iso8601(ramp["paused_on"])).to_i if ramp["paused_on"] && ramp["paused_by"]
    total
  end

  # The owner's brake (app-store-brake.yml). `pause` is refused unless the release
  # is live in its rollout; `resume` clears any pause, the gate's or the brake's.
  def brake_decision(action:, phased:, track:, today:)
    ramp = (track && track["ramp"]) || {}
    paused = phased.to_s == PHASED_PAUSED
    case action
    when "pause"
      return { action: :none, reason: "already paused" } if paused
      return { action: :none, reason: "the phased release is #{phased.inspect}; only a live rollout can be paused" } unless phased == PHASED_ACTIVE

      used = paused_days_used(ramp, today)
      return { action: :budget, reason: "the phased release has used its #{PAUSE_BUDGET_DAYS} paused days" } if used >= PAUSE_BUDGET_DAYS

      { action: :pause, reason: "paused by the owner (app-store-brake.yml)" }
    when "resume"
      return { action: :none, reason: "not paused" } unless paused

      { action: :resume, reason: "resumed by the owner (app-store-brake.yml)" }
    else
      { action: :none, reason: "unknown brake action #{action.inspect}" }
    end
  end

  # The marker's ramp after a pause is made or a resume is made.
  def ramp_after(ramp, action:, by:, today:)
    ramp = (ramp || {}).dup
    case action
    when :pause
      ramp.merge("phased_state" => PHASED_PAUSED, "paused_by" => by, "paused_on" => today.iso8601)
    when :resume
      ramp.merge(
        "phased_state" => PHASED_ACTIVE,
        "paused_by" => nil,
        "paused_on" => nil,
        "paused_days" => paused_days_used(ramp, today),
      )
    else
      ramp
    end
  end
end

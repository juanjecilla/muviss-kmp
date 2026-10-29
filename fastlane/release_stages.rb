# Staged releases (ADR 0030, iOS in ADR 0033): every version walks internal ->
# open beta -> production, a week apart, unless a gate holds it. This file is
# the schedule arithmetic, the stagger rule the train applies, and the format of
# the "Release X.Y.Z" issue that carries each version's state. The workflows make
# the GitHub, Play and App Store Connect calls; nothing here does I/O except the CLI.
#
# Each platform has its own stage. The top-level fields are Play's (and the
# shared holds, which both platforms read): a marker written before iOS joined
# the train has only those, and parses to "no iOS track". A version cut since
# then carries an "ios" object with the iOS stage, its RC, its holds and its
# phased-release bookkeeping (fastlane/ios_train.rb decides on them).
#
#   ruby fastlane/release_stages.rb schedule YYYY-MM-DD        cut date -> dates, JSON
#   ruby fastlane/release_stages.rb render VERSION YYYY-MM-DD  the issue body for a fresh cut
#   ruby fastlane/release_stages.rb plan FILE.json             the train's stagger decision
#
# plan's FILE.json: {"open": ["1.1.0"], "issues": [{"title": "...", "body": "..."}]}
require "date"
require "json"

module ReleaseStages
  INTERNAL = "internal".freeze
  BETA = "beta".freeze
  PRODUCTION = "production".freeze
  DONE = "done".freeze
  STAGES = [INTERNAL, BETA, PRODUCTION, DONE].freeze

  # Play's open testing track is called "beta" in the Play API.
  BETA_TRACK = "beta".freeze

  TITLE_PREFIX = "Release ".freeze
  MARKER_PATTERN = /<!-- muviss-release: (\{.*\}) -->/.freeze
  STEP_NAMES = { INTERNAL => "Internal", BETA => "Open beta", PRODUCTION => "Production" }.freeze
  IOS_STEP_NAMES = { INTERNAL => "TestFlight internal (cut)", BETA => "TestFlight external (Beta App Review)",
                     PRODUCTION => "App Store (7-day phased release)" }.freeze
  IOS_KEY = "ios".freeze

  module_function

  # The Sunday after the cut: six days after a Monday cut (ADR 0030).
  def beta_on(cut_on)
    ahead = (7 - cut_on.wday) % 7
    cut_on + (ahead.zero? ? 7 : ahead)
  end

  # A full week on beta before production; a held beta step moves this with it.
  def production_on(beta_done_on)
    beta_done_on + 7
  end

  def schedule(cut_on)
    cut = to_date(cut_on)
    beta = beta_on(cut)
    { "cut_on" => iso(cut), "beta_on" => iso(beta), "production_on" => iso(production_on(beta)) }
  end

  def title(version)
    "#{TITLE_PREFIX}#{version}"
  end

  def new_state(version, cut_on)
    schedule(cut_on).merge(
      "version" => version,
      "stage" => INTERNAL,
      "rc_tag" => nil,
      "beta_done_on" => nil,
      "production_started_on" => nil,
      "completed_on" => nil,
      "holds" => [],
      IOS_KEY => new_ios_state,
    )
  end

  # iOS's own track. `beta_done_on` is the Sunday the RC was submitted to Beta
  # App Review; `production_started_on` the Sunday the App Store version was
  # submitted (automatic release, 7-day phased). `ramp` is the phased release as
  # the daily job last saw it, and what paused it (ADR 0033).
  def new_ios_state
    {
      "stage" => INTERNAL,
      "rc_tag" => nil,
      "beta_done_on" => nil,
      "production_started_on" => nil,
      "superseded_on" => nil,
      # The newest RC the iOS RC job uploaded to TestFlight internal (#604). Not `rc_tag`: that is the
      # build sent to external beta, and the Sunday step reads it as such. Its presence keeps the
      # version on the iOS train even while its beta step is held.
      "testflight_rc" => nil,
      "holds" => [],
      "ramp" => { "phased_state" => nil, "paused_by" => nil, "paused_on" => nil, "paused_days" => 0 },
    }
  end

  # The iOS track of a state, or nil when the marker predates iOS in the train:
  # such a version has no iOS stage, and the iOS steps leave it alone.
  def ios_of(state)
    track = state && state[IOS_KEY]
    track.is_a?(Hash) ? with_ios_defaults(track) : nil
  end

  # A hand-edited iOS object may lack a key; the defaults keep the rest readable.
  def with_ios_defaults(track)
    new_ios_state.merge(track) { |_key, default, value| value.nil? ? default : value }
      .then { |merged| merged.merge("ramp" => new_ios_state["ramp"].merge(track["ramp"].is_a?(Hash) ? track["ramp"] : {})) }
  end

  # The state a release issue carries, or nil if its body has no marker.
  def parse(body)
    match = MARKER_PATTERN.match(body.to_s)
    match && JSON.parse(match[1])
  end

  class MarkerConflict < StandardError; end

  # Saves a writer's `state` as the issue's marker and keeps `issue["body"]` in step with what was
  # written, so the next save merges against it (see write_marker).
  def save(github, issue, state)
    written = write_marker(github, issue["number"], base: parse(issue["body"]), mine: state)
    issue["body"] = render(written)
  end

  # Writes this writer's change to the marker (#623). The body is rewritten whole, so a writer that
  # read it before another writer landed would put its stale copy back; no precondition exists on an
  # issue body to refuse that. Instead each write starts from the body as it is now and applies only
  # what this writer changed: `base` is the marker it read, `mine` is what it wants. The write is then
  # read back, and merged and written again if another write replaced it. Returns the state written.
  #
  # What remains: a write that lands between this writer's fresh read and its own PATCH is overwritten,
  # and the read back cannot tell, because it shows this writer's state. The window is those two calls.
  # The REST API documents no precondition on an issue update, so only a lock would close it (#623's
  # follow-up, docs/EPICS.md). docs/RELEASING.md (staged releases) records the same window.
  def write_marker(github, number, base:, mine:, attempts: 3)
    attempts.times do
      fresh = read_marker(github.issue_body(number), number)
      merged = merge_marker(base, mine, fresh)
      github.update_issue(number, render(merged))
      readback = read_marker(github.issue_body(number), number)
      return merged if merge_marker(base, mine, readback) == normalize(readback)
    end
    raise MarkerConflict, "the marker of issue ##{number} kept changing under the write; nothing was recorded"
  end

  def read_marker(body, number)
    parse(body) || raise(MarkerConflict, "issue ##{number} has no readable marker to merge into")
  rescue JSON::ParserError
    raise MarkerConflict, "the marker of issue ##{number} is not valid JSON; nothing was recorded"
  end

  # The marker that results from writing `mine` (changed from `base`) onto `fresh`: every field this
  # writer did not change keeps its value in `fresh`. Two fields never move backwards: the stage only
  # advances, and the TestFlight record only moves to a newer RC.
  def merge_marker(base, mine, fresh)
    merge_value(normalize(base), normalize(mine), normalize(fresh))
  end

  # A hand-edited or older marker may lack iOS keys; comparing defaulted copies keeps an unchanged
  # default from counting as a change this writer made.
  def normalize(state)
    return state unless state.is_a?(Hash) && state[IOS_KEY].is_a?(Hash)

    state.merge(IOS_KEY => with_ios_defaults(state[IOS_KEY]))
  end

  def merge_value(base, mine, fresh)
    return fresh if mine == base
    return mine unless mine.is_a?(Hash) && base.is_a?(Hash) && fresh.is_a?(Hash)

    merged = (base.keys | mine.keys | fresh.keys).to_h { |key| [key, merge_value(base[key], mine[key], fresh[key])] }
    keep_furthest(merged, fresh)
  end

  def keep_furthest(merged, fresh)
    stage = fresh["stage"]
    if STAGES.include?(stage) && STAGES.include?(merged["stage"]) && STAGES.index(stage) > STAGES.index(merged["stage"])
      merged = merged.merge("stage" => stage)
    end
    return merged unless rc_number(fresh["testflight_rc"]) > rc_number(merged["testflight_rc"])

    merged.merge("testflight_rc" => fresh["testflight_rc"])
  end

  def rc_number(tag)
    tag.to_s[/-rc(\d+)\z/, 1].to_i
  end

  def stage_of(body)
    parse(body)&.fetch("stage", nil)
  end

  # The issue body for a state. The marker is the machine state; the rest is for people.
  def render(state)
    marker = "<!-- muviss-release: #{JSON.generate(state)} -->"
    lines = []
    lines << marker
    lines << ""
    lines << "# Release #{state['version']}"
    lines << ""
    lines << "Stage: **#{state['stage']}**. Milestone: `#{state['version']}`. Release branch: `release/#{state['version']}`."
    lines << ""
    lines << "| Step | Date (UTC) | Status |"
    lines << "|---|---|---|"
    lines << "| #{STEP_NAMES[INTERNAL]} (cut) | #{state['cut_on']} | #{status_of(state, INTERNAL)} |"
    lines << "| #{STEP_NAMES[BETA]} | #{beta_date_text(state)} | #{status_of(state, BETA)} |"
    lines << "| #{STEP_NAMES[PRODUCTION]} (20%, then the daily ramp) | #{production_date_text(state)} | #{status_of(state, PRODUCTION)} |"
    lines << ""
    lines << "Latest RC: #{state['rc_tag'] || 'none yet'}."
    lines << ""
    lines.concat(holds_lines("No holds.", "Held:", state["holds"]))
    lines << ""
    lines.concat(ios_render_lines(state))
    lines << "Gates, the schedule and the rules: docs/RELEASING.md (staged releases). Edit the marker only to move a date."
    lines.join("\n") + "\n"
  end

  # The iOS section of the issue. A marker with no iOS track says so, rather
  # than showing an iOS row that nothing will ever move.
  def ios_render_lines(state)
    track = ios_of(state)
    return ["iOS: not on this train (cut before ADR 0033); the iOS steps leave it alone.", ""] unless track

    cut_track = track.merge("cut_on" => state["cut_on"])
    lines = ["iOS, the same three stages, independently (ADR 0033):", ""]
    lines << "| Step | Date (UTC) | Status |"
    lines << "|---|---|---|"
    lines << "| #{IOS_STEP_NAMES[INTERNAL]} | #{state['cut_on']} | #{status_of(cut_track, INTERNAL)} |"
    lines << "| #{IOS_STEP_NAMES[BETA]} | #{ios_beta_text(state, track)} | #{status_of(cut_track, BETA)} |"
    lines << "| #{IOS_STEP_NAMES[PRODUCTION]} | #{ios_production_text(state, track)} | #{status_of(cut_track, PRODUCTION)} |"
    lines << ""
    lines << "Latest iOS RC: #{track['rc_tag'] || 'none yet'}."
    lines << "Phased release: #{ios_ramp_text(track)}."
    lines << "Superseded: #{track['superseded_on'] ? "#{track['superseded_on']} (removed from review)" : 'no'}."
    lines << ""
    lines.concat(holds_lines("No iOS holds.", "Held on iOS:", track["holds"]))
    lines << ""
  end

  def holds_lines(none, heading, holds)
    holds = Array(holds)
    return [none] if holds.empty?

    [heading] + holds.map { |hold| "- #{hold}" }
  end

  def ios_beta_text(state, track)
    track["beta_done_on"] || state["beta_on"]
  end

  def ios_production_text(state, track)
    track["production_started_on"] || (track["beta_done_on"] ? ReleaseStages.production_on(to_date(track["beta_done_on"])).iso8601 : state["production_on"])
  end

  def ios_ramp_text(track)
    ramp = track["ramp"] || {}
    return "not started" unless ramp["phased_state"]

    text = ramp["phased_state"].to_s
    text += ", paused by #{ramp['paused_by']} since #{ramp['paused_on']}" if ramp["paused_by"]
    text += " (#{ramp['paused_days'].to_i} of 30 paused days used)" if ramp["paused_days"].to_i.positive?
    text
  end

  # The train's stagger rule. `open` are the versions whose release/* branch is
  # open; `issues` are the repo's release issues ({"title", "body"}).
  # A version still in internal holds the next cut: it is on the one internal
  # track, and the new RC would replace it there. A version with no issue (a
  # branch cut before this rule existed) is adopted and counts as internal.
  #
  # A marker that no longer parses (people edit it by hand to move a date) holds
  # the train too, naming the issue, rather than crashing the run with no reason:
  # nobody can tell which stage that version is in until it is fixed.
  def plan(open:, issues:)
    stages = {}
    adopt = []
    unreadable = []
    open.each do |version|
      issue = issues.find { |i| i["title"] == title(version) }
      begin
        stage = issue && stage_of(issue["body"])
      rescue JSON::ParserError
        unreadable << version
        next
      end
      if stage
        stages[version] = stage
      else
        adopt << version
        stages[version] = INTERNAL
      end
    end
    held = stages.select { |_, stage| stage == INTERNAL }.keys
    reasons = []
    reasons << "#{held.join(', ')} still in internal: the train cuts after it moves to open beta" unless held.empty?
    unreadable.each do |version|
      reasons << "the marker in issue \"#{title(version)}\" is not valid JSON: fix it so the train knows #{version}'s stage"
    end
    hold = reasons.empty? ? nil : reasons.join("; ")
    { "hold" => hold, "adopt" => adopt, "stages" => stages, "unreadable" => unreadable }
  end

  # `state` is a Play state, or an iOS track with `cut_on` merged in: both carry
  # the same stage fields, so one rule words both.
  def status_of(state, step)
    rank = STAGES.index(step)
    current = STAGES.index(state["stage"]) || 0
    done_on = case step
              when INTERNAL then state["cut_on"]
              when BETA then state["beta_done_on"]
              when PRODUCTION then state["production_started_on"]
              end
    return "done #{done_on}" if rank < current && done_on
    return "done" if rank < current
    return "current" if rank == current && state["stage"] != DONE

    "pending"
  end

  def beta_date_text(state)
    state["beta_done_on"] || state["beta_on"]
  end

  def production_date_text(state)
    state["production_started_on"] || state["production_on"]
  end

  def to_date(value)
    value.is_a?(Date) ? value : Date.iso8601(value.to_s)
  end

  def iso(date)
    date.iso8601
  end
end

if __FILE__ == $PROGRAM_NAME
  command, *args = ARGV
  case command
  when "schedule"
    puts JSON.pretty_generate(ReleaseStages.schedule(args.fetch(0)))
  when "render"
    version, cut_on = args
    puts ReleaseStages.render(ReleaseStages.new_state(version, cut_on))
  when "plan"
    input = JSON.parse(File.read(args.fetch(0)))
    puts JSON.generate(ReleaseStages.plan(open: input.fetch("open"), issues: input.fetch("issues")))
  else
    warn "usage: release_stages.rb schedule DATE | render VERSION DATE | plan FILE.json"
    exit 2
  end
end

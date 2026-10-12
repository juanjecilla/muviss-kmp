# Staged releases (ADR 0029): every version walks internal -> open beta ->
# production, a week apart, unless a gate holds it. This file is the schedule
# arithmetic, the stagger rule the train applies, and the format of the
# "Release X.Y.Z" issue that carries each version's state. The workflows make
# the GitHub and Play calls; nothing here does I/O except the CLI below.
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

  module_function

  # The Sunday after the cut: six days after a Monday cut (ADR 0029).
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
    )
  end

  # The state a release issue carries, or nil if its body has no marker.
  def parse(body)
    match = MARKER_PATTERN.match(body.to_s)
    match && JSON.parse(match[1])
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
    holds = Array(state["holds"])
    if holds.empty?
      lines << "No holds."
    else
      lines << "Held:"
      holds.each { |hold| lines << "- #{hold}" }
    end
    lines << ""
    lines << "Gates, the schedule and the rules: docs/RELEASING.md (staged releases). Edit the marker only to move a date."
    lines.join("\n") + "\n"
  end

  # The train's stagger rule. `open` are the versions whose release/* branch is
  # open; `issues` are the repo's release issues ({"title", "body"}).
  # A version still in internal holds the next cut: it is on the one internal
  # track, and the new RC would replace it there. A version with no issue (a
  # branch cut before this rule existed) is adopted and counts as internal.
  def plan(open:, issues:)
    stages = {}
    adopt = []
    open.each do |version|
      issue = issues.find { |i| i["title"] == title(version) }
      if issue && (stage = stage_of(issue["body"]))
        stages[version] = stage
      else
        adopt << version
        stages[version] = INTERNAL
      end
    end
    held = stages.select { |_, stage| stage == INTERNAL }.keys
    hold = if held.empty?
             nil
           else
             "#{held.join(', ')} still in internal: the train cuts after it moves to open beta"
           end
    { "hold" => hold, "adopt" => adopt, "stages" => stages }
  end

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

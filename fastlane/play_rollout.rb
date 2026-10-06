# Staged-rollout policy for Google Play production (ADR 0025), kept apart from
# the Fastfile so it can be unit-tested without credentials:
#   docker run --rm -v "$PWD":/w -w /w ruby:3.3 ruby fastlane/test/play_rollout_test.rb
#
# The ramp is a function of the release tag's age, not of stored state: the
# daily cron (play-rollout-ramp.yml) recomputes the target every run and only
# ever raises the fraction. A halted release is never touched — halting is how
# a human stops the ramp, and resuming is a separate, deliberate action.
module PlayRollout
  # [minimum age in days, user fraction]. Day 0 is what `promote` sets.
  RAMP = [[0, 0.2], [2, 0.5], [4, 1.0]].freeze

  IN_PROGRESS = "inProgress".freeze
  HALTED = "halted".freeze
  COMPLETED = "completed".freeze
  DRAFT = "draft".freeze

  module_function

  def target(age_days)
    step = RAMP.select { |min_age, _| age_days >= min_age }.last
    step && step[1]
  end

  # What the release should become, or nil to leave it alone.
  # Returns [status, user_fraction]; a completed release carries no fraction.
  def next_step(status:, fraction:, age_days:)
    return nil unless status == IN_PROGRESS

    goal = target(age_days)
    return nil if goal.nil? || goal <= fraction.to_f

    goal >= 1.0 ? [COMPLETED, nil] : [IN_PROGRESS, goal]
  end
end

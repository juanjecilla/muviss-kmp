#   ruby scripts/release/test/train_day_test.rb
require "minitest/autorun"
require "time"
require_relative "../train_day"

# The nightly train (release-train.yml) is a cron at 23:00 UTC on the train
# day. GitHub starts scheduled runs late (#714: a Sunday 23:00 run started at
# 02:24 UTC on Monday), so the day is judged from the time the run was
# scheduled for: the UTC day of now minus six hours, which is the schedule's
# hour, not the run's. A dispatch always runs.
class TrainDayTest < Minitest::Test
  def utc(text) = Time.iso8601(text)

  def judged(now, event: "schedule", train_day: "1")
    TrainDay.run?(event: event, train_day: train_day, now: utc(now))
  end

  def test_the_scheduled_time_on_the_train_day_runs
    assert judged("2026-11-09T23:00:00Z") # a Monday
  end

  def test_a_run_started_past_midnight_still_counts_as_the_monday_it_was_scheduled_for
    assert judged("2026-11-10T02:24:00Z"), "the Monday 23:00 run, started at 02:24 on Tuesday (#714)"
  end

  def test_one_minute_before_the_six_hour_mark_is_still_monday
    assert judged("2026-11-10T05:59:00Z"), "05:59 Tuesday is 23:59 Monday once the six hours are taken off"
  end

  def test_the_six_hour_mark_is_tuesday
    refute judged("2026-11-10T06:00:00Z"), "06:00 Tuesday is 00:00 Tuesday, not the train day"
  end

  def test_a_scheduled_run_on_a_non_train_day_does_not_run
    refute judged("2026-11-11T23:00:00Z"), "a Wednesday"
    refute judged("2026-11-08T23:00:00Z"), "the Sunday before"
  end

  def test_a_dispatch_runs_on_any_day
    assert judged("2026-11-11T14:00:00Z", event: "workflow_dispatch"), "dispatch bypasses the day"
  end

  def test_the_train_day_is_read_as_an_iso_weekday
    assert judged("2026-11-14T23:00:00Z", train_day: "6"), "Saturday when the train day is 6"
    refute judged("2026-11-09T23:00:00Z", train_day: "6")
    assert judged("2026-11-15T23:00:00Z", train_day: "7"), "Sunday is 7, not 0"
  end

  def test_a_train_day_outside_one_to_seven_is_refused
    assert_raises(ArgumentError) { judged("2026-11-09T23:00:00Z", train_day: "0") }
    assert_raises(ArgumentError) { judged("2026-11-09T23:00:00Z", train_day: "monday") }
  end

  def test_the_command_line_prints_true_or_false
    out = `ruby #{File.expand_path("../train_day.rb", __dir__)} workflow_dispatch 1`.strip
    assert_equal "true", out
  end
end

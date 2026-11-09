# Is this run on the train day? (release-train.yml, #714)
#
# A scheduled run is judged by the UTC day of the time it was scheduled for, not
# the time it started: the nightly cron is 23:00 UTC and GitHub can start it
# hours late, past midnight. Judging the run's own clock put a Monday 23:00 run
# that started at 02:24 on Tuesday on the wrong day. The schedule's time is the
# run's time less SCHEDULE_LAG, the most a late start has been seen to cost.
# A dispatch always runs.
#
#   ruby scripts/release/train_day.rb EVENT TRAIN_DAY   prints true or false
#
# TRAIN_DAY is an ISO weekday, 1 (Monday) to 7 (Sunday).
require "time"

module TrainDay
  SCHEDULE_LAG = 6 * 3600

  module_function

  def run?(event:, train_day:, now:)
    return true unless event == "schedule"

    day = Integer(train_day, exception: false)
    raise ArgumentError, "the train day must be 1 to 7, got #{train_day.inspect}" unless day&.between?(1, 7)

    scheduled = (now - SCHEDULE_LAG).utc
    iso_weekday(scheduled) == day
  end

  # Ruby's wday has Sunday as 0; ISO has it as 7.
  def iso_weekday(time)
    time.wday.zero? ? 7 : time.wday
  end
end

if __FILE__ == $PROGRAM_NAME
  event, train_day = ARGV
  abort "usage: train_day.rb EVENT TRAIN_DAY" unless event && train_day

  puts TrainDay.run?(event: event, train_day: train_day, now: Time.now.utc)
end

# Is this run on the train day? (release-train.yml, #714)
#
# A scheduled run is judged by the UTC day of the time it was scheduled for, not
# the time it started: the nightly cron is 23:00 UTC and GitHub can start it
# hours late, past midnight. Judging the run's own clock put a Monday 23:00 run
# that started at 02:24 on Tuesday on the wrong day. The schedule's time is the
# run's time less SLOT_HOUR hours: the cron fires at SLOT_HOUR:00 UTC, so any
# start from the slot up to 24h later lands on the slot's own UTC day. A fixed
# lag bound (6h, #714) failed on a 7h47m late start (#1050).
# A dispatch always runs.
#
#   ruby scripts/release/train_day.rb EVENT TRAIN_DAY   prints true or false
#
# TRAIN_DAY is an ISO weekday, 1 (Monday) to 7 (Sunday).
require "time"

module TrainDay
  # The hour of release-train.yml's cron ('0 23 * * *'); keep them equal.
  SLOT_HOUR = 23

  module_function

  def run?(event:, train_day:, now:)
    return true unless event == "schedule"

    day = Integer(train_day, exception: false)
    raise ArgumentError, "the train day must be 1 to 7, got #{train_day.inspect}" unless day&.between?(1, 7)

    scheduled = (now - (SLOT_HOUR * 3600)).utc
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

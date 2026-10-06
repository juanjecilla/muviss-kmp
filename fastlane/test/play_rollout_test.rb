require "minitest/autorun"
require_relative "../play_rollout"

class PlayRolloutTest < Minitest::Test
  def test_target_follows_the_ramp
    assert_equal 0.2, PlayRollout.target(0)
    assert_equal 0.2, PlayRollout.target(1.9)
    assert_equal 0.5, PlayRollout.target(2)
    assert_equal 0.5, PlayRollout.target(3.99)
    assert_equal 1.0, PlayRollout.target(4)
    assert_equal 1.0, PlayRollout.target(30)
  end

  def test_negative_age_has_no_target
    assert_nil PlayRollout.target(-1)
  end

  def test_raises_an_in_progress_release
    assert_equal ["inProgress", 0.5], PlayRollout.next_step(status: "inProgress", fraction: 0.2, age_days: 2.5)
  end

  def test_completes_at_the_last_step
    assert_equal ["completed", nil], PlayRollout.next_step(status: "inProgress", fraction: 0.5, age_days: 4)
  end

  def test_never_lowers_a_fraction_a_human_raised
    assert_nil PlayRollout.next_step(status: "inProgress", fraction: 0.8, age_days: 2)
  end

  def test_leaves_a_halted_release_alone
    assert_nil PlayRollout.next_step(status: "halted", fraction: 0.2, age_days: 10)
  end

  def test_leaves_completed_and_draft_releases_alone
    assert_nil PlayRollout.next_step(status: "completed", fraction: nil, age_days: 10)
    assert_nil PlayRollout.next_step(status: "draft", fraction: nil, age_days: 10)
  end

  def test_same_fraction_is_a_no_op
    assert_nil PlayRollout.next_step(status: "inProgress", fraction: 0.2, age_days: 1)
  end
end

require "minitest/autorun"
require "tmpdir"
require "fileutils"
require_relative "../check-action-pins"

class CheckActionPinsTest < Minitest::Test
  SHA = "0123456789abcdef0123456789abcdef01234567".freeze

  def with_workflow(yaml)
    Dir.mktmpdir do |root|
      FileUtils.mkdir_p(File.join(root, ".github/workflows"))
      File.write(File.join(root, ".github/workflows/x.yml"), yaml)
      yield unpinned(root).map(&:last)
    end
  end

  def test_a_comment_cannot_fake_a_pin
    with_workflow(<<~YAML) { |bad| assert_equal ["owner/action@main"], bad }
      jobs:
        a:
          steps:
            - uses: owner/action@main # uses: owner/action@#{SHA}
    YAML
  end

  DIGEST = "sha256:#{'a' * 64}".freeze

  def test_pinned_local_and_digest_pinned_docker_pass
    with_workflow(<<~YAML) { |bad| assert_equal [], bad }
      jobs:
        a:
          steps:
            - uses: owner/ok@#{SHA} # v1
            - uses: ./local
            - uses: docker://alpine@#{DIGEST}
    YAML
  end

  def test_a_docker_tag_is_not_a_pin
    with_workflow(<<~YAML) { |bad| assert_equal ["docker://alpine:3", "docker://ghcr.io/o/i:latest"], bad }
      jobs:
        a:
          steps:
            - uses: docker://alpine:3
            - uses: docker://ghcr.io/o/i:latest
    YAML
  end

  def test_aliases_are_read_not_rejected
    with_workflow(<<~YAML) { |bad| assert_equal ["owner/moving@main"], bad }
      x-step: &step
        uses: owner/moving@main
      jobs:
        a:
          steps:
            - *step
            - uses: owner/ok@#{SHA}
    YAML
  end

  def test_an_unknown_local_prefix_is_not_trusted
    with_workflow(<<~YAML) { |bad| assert_equal ["$/tools/setup"], bad }
      jobs:
        a:
          steps:
            - uses: $/tools/setup
    YAML
  end

  def test_tags_branches_and_reusable_workflows_are_caught
    with_workflow(<<~YAML) { |bad| assert_equal ["org/r/.github/workflows/w.yml@main", "owner/q@v2", "owner/short@0123abc"], bad }
      jobs:
        a:
          uses: org/r/.github/workflows/w.yml@main
        b:
          steps:
            - uses: "owner/q@v2"
            - uses: owner/short@0123abc
    YAML
  end

  def test_a_local_composite_action_anywhere_is_followed
    Dir.mktmpdir do |root|
      FileUtils.mkdir_p(File.join(root, ".github/workflows"))
      FileUtils.mkdir_p(File.join(root, "tools/setup"))
      File.write(File.join(root, ".github/workflows/x.yml"), "jobs:\n  a:\n    steps:\n      - uses: ./tools/setup\n")
      File.write(File.join(root, "tools/setup/action.yml"),
                 "runs:\n  using: composite\n  steps:\n    - uses: owner/nested@v3\n    - uses: ./tools/setup\n")
      assert_equal [["tools/setup/action.yml", "owner/nested@v3"]], unpinned(root)
    end
  end

  def test_the_repository_itself_is_pinned
    assert_equal [], unpinned(File.expand_path("../../..", __dir__))
  end
end

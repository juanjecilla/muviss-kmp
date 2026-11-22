require "minitest/autorun"
require "tmpdir"
require "fileutils"
require_relative "../check-runner-pins"

class CheckRunnerPinsTest < Minitest::Test
  def offenders(yaml)
    Dir.mktmpdir do |root|
      FileUtils.mkdir_p(File.join(root, ".github/workflows"))
      File.write(File.join(root, ".github/workflows/x.yml"), yaml)
      floating_runners(root).map(&:last)
    end
  end

  def test_floating_labels_are_caught
    assert_equal %w[ubuntu-latest macos-latest], offenders(<<~YAML)
      jobs:
        a:
          runs-on: ubuntu-latest
        b:
          runs-on: macos-latest
    YAML
  end

  def test_specific_images_pass
    assert_equal [], offenders("jobs:\n  a:\n    runs-on: ubuntu-24.04\n  b:\n    runs-on: macos-15\n  c:\n    runs-on: windows-2025\n")
  end

  def test_a_matrix_expression_is_followed_to_its_values
    assert_equal ["windows-latest"], offenders(<<~YAML)
      jobs:
        a:
          strategy:
            matrix:
              include:
                - os: ubuntu-24.04
                - os: windows-latest
          runs-on: ${{ matrix.os }}
    YAML
  end

  def offenders_in(files)
    Dir.mktmpdir do |root|
      FileUtils.mkdir_p(File.join(root, ".github/workflows"))
      files.each { |name, yaml| File.write(File.join(root, ".github/workflows", name), yaml) }
      floating_runners(root).map(&:last)
    end
  end

  LEG = "on:\n  workflow_call:\n    inputs:\n      os:\n        type: string\njobs:\n  leg:\n    runs-on: ${{ inputs.os }}\n".freeze

  def test_an_input_is_followed_to_what_every_local_caller_passes
    callers = "jobs:\n  a:\n    uses: ./.github/workflows/leg.yml\n    with:\n      os: macos-15\n  b:\n    uses: ./.github/workflows/leg.yml\n    with:\n      os: windows-2025\n"
    assert_equal [], offenders_in("leg.yml" => LEG, "main.yml" => callers)
  end

  def test_a_caller_passing_a_floating_image_is_caught
    callers = "jobs:\n  a:\n    uses: ./.github/workflows/leg.yml\n    with:\n      os: macos-15\n  b:\n    uses: ./.github/workflows/leg.yml\n    with:\n      os: ubuntu-latest\n"
    assert_equal ["ubuntu-latest"], offenders_in("leg.yml" => LEG, "main.yml" => callers)
  end

  def test_a_caller_passing_an_expression_is_not_trusted
    callers = "jobs:\n  a:\n    uses: ./.github/workflows/leg.yml\n    with:\n      os: ${{ github.event.inputs.os }}\n"
    assert_equal ["${{ github.event.inputs.os }}"], offenders_in("leg.yml" => LEG, "main.yml" => callers)
  end

  def test_an_expression_it_cannot_resolve_is_not_trusted
    assert_equal ["${{ inputs.runner }}"], offenders("jobs:\n  a:\n    runs-on: ${{ inputs.runner }}\n")
  end

  def test_a_size_suffix_does_not_hide_a_floating_label
    assert_equal %w[macos-latest-large macos-latest-xlarge], offenders(<<~YAML)
      jobs:
        a:
          runs-on: macos-latest-large
        b:
          runs-on: macos-latest-xlarge
        c:
          runs-on: macos-15-xlarge
    YAML
  end

  def test_a_runner_group_mapping_is_checked_by_its_labels
    assert_equal ["ubuntu-latest"], offenders(<<~YAML)
      jobs:
        a:
          runs-on:
            group: pinned
            labels: [self-hosted, ubuntu-24.04]
        b:
          runs-on:
            group: floating
            labels: ubuntu-latest
        c:
          runs-on:
            group: only-a-group
    YAML
  end

  def test_an_ordinary_matrix_axis_is_followed_to_its_values
    assert_equal ["macos-latest"], offenders(<<~YAML)
      jobs:
        a:
          strategy:
            matrix:
              os: [ubuntu-24.04, macos-latest]
          runs-on: ${{ matrix.os }}
    YAML
  end

  def test_a_hyphenated_matrix_property_is_followed_to_its_values
    assert_empty offenders(<<~YAML)
      jobs:
        a:
          strategy:
            matrix:
              runner-label: [ubuntu-24.04]
          runs-on: ${{ matrix.runner-label }}
    YAML
  end

  def test_a_label_list_is_checked_entry_by_entry
    assert_equal ["ubuntu-latest"], offenders("jobs:\n  a:\n    runs-on: [self-hosted, ubuntu-latest]\n")
  end
end

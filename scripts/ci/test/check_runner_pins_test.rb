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

  def test_an_expression_it_cannot_resolve_is_not_trusted
    assert_equal ["${{ inputs.runner }}"], offenders("jobs:\n  a:\n    runs-on: ${{ inputs.runner }}\n")
  end

  def test_a_label_list_is_checked_entry_by_entry
    assert_equal ["ubuntu-latest"], offenders("jobs:\n  a:\n    runs-on: [self-hosted, ubuntu-latest]\n")
  end
end

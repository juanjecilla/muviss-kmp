require "minitest/autorun"
require "tmpdir"
require "fileutils"
require_relative "../check-workflow-permissions"

class CheckWorkflowPermissionsTest < Minitest::Test
  def offenders(files)
    Dir.mktmpdir do |root|
      FileUtils.mkdir_p(File.join(root, ".github/workflows"))
      files.each { |name, yaml| File.write(File.join(root, ".github/workflows", name), yaml) }
      without_permissions(root).map { |f| File.basename(f) }
    end
  end

  def test_a_workflow_without_permissions_is_caught
    assert_equal ["bare.yml"], offenders("bare.yml" => "on: push\njobs:\n  a:\n    steps: []\n")
  end

  def test_top_level_permissions_pass
    assert_equal [], offenders("ok.yml" => "on: push\npermissions:\n  contents: read\njobs: {}\n")
  end

  def test_an_empty_permissions_block_is_a_declaration
    assert_equal [], offenders("none.yml" => "on: push\npermissions: {}\njobs: {}\n")
  end

  def test_a_job_level_block_does_not_stand_in_for_the_top_level
    yaml = "on: push\njobs:\n  a:\n    permissions:\n      contents: read\n    steps: []\n"
    assert_equal ["job.yml"], offenders("job.yml" => yaml)
  end

  def test_yaml_and_yml_are_both_read
    assert_equal ["a.yaml", "b.yml"], offenders("a.yaml" => "jobs: {}\n", "b.yml" => "jobs: {}\n")
  end
end

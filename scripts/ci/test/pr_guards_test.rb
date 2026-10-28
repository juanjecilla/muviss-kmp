# pr-guards.yml runs on PRs into release/* and hotfix/* (#522), so every job in it
# has to be a decision about those bases: either it is repo-wide and runs on every
# base, or it names the one base it guards in `if:` and is skipped everywhere else.
# A job added with neither would silently gate release PRs on a develop rule.
#
#   ruby scripts/ci/test/pr_guards_test.rb
require "minitest/autorun"
require "yaml"
require "date"

WORKFLOW = File.expand_path("../../../.github/workflows/pr-guards.yml", __dir__)

# Jobs that run on every PR base. Each one is a repo-wide policy or tooling check.
REPO_WIDE_JOBS = %w[release-tooling].freeze

# Jobs that guard one base. The base is named in `if:`, so they skip elsewhere.
BASE_SCOPED_JOBS = {
  "main-source-guard" => "main",
  "release-notes-guard" => "main",
  "landing-guard" => "develop"
}.freeze

# Check names the rulesets require (scripts/release/owner-setup.sh). Renaming a
# job here silently unrequires it, so the names are asserted, not just the jobs.
REQUIRED_NAMES = {
  "main-source-guard" => "PRs into main come from release/* or hotfix/*",
  "release-notes-guard" => "Release notes are final",
  "landing-guard" => "Features reach the landing",
  "release-tooling" => "Release tooling tests"
}.freeze

class PrGuardsTest < Minitest::Test
  def workflow
    @workflow ||= YAML.safe_load(File.read(WORKFLOW), permitted_classes: [Symbol, Date, Time], aliases: true)
  end

  # YAML 1.1 reads an unquoted `on:` as the boolean key true.
  def triggers
    workflow.fetch("on") { workflow.fetch(true) }
  end

  def jobs
    workflow.fetch("jobs")
  end

  def test_pull_requests_into_release_and_hotfix_branches_are_guarded
    branches = triggers.fetch("pull_request").fetch("branches")
    assert_equal ["main", "develop", "release/**", "hotfix/**"], branches
  end

  def test_every_job_is_either_repo_wide_or_scoped_to_one_base
    jobs.each do |name, job|
      condition = job["if"].to_s
      if REPO_WIDE_JOBS.include?(name)
        refute_match(/base_ref/, condition, "#{name} is repo-wide but has a base_ref condition")
      elsif BASE_SCOPED_JOBS.key?(name)
        assert_equal "github.base_ref == '#{BASE_SCOPED_JOBS.fetch(name)}'", condition,
                     "#{name} must name its base in `if:` so it is skipped on release/* and hotfix/*"
      else
        flunk "#{name} is neither repo-wide nor base-scoped: decide where it runs (#522)"
      end
    end
  end

  def test_base_scoped_jobs_guard_only_main_or_develop
    BASE_SCOPED_JOBS.each do |name, base|
      assert_includes %w[main develop], base, "#{name} guards an unexpected base"
    end
  end

  def test_required_check_names_are_unchanged
    REQUIRED_NAMES.each do |job, name|
      assert_equal name, jobs.fetch(job).fetch("name"), "the #{job} check name is required by a ruleset"
    end
  end
end

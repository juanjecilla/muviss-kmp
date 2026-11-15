# dependabot-worker-locks.yml (#638) regenerates the web worker's kotlin-js-store
# locks for a Dependabot bump and pushes them with the release App's token. The
# split that keeps that safe is the workflow's shape, not its behaviour, so this
# test reads the shape: the job that runs the PR's code holds no secret, and the
# job that holds the token runs no repository code.
#
#   ruby scripts/ci/test/dependabot_worker_locks_test.rb
require "minitest/autorun"
require "yaml"
require "date"

WORKFLOW = File.expand_path("../../../.github/workflows/dependabot-worker-locks.yml", __dir__)
GATE_ACTOR = "github.actor == 'dependabot[bot]'"
# The actor alone is whoever triggered this run; the author is who opened the PR.
GATE_AUTHOR = "github.event.pull_request.user.login == 'dependabot[bot]'"
GATE_BRANCH = "startsWith(github.head_ref, 'dependabot/npm_and_yarn/')"
GATE_REPO = "github.event.pull_request.head.repo.full_name == github.repository"

class DependabotWorkerLocksTest < Minitest::Test
  def workflow
    @workflow ||= YAML.safe_load(File.read(WORKFLOW), permitted_classes: [Symbol, Date, Time], aliases: true)
  end

  def triggers
    workflow.fetch("on") { workflow.fetch(true) }
  end

  def jobs
    workflow.fetch("jobs")
  end

  def regenerate
    jobs.fetch("regenerate")
  end

  def push
    jobs.fetch("push")
  end

  def run_text(job)
    job.fetch("steps").map { |step| step["run"].to_s }.join("\n")
  end

  def test_triggers_on_pull_requests_into_develop_for_the_worker_only
    pr = triggers.fetch("pull_request")
    assert_equal ["develop"], pr.fetch("branches")
    assert_equal ["core/database/src/webWorker/package.json"], pr.fetch("paths")
    refute triggers.key?("pull_request_target"), "a pull_request_target trigger would hand the App token to fork code"
  end

  def test_both_jobs_are_gated_on_dependabot_author_branch_and_same_repo
    [regenerate, push].each do |job|
      cond = job.fetch("if")
      [GATE_ACTOR, GATE_AUTHOR, GATE_BRANCH, GATE_REPO].each { |g| assert_includes cond, g }
    end
  end

  def test_the_push_waits_for_a_successful_regeneration
    assert_equal ["regenerate"], Array(push.fetch("needs"))
    assert_includes push.fetch("if"), "needs.regenerate.result == 'success'"
  end

  def test_the_regenerating_job_holds_no_secrets_and_only_reads
    assert_equal({ "contents" => "read" }, regenerate.fetch("permissions"))
    refute_match(/secrets\./, YAML.dump(regenerate), "the job that runs PR code must not reference secrets")
    refute_match(/create-github-app-token/, YAML.dump(regenerate))
  end

  def test_only_the_push_job_mints_the_app_token_and_reads_the_app_secrets
    assert_match(/secrets\.RELEASE_APP_ID/, YAML.dump(push))
    assert_match(/secrets\.RELEASE_APP_PRIVATE_KEY/, YAML.dump(push))
    assert(push.fetch("steps").any? { |s| s["uses"].to_s.start_with?("actions/create-github-app-token@") })
  end

  def test_the_app_token_is_scoped_to_this_repository_and_contents_write
    mint = push.fetch("steps").find { |s| s["uses"].to_s.start_with?("actions/create-github-app-token@") }
    inputs = mint.fetch("with")
    assert_equal "${{ github.event.repository.name }}", inputs.fetch("repositories")
    assert_equal "write", inputs.fetch("permission-contents")
    assert_equal ["permission-contents"], inputs.keys.grep(/\Apermission-/), "the token gets no other permission"
  end

  def test_the_push_job_runs_no_repository_code
    text = run_text(push)
    refute_match(/gradlew|\bnpm\b|\bnode\b|scripts\//, text)
    # Its only checkout is the PR head, read as data for git to commit onto.
    checkout = push.fetch("steps").find { |s| s["uses"].to_s.start_with?("actions/checkout@") }
    refute_nil checkout
    refute checkout.fetch("with", {}).key?("submodules")
  end

  def test_the_push_accepts_only_the_two_lock_paths
    text = run_text(push)
    assert_includes text, "kotlin-js-store/wasm/yarn.lock kotlin-js-store/yarn.lock"
    assert_includes text, "exactly the two lock files"
    assert_includes text, "find . -mindepth 1 ! -type f ! -type d"
  end

  def test_the_push_is_not_forced_and_pushes_the_branch_it_came_from
    text = run_text(push)
    refute_match(/--force|-f /, text)
    assert_includes text, 'git push origin "HEAD:refs/heads/${HEAD_REF}"'
  end

  def test_regeneration_runs_the_two_yarn_lock_tasks
    assert_includes run_text(regenerate), "./gradlew kotlinUpgradeYarnLock kotlinWasmUpgradeYarnLock"
  end

  def test_the_artifact_is_handed_from_one_job_to_the_other_by_name
    upload = regenerate.fetch("steps").find { |s| s["uses"].to_s.start_with?("actions/upload-artifact@") }
    download = push.fetch("steps").find { |s| s["uses"].to_s.start_with?("actions/download-artifact@") }
    assert_equal "worker-locks", upload.fetch("with").fetch("name")
    assert_equal "worker-locks", download.fetch("with").fetch("name")
  end

  def test_the_workflow_never_cancels_a_push_midway
    assert_equal false, workflow.fetch("concurrency").fetch("cancel-in-progress")
  end
end

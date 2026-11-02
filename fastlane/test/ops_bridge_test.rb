require_relative "ops_bridge_test_helper"

# The bridge itself: markers, dedup, the sink, the two runs. Connectors have their own files.
class OpsBridgeTest < Minitest::Test
  def item(id, title: "t")
    OpsBridge::Item.new(id, title, "body", ["from-stub"], nil)
  end

  # --- markers and titles ------------------------------------------------------

  def test_a_marker_round_trips_for_its_own_kind_only
    marker = OpsBridge.marker("sentry", "MUVISS-7")
    assert_equal "<!-- sentry:MUVISS-7 -->", marker
    assert_equal "MUVISS-7", OpsBridge.marker_in("sentry", "text\n#{marker}")
    assert_nil OpsBridge.marker_in("play-review", marker), "another connector's marker is not ours"
    assert_nil OpsBridge.marker_in("sentry", "no marker here")
    assert_nil OpsBridge.marker_in("sentry", nil)
  end

  def test_ids_with_colons_survive_a_marker
    id = "gp:AOqpTOEx-ample_1"
    assert_equal id, OpsBridge.marker_in("play-review", OpsBridge.marker("play-review", id))
  end

  def test_a_very_long_title_is_cut
    assert_equal OpsBridge::TITLE_LIMIT, OpsBridge.title("[X]", "y" * 500).length
  end

  def test_dedup_drops_what_already_has_an_issue
    items = OpsBridge.new_items([item("a"), item("b")], ["a"])
    assert_equal ["b"], items.map(&:id)
  end

  # --- the sink ----------------------------------------------------------------

  def github(responses)
    http = FakeHttp.new(responses)
    [OpsBridge::GithubSink.new(repo: "o/r", token: "ghs_secret", http: http), http]
  end

  def test_known_ids_come_from_the_issue_bodies_across_pages_and_skip_pull_requests
    page1 = [{ "body" => OpsBridge.marker("stub", "1") }, { "body" => OpsBridge.marker("stub", "2"), "pull_request" => {} }]
    page2 = [{ "body" => OpsBridge.marker("stub", "3") }, { "body" => "hand-written, no marker" }, { "body" => OpsBridge.marker("other", "4") }]
    sink, = github(
      [:get, "https://api.github.com/repos/o/r/issues?page=2"] => [200, page2, {}],
      # state=all: a closed issue still counts, or the next sync would open its item again.
      [:get, "https://api.github.com/repos/o/r/issues?labels=from-stub&state=all"] => [200, page1, { "link" => '<https://api.github.com/repos/o/r/issues?page=2>; rel="next"' }],
    )
    assert_equal %w[1 3], sink.known_ids(StubConnector.new([]))
  end

  def test_an_existing_label_is_fine_and_any_other_failure_is_not
    sink, = github([:post, "https://api.github.com/repos/o/r/labels"] => [422, {}])
    sink.ensure_labels(%w[crash])

    failing, = github([:post, "https://api.github.com/repos/o/r/labels"] => [403, {}])
    assert_raises(OpsBridge::ApiError) { failing.ensure_labels(%w[crash]) }
  end

  def test_creating_sends_the_title_body_and_labels
    sink, http = github([:post, "https://api.github.com/repos/o/r/issues"] => [201, { "html_url" => "https://github.com/o/r/issues/9" }])
    assert_equal "https://github.com/o/r/issues/9", sink.create(item("a", title: "T"))
    sent = http.requests.last[:body]
    assert_equal({ title: "T", body: "body", labels: ["from-stub"] }, sent)
  end

  def test_a_failed_create_raises_without_the_token_in_the_message
    sink, = github([:post, "https://api.github.com/repos/o/r/issues"] => [500, {}])
    error = assert_raises(OpsBridge::ApiError) { sink.create(item("a")) }
    refute_includes error.message, "ghs_secret"
  end

  def test_the_sink_needs_a_repo_and_a_token
    assert_raises(OpsBridge::ApiError) { OpsBridge::GithubSink.new(repo: "", token: "t") }
    assert_raises(OpsBridge::ApiError) { OpsBridge::GithubSink.new(repo: "o/r", token: nil) }
  end

  # --- sync --------------------------------------------------------------------

  def test_a_new_item_opens_exactly_one_issue_and_a_second_run_opens_none
    connector = StubConnector.new([item("a")])
    sink = StubSink.new([])
    OpsBridge.sync(connector: connector, sink: sink)
    assert_equal ["a"], sink.created
    assert_equal [["from-stub"]], sink.labelled

    again = StubSink.new(["a"])
    OpsBridge.sync(connector: connector, sink: again)
    assert_empty again.created, "re-running must be a no-op"
    assert_empty again.labelled, "and must not even touch the labels"
  end

  def test_a_dry_run_reports_and_writes_nothing
    sink = StubSink.new([])
    lines = []
    OpsBridge.sync(connector: StubConnector.new([item("a", title: "T")]), sink: sink, dry_run: true, log: ->(line) { lines << line })
    assert_empty sink.created
    assert_empty sink.labelled
    assert(lines.any? { |line| line.include?("would open: T") })
  end

  # --- reopen on regression (#327) ---------------------------------------------

  CLOSED_AT = Time.utc(2026, 10, 1, 12)

  def closed(id, number: 7, reason: "completed", at: CLOSED_AT)
    OpsBridge::KnownIssue.new(id, number, "closed", reason, at)
  end

  def came_back(id, at: CLOSED_AT + 3600)
    OpsBridge::Item.new(id, "T #{id}", "body", ["from-stub"], nil, at, "it is back")
  end

  def test_an_item_back_after_its_issue_was_closed_as_completed_reopens_it_with_the_note
    sink = StubSink.new([closed("a")])
    opened = OpsBridge.sync(connector: StubConnector.new([came_back("a")]), sink: sink)
    assert_equal [[7, "it is back"]], sink.reopened
    assert_empty opened, "a regression reopens; it never opens a second issue"
    assert_empty sink.created
  end

  def test_a_close_after_the_source_last_saw_it_is_not_reopened
    sink = StubSink.new([closed("a", at: CLOSED_AT + 7200)])
    OpsBridge.sync(connector: StubConnector.new([came_back("a")]), sink: sink)
    assert_empty sink.reopened, "closed with the regression already known: leave it closed"
  end

  def test_a_close_as_not_planned_or_duplicate_is_not_reopened
    %w[not_planned duplicate].each do |reason|
      sink = StubSink.new([closed("a", reason: reason)])
      OpsBridge.sync(connector: StubConnector.new([came_back("a")]), sink: sink)
      assert_empty sink.reopened, reason
    end
  end

  def test_an_open_issue_or_an_item_that_is_not_a_regression_is_left_alone
    open_issue = StubSink.new([OpsBridge::KnownIssue.new("a", 7, "open", nil, nil)])
    OpsBridge.sync(connector: StubConnector.new([came_back("a")]), sink: open_issue)
    assert_empty open_issue.reopened

    plain = StubSink.new([closed("a")])
    OpsBridge.sync(connector: StubConnector.new([item("a")]), sink: plain)
    assert_empty plain.reopened, "no reopen_after: the source did not say it came back"
  end

  def test_a_reopened_issue_is_not_reopened_again
    sink = StubSink.new([OpsBridge::KnownIssue.new("a", 7, "open", "reopened", nil)])
    OpsBridge.sync(connector: StubConnector.new([came_back("a")]), sink: sink)
    assert_empty sink.reopened
  end

  def test_an_ineligible_copy_of_the_marker_does_not_hide_an_eligible_issue
    sink = StubSink.new([closed("a", number: 7), closed("a", number: 9, reason: "duplicate")])
    OpsBridge.sync(connector: StubConnector.new([came_back("a")]), sink: sink)
    assert_equal [[7, "it is back"]], sink.reopened
    assert_empty sink.created
  end

  def test_an_open_copy_of_the_marker_means_nothing_is_reopened
    open_copy = OpsBridge::KnownIssue.new("a", 9, "open", nil, nil)
    sink = StubSink.new([closed("a", number: 7), open_copy])
    OpsBridge.sync(connector: StubConnector.new([came_back("a")]), sink: sink)
    assert_empty sink.reopened, "an open issue already tracks the item"
  end

  def test_a_dry_run_says_it_would_reopen_and_does_not
    sink = StubSink.new([closed("a")])
    lines = []
    OpsBridge.sync(connector: StubConnector.new([came_back("a")]), sink: sink, dry_run: true, log: ->(line) { lines << line })
    assert_empty sink.reopened
    assert(lines.any? { |line| line.include?("would reopen #7") })
  end

  def test_the_sink_reads_state_and_close_time_and_reopens_with_a_comment_first
    issues = [{ "number" => 7, "state" => "closed", "state_reason" => "completed", "closed_at" => "2026-10-01T12:00:00Z",
                "body" => OpsBridge.marker("stub", "a") }]
    sink, http = github(
      [:get, "https://api.github.com/repos/o/r/issues?labels=from-stub"] => [200, issues, {}],
      [:post, "https://api.github.com/repos/o/r/issues/7/comments"] => [201, {}],
      [:patch, "https://api.github.com/repos/o/r/issues/7"] => [200, {}],
    )
    assert_equal [closed("a")], sink.known_issues(StubConnector.new([]))

    sink.reopen(7, "it is back")
    comment, patch = http.requests.last(2)
    assert_equal({ body: "it is back" }, comment[:body])
    assert_equal :patch, patch[:method]
    assert_equal({ state: "open", state_reason: "reopened" }, patch[:body])
  end

  def test_a_failed_reopen_raises_without_the_token
    sink, = github([:post, "https://api.github.com/repos/o/r/issues/7/comments"] => [403, {}])
    error = assert_raises(OpsBridge::ApiError) { sink.reopen(7, "n") }
    refute_includes error.message, "ghs_secret"
  end

  # --- resolve -----------------------------------------------------------------

  def test_closing_one_of_ours_resolves_its_source
    connector = StubConnector.new([])
    id = OpsBridge.resolve_closed(connector: connector, labels: %w[from-stub], body: "x\n#{OpsBridge.marker('stub', 'a')}")
    assert_equal "a", id
    assert_equal ["a"], connector.resolved
  end

  def test_closing_any_other_issue_does_nothing
    connector = StubConnector.new([])
    assert_nil OpsBridge.resolve_closed(connector: connector, labels: %w[bug], body: OpsBridge.marker("stub", "a"))
    assert_empty connector.resolved
  end

  def test_a_marker_edited_away_does_nothing
    connector = StubConnector.new([])
    assert_nil OpsBridge.resolve_closed(connector: connector, labels: %w[from-stub], body: "someone rewrote this")
    assert_empty connector.resolved
  end

  def test_a_connector_with_no_reverse_mapping_does_nothing
    connector = StubConnector.new([], resolvable: false)
    assert_nil OpsBridge.resolve_closed(connector: connector, labels: %w[from-stub], body: OpsBridge.marker("stub", "a"))
  end

  def test_a_close_as_not_planned_or_duplicate_resolves_nothing
    connector = StubConnector.new([])
    %w[not_planned duplicate].each do |reason|
      assert_nil OpsBridge.resolve_closed(connector: connector, labels: %w[from-stub], body: OpsBridge.marker("stub", "a"),
                                          state_reason: reason)
    end
    assert_empty connector.resolved
  end

  def test_a_close_as_completed_resolves
    connector = StubConnector.new([])
    OpsBridge.resolve_closed(connector: connector, labels: %w[from-stub], body: OpsBridge.marker("stub", "a"), state_reason: "completed")
    assert_equal ["a"], connector.resolved
  end

  def test_a_dry_run_close_resolves_nothing
    connector = StubConnector.new([])
    OpsBridge.resolve_closed(connector: connector, labels: %w[from-stub], body: OpsBridge.marker("stub", "a"), dry_run: true)
    assert_empty connector.resolved
  end

  # --- the entry point -----------------------------------------------------------

  def test_main_rejects_an_unknown_connector_and_a_bad_command
    out = StringIO.new
    assert_equal 1, OpsBridge.main(%w[sync nope], {}, out: out)
    assert_includes out.string, "unknown connector"

    out = StringIO.new
    assert_equal 1, OpsBridge.main(%w[frobnicate sentry], {}, out: out)
    assert_includes out.string, "usage"
  end

  def test_main_reports_a_missing_credential_and_fails
    out = StringIO.new
    assert_equal 1, OpsBridge.main(%w[sync sentry], { "SENTRY_ORG" => "codingpit" }, out: out)
    assert_includes out.string, "SENTRY_AUTH_TOKEN"

    out = StringIO.new
    assert_equal 1, OpsBridge.main(%w[sync play-reviews], {}, out: out)
    assert_includes out.string, "PLAY_SERVICE_ACCOUNT_JSON"
  end
end

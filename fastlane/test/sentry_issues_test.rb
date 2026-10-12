require "json"
require "stringio"
require "minitest/autorun"
require_relative "../sentry_issues"

class SentryIssuesTest < Minitest::Test
  ISSUE = {
    "shortId" => "MUVISS-7",
    "title" => "java.lang.IllegalStateException: boom",
    "culprit" => "com.codingpit.muviss.Foo in bar",
    "permalink" => "https://sentry.io/organizations/codingpit/issues/123/",
    "firstRelease" => { "version" => "com.codingpit.muviss@1.1.0+160" },
    "lastRelease" => { "version" => "com.codingpit.muviss@1.1.1+170" },
    "count" => "42",
    "userCount" => 5,
  }.freeze

  # A scripted stand-in for SentryIssues::Http: answers from a table, records every request.
  class FakeHttp
    attr_reader :requests

    def initialize(responses)
      @responses = responses
      @requests = []
    end

    def request(method, url, headers: {}, body: nil)
      @requests << { method: method, url: url, headers: headers, body: body }
      key = @responses.keys.find { |k| k[0] == method && url.start_with?(k[1]) }
      raise "unscripted request: #{method} #{url}" unless key

      status, payload, response_headers = @responses.fetch(key)
      [status, payload.is_a?(String) ? payload : JSON.generate(payload), response_headers || {}]
    end
  end

  # --- query ---------------------------------------------------------------

  def test_the_query_asks_for_unresolved_unhandled_production_crashes
    query = SentryIssues.query
    assert_includes query, "is:unresolved"
    assert_includes query, "error.unhandled:true"
    assert_includes query, "environment:production"
  end

  def test_a_project_narrows_the_query
    assert_includes SentryIssues.query(project: "muviss"), "project:muviss"
    refute_includes SentryIssues.query(project: ""), "project:"
  end

  # --- item and body ---------------------------------------------------------

  def test_the_item_is_titled_with_the_short_id_and_labelled_for_triage
    item = SentryIssues.to_item(ISSUE)
    assert_equal "[MUVISS-7] java.lang.IllegalStateException: boom", item.title
    assert_equal %w[crash from-sentry], item.labels
    assert_equal "MUVISS-7", item.id
    assert_equal ISSUE["permalink"], item.link
  end

  def test_a_very_long_title_is_cut
    long = ISSUE.merge("title" => "x" * 500)
    assert_equal SentryIssues::TITLE_LIMIT, SentryIssues.to_item(long).title.length
  end

  def test_the_body_carries_the_facts_and_the_marker_and_nothing_raw
    body = SentryIssues.render_body(ISSUE)
    assert_includes body, "[MUVISS-7](https://sentry.io/organizations/codingpit/issues/123/)"
    assert_includes body, "`com.codingpit.muviss.Foo in bar`"
    assert_includes body, "`com.codingpit.muviss@1.1.0+160`"
    assert_includes body, "`com.codingpit.muviss@1.1.1+170`"
    assert_includes body, "**Events:** 42"
    assert_includes body, "**Users affected:** 5"
    assert_includes body, "<!-- sentry:MUVISS-7 -->"
    refute_includes body.downcase, "stacktrace"
  end

  def test_a_missing_field_is_left_out_rather_than_printed_blank
    body = SentryIssues.render_body("shortId" => "MUVISS-9", "title" => "t")
    refute_includes body, "Culprit"
    refute_includes body, "First seen in"
    assert_includes body, "<!-- sentry:MUVISS-9 -->"
  end

  def test_the_marker_round_trips
    assert_equal "MUVISS-7", SentryIssues.marker_in(SentryIssues.render_body(ISSUE))
    assert_nil SentryIssues.marker_in("no marker here")
    assert_nil SentryIssues.marker_in(nil)
  end

  # --- dedup -----------------------------------------------------------------

  def test_an_issue_that_already_has_a_github_issue_is_not_opened_again
    items = SentryIssues.new_items([ISSUE, ISSUE.merge("shortId" => "MUVISS-8")], ["MUVISS-7"])
    assert_equal ["MUVISS-8"], items.map(&:id)
  end

  # --- the sink ----------------------------------------------------------------

  def github(responses)
    http = FakeHttp.new(responses)
    [SentryIssues::GithubSink.new(repo: "o/r", token: "ghs_secret", http: http), http]
  end

  def test_known_ids_come_from_the_issue_bodies_across_pages_and_skip_pull_requests
    page1 = [{ "body" => SentryIssues.marker("MUVISS-1") }, { "body" => "x #{SentryIssues.marker('MUVISS-2')}", "pull_request" => {} }]
    page2 = [{ "body" => SentryIssues.marker("MUVISS-3") }, { "body" => "hand-written, no marker" }]
    responses = {
      [:get, "https://api.github.com/repos/o/r/issues?page=2"] => [200, page2, {}],
      [:get, "https://api.github.com/repos/o/r/issues?labels=from-sentry"] => [200, page1, { "link" => '<https://api.github.com/repos/o/r/issues?page=2>; rel="next"' }],
    }
    http = FakeHttp.new(responses)
    sink = SentryIssues::GithubSink.new(repo: "o/r", token: "t", http: http)
    assert_equal %w[MUVISS-1 MUVISS-3], sink.known_ids
  end

  def test_an_existing_label_is_fine_and_any_other_failure_is_not
    sink, = github([:post, "https://api.github.com/repos/o/r/labels"] => [422, {}])
    sink.ensure_labels(%w[crash])

    failing, = github([:post, "https://api.github.com/repos/o/r/labels"] => [403, {}])
    assert_raises(SentryIssues::ApiError) { failing.ensure_labels(%w[crash]) }
  end

  def test_creating_sends_the_title_body_and_labels
    sink, http = github([:post, "https://api.github.com/repos/o/r/issues"] => [201, { "html_url" => "https://github.com/o/r/issues/9" }])
    url = sink.create(SentryIssues.to_item(ISSUE))
    assert_equal "https://github.com/o/r/issues/9", url
    sent = http.requests.last[:body]
    assert_equal %w[crash from-sentry], sent[:labels]
    assert_includes sent[:body], "<!-- sentry:MUVISS-7 -->"
  end

  def test_a_failed_create_raises_without_the_token_in_the_message
    sink, = github([:post, "https://api.github.com/repos/o/r/issues"] => [500, {}])
    error = assert_raises(SentryIssues::ApiError) { sink.create(SentryIssues.to_item(ISSUE)) }
    refute_includes error.message, "ghs_secret"
  end

  # --- the source --------------------------------------------------------------

  def sentry(responses)
    http = FakeHttp.new(responses)
    [SentryIssues::SentrySource.new(org: "codingpit", token: "sntrys_secret", project: "muviss", http: http), http]
  end

  def test_the_source_needs_a_token_and_an_org
    assert_raises(SentryIssues::ApiError) { SentryIssues::SentrySource.new(org: "", token: "t") }
    assert_raises(SentryIssues::ApiError) { SentryIssues::SentrySource.new(org: "o", token: nil) }
  end

  def test_the_source_asks_for_the_query_and_returns_the_list
    source, http = sentry([:get, "https://sentry.io/api/0/organizations/codingpit/issues/"] => [200, [ISSUE]])
    assert_equal [ISSUE], source.issues
    request = http.requests.last
    assert_includes request[:url], "error.unhandled%3Atrue"
    assert_equal "Bearer sntrys_secret", request[:headers]["Authorization"]
  end

  def test_something_that_is_not_a_list_is_an_error
    source, = sentry([:get, "https://sentry.io/api/0/organizations/codingpit/issues/"] => [200, { "detail" => "nope" }])
    assert_raises(SentryIssues::ApiError) { source.issues }
  end

  def test_an_http_error_is_an_error_without_the_token
    source, = sentry([:get, "https://sentry.io/api/0/organizations/codingpit/issues/"] => [403, {}])
    error = assert_raises(SentryIssues::ApiError) { source.issues }
    assert_includes error.message, "403"
    refute_includes error.message, "sntrys_secret"
  end

  def test_resolving_looks_up_the_group_and_resolves_it_in_the_next_release
    source, http = sentry(
      [:get, "https://sentry.io/api/0/organizations/codingpit/shortids/MUVISS-7/"] => [200, { "groupId" => "123" }],
      [:put, "https://sentry.io/api/0/organizations/codingpit/issues/"] => [200, {}],
    )
    source.resolve_in_next_release("MUVISS-7")
    put = http.requests.last
    assert_equal :put, put[:method]
    assert_includes put[:url], "?id=123"
    assert_equal({ status: "resolved", statusDetails: { inNextRelease: true } }, put[:body])
  end

  def test_a_refused_resolve_is_an_error
    source, = sentry(
      [:get, "https://sentry.io/api/0/organizations/codingpit/shortids/MUVISS-7/"] => [200, { "groupId" => "123" }],
      [:put, "https://sentry.io/api/0/organizations/codingpit/issues/"] => [403, {}],
    )
    assert_raises(SentryIssues::ApiError) { source.resolve_in_next_release("MUVISS-7") }
  end

  # --- the two runs ------------------------------------------------------------

  class StubSource
    attr_reader :resolved

    def initialize(issues)
      @issues = issues
      @resolved = []
    end

    def issues
      @issues
    end

    def resolve_in_next_release(short_id)
      @resolved << short_id
    end
  end

  class StubSink
    attr_reader :created, :labelled

    def initialize(known)
      @known = known
      @created = []
      @labelled = []
    end

    def known_ids
      @known
    end

    def ensure_labels(labels)
      @labelled << labels
    end

    def create(item)
      @created << item.id
      "https://github.com/o/r/issues/#{@created.size}"
    end
  end

  def test_one_new_crash_opens_exactly_one_issue_and_a_second_run_opens_none
    source = StubSource.new([ISSUE])
    sink = StubSink.new([])
    SentryIssues.sync(source: source, sink: sink)
    assert_equal ["MUVISS-7"], sink.created

    again = StubSink.new(["MUVISS-7"])
    SentryIssues.sync(source: source, sink: again)
    assert_empty again.created, "re-running must be a no-op"
    assert_empty again.labelled, "and must not even touch the labels"
  end

  def test_a_dry_run_reports_and_writes_nothing
    sink = StubSink.new([])
    lines = []
    SentryIssues.sync(source: StubSource.new([ISSUE]), sink: sink, dry_run: true, log: ->(line) { lines << line })
    assert_empty sink.created
    assert_empty sink.labelled
    assert(lines.any? { |line| line.include?("would open: [MUVISS-7]") })
  end

  def test_closing_a_from_sentry_issue_resolves_the_sentry_issue
    source = StubSource.new([])
    id = SentryIssues.resolve_closed(labels: %w[crash from-sentry], body: SentryIssues.render_body(ISSUE), source: source)
    assert_equal "MUVISS-7", id
    assert_equal ["MUVISS-7"], source.resolved
  end

  def test_closing_any_other_issue_does_nothing
    source = StubSource.new([])
    assert_nil SentryIssues.resolve_closed(labels: %w[bug], body: SentryIssues.render_body(ISSUE), source: source)
    assert_empty source.resolved
  end

  def test_a_from_sentry_issue_with_its_marker_edited_away_does_nothing
    source = StubSource.new([])
    assert_nil SentryIssues.resolve_closed(labels: %w[from-sentry], body: "someone rewrote this", source: source)
    assert_empty source.resolved
  end

  def test_a_close_as_not_planned_or_duplicate_resolves_nothing
    source = StubSource.new([])
    %w[not_planned duplicate].each do |reason|
      assert_nil SentryIssues.resolve_closed(labels: %w[from-sentry], body: SentryIssues.render_body(ISSUE),
                                             source: source, state_reason: reason)
    end
    assert_empty source.resolved
  end

  def test_a_close_as_completed_resolves
    source = StubSource.new([])
    SentryIssues.resolve_closed(labels: %w[from-sentry], body: SentryIssues.render_body(ISSUE), source: source,
                                state_reason: "completed")
    assert_equal ["MUVISS-7"], source.resolved
  end

  def test_a_dry_run_close_resolves_nothing
    source = StubSource.new([])
    SentryIssues.resolve_closed(labels: %w[from-sentry], body: SentryIssues.render_body(ISSUE), source: source, dry_run: true)
    assert_empty source.resolved
  end

  # --- the entry point ---------------------------------------------------------

  def test_main_reports_a_missing_token_and_fails
    out = StringIO.new
    status = SentryIssues.main(["sync"], { "SENTRY_ORG" => "codingpit" }, out: out)
    assert_equal 1, status
    assert_includes out.string, "SENTRY_AUTH_TOKEN"
  end

  def test_main_rejects_an_unknown_command
    out = StringIO.new
    status = SentryIssues.main(["nope"], { "SENTRY_ORG" => "o", "SENTRY_AUTH_TOKEN" => "t" }, out: out)
    assert_equal 1, status
    assert_includes out.string, "usage"
  end
end

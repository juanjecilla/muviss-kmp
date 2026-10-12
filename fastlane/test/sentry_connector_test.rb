require_relative "ops_bridge_test_helper"

class SentryConnectorTest < Minitest::Test
  Sentry = OpsBridge::Connectors::Sentry

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

  def sentry(responses)
    http = FakeHttp.new(responses)
    [Sentry.new(org: "codingpit", token: "sntrys_secret", project: "muviss", http: http), http]
  end

  def test_the_query_asks_for_unresolved_unhandled_production_crashes
    query = Sentry.query
    assert_includes query, "is:unresolved"
    assert_includes query, "error.unhandled:true"
    assert_includes query, "environment:production"
  end

  def test_a_project_narrows_the_query
    assert_includes Sentry.query(project: "muviss"), "project:muviss"
    refute_includes Sentry.query(project: ""), "project:"
  end

  def test_the_item_is_titled_with_the_short_id_and_labelled_for_triage
    item = Sentry.to_item(ISSUE)
    assert_equal "[MUVISS-7] java.lang.IllegalStateException: boom", item.title
    assert_equal %w[crash from-sentry], item.labels
    assert_equal "MUVISS-7", item.id
    assert_equal ISSUE["permalink"], item.link
  end

  def test_the_body_carries_the_facts_and_the_marker_and_nothing_raw
    body = Sentry.render_body(ISSUE)
    assert_includes body, "[MUVISS-7](https://sentry.io/organizations/codingpit/issues/123/)"
    assert_includes body, "**Title:** `java.lang.IllegalStateException: boom`"
    assert_includes body, "`com.codingpit.muviss.Foo in bar`"
    assert_includes body, "`com.codingpit.muviss@1.1.0+160`"
    assert_includes body, "`com.codingpit.muviss@1.1.1+170`"
    assert_includes body, "**Events:** 42"
    assert_includes body, "**Users affected:** 5"
    assert_includes body, "<!-- sentry:MUVISS-7 -->"
    refute_includes body.downcase, "stacktrace"
  end

  def test_a_missing_field_is_left_out_rather_than_printed_blank
    body = Sentry.render_body("shortId" => "MUVISS-9", "title" => "t")
    refute_includes body, "Culprit"
    refute_includes body, "First seen in"
    assert_includes body, "<!-- sentry:MUVISS-9 -->"
  end

  def test_the_connector_needs_a_token_and_an_org
    assert_raises(OpsBridge::ApiError) { Sentry.new(org: "", token: "t") }
    assert_raises(OpsBridge::ApiError) { Sentry.new(org: "o", token: nil) }
  end

  def test_items_asks_for_the_query_and_maps_the_list
    source, http = sentry([:get, "https://sentry.io/api/0/organizations/codingpit/issues/"] => [200, [ISSUE]])
    assert_equal ["MUVISS-7"], source.items.map(&:id)
    request = http.requests.last
    assert_includes request[:url], "error.unhandled%3Atrue"
    assert_equal "Bearer sntrys_secret", request[:headers]["Authorization"]
  end

  def test_something_that_is_not_a_list_is_an_error
    source, = sentry([:get, "https://sentry.io/api/0/organizations/codingpit/issues/"] => [200, { "detail" => "nope" }])
    assert_raises(OpsBridge::ApiError) { source.items }
  end

  def test_an_http_error_is_an_error_without_the_token
    source, = sentry([:get, "https://sentry.io/api/0/organizations/codingpit/issues/"] => [403, {}])
    error = assert_raises(OpsBridge::ApiError) { source.items }
    assert_includes error.message, "403"
    refute_includes error.message, "sntrys_secret"
  end

  def test_resolving_looks_up_the_group_and_resolves_it_in_the_next_release
    source, http = sentry(
      [:get, "https://sentry.io/api/0/organizations/codingpit/shortids/MUVISS-7/"] => [200, { "groupId" => "123" }],
      [:put, "https://sentry.io/api/0/organizations/codingpit/issues/"] => [200, {}],
    )
    source.resolve("MUVISS-7")
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
    assert_raises(OpsBridge::ApiError) { source.resolve("MUVISS-7") }
  end

  def test_the_whole_run_through_the_bridge_is_idempotent
    source, = sentry([:get, "https://sentry.io/api/0/organizations/codingpit/issues/"] => [200, [ISSUE]])
    first = StubSink.new([])
    OpsBridge.sync(connector: source, sink: first)
    assert_equal ["MUVISS-7"], first.created

    second = StubSink.new(["MUVISS-7"])
    OpsBridge.sync(connector: source, sink: second)
    assert_empty second.created
  end
end

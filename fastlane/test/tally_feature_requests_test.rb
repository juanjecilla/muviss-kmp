require_relative "ops_bridge_test_helper"

class TallyFeatureRequestsTest < Minitest::Test
  Tally = OpsBridge::Connectors::TallyFeatureRequests

  QUESTIONS = [
    { "id" => "q-request", "type" => "TEXTAREA", "title" => "What should Muviss do?" },
    { "id" => "q-why", "type" => "TEXTAREA", "title" => "Why would it help you? (optional)" },
    { "id" => "q-email", "type" => "INPUT_EMAIL", "title" => "Email" },
    { "id" => "q-hidden", "type" => "HIDDEN_FIELDS", "title" => nil },
  ].freeze

  def submission(id: "sub1", request: "Let me tag shows with my own labels", why: "To find them later", email: "jane@example.com",
                 context: { "version" => "1.2.0", "platform" => "android", "os" => "15", "locale" => "es-ES" },
                 completed: true, at: "2026-10-19T12:38:53.000Z")
    responses = [{ "questionId" => "q-request", "answer" => request }]
    responses << { "questionId" => "q-why", "answer" => why } if why
    responses << { "questionId" => "q-email", "answer" => email } if email
    responses << { "questionId" => "q-hidden", "answer" => context } if context
    { "id" => id, "isCompleted" => completed, "submittedAt" => at, "responses" => responses }
  end

  def item(**kwargs)
    Tally.to_item(submission(**kwargs), QUESTIONS)
  end

  # --- which submissions become items -----------------------------------------------

  def test_a_completed_submission_becomes_an_item
    result = item
    assert_equal "sub1", result.id
    assert_equal ["from-feature-request", "user-feedback"], result.labels
    assert_equal "[Feature request] Let me tag shows with my own labels", result.title
  end

  def test_an_empty_or_unfinished_submission_is_skipped
    assert_nil item(request: "   ")
    assert_nil item(request: "")
    assert_nil item(completed: false)
  end

  def test_the_title_is_the_first_line_only_and_bounded
    assert_equal "[Feature request] first", item(request: "first\nsecond line").title
    assert_operator item(request: "x" * 500).title.length, :<=, OpsBridge::TITLE_LIMIT
  end

  # --- the body ---------------------------------------------------------------------

  def test_the_body_has_the_context_the_text_and_the_marker
    body = item.body
    assert_includes body, "**version:** `1.2.0`"
    assert_includes body, "**platform:** `android`"
    assert_includes body, "**os:** `15`"
    assert_includes body, "**locale:** `es-ES`"
    assert_includes body, "**Submitted:** 2026-10-19"
    assert_includes body, "Let me tag shows with my own labels"
    assert_includes body, "To find them later"
    assert_equal "sub1", OpsBridge.marker_in("tally-feature-request", body)
  end

  def test_the_email_is_never_copied_only_whether_one_was_left
    with_email = item(email: "jane@example.com").body
    refute_includes with_email, "jane@example.com"
    assert_includes with_email, "**Can be replied to:** yes"
    assert_includes item(email: nil).body, "**Can be replied to:** no"
  end

  def test_user_text_is_fenced_and_cannot_close_the_fence_or_ping_anyone
    body = item(request: "hi @maintainer ``` # injected heading\n<img src=x onerror=1>", why: nil).body
    assert_includes body, "```text\nhi @maintainer ''' # injected heading"
    assert_equal 1, body.scan(/^```text$/).size
    assert_equal 1, body.scan(/^```$/).size
  end

  def test_a_hostile_hidden_field_cannot_inject_markdown
    body = item(context: { "version" => "1.0`\n# pwned", "platform" => "x" * 200 }).body
    refute_includes body, "# pwned"
    assert_includes body, "**version:** `1.0?" # newline and backtick replaced
    assert_operator body[/\*\*platform:\*\* `([^`]*)`/, 1].length, :<=, 40
  end

  def test_a_missing_hidden_field_block_is_fine
    body = item(context: nil).body
    refute_includes body, "**version:**"
  end

  def test_a_very_long_request_is_truncated
    body = item(request: "y" * 10_000).body
    assert_operator body.length, :<, 6_000
  end

  # --- the connector against Tally ---------------------------------------------------

  def connector(http)
    Tally.new(form_id: "bZVJMg", token: "tally-secret-key", http: http)
  end

  def page(submissions, has_more: false)
    { "hasMore" => has_more, "questions" => QUESTIONS, "submissions" => submissions }
  end

  def test_items_lists_completed_submissions_oldest_first_and_follows_pages
    http = FakeHttp.new(
      [:get, "https://api.tally.so/forms/bZVJMg/submissions?filter=completed&page=1"] => [200, page([submission(id: "new", at: "2026-10-19T12:00:00.000Z")], has_more: true)],
      [:get, "https://api.tally.so/forms/bZVJMg/submissions?filter=completed&page=2"] => [200, page([submission(id: "old", at: "2026-10-18T12:00:00.000Z"), submission(id: "blank", request: " ")])],
    )
    ids = connector(http).items.map(&:id)
    assert_equal %w[old new], ids
    assert_equal ["Bearer tally-secret-key"], http.requests.map { |r| r[:headers]["Authorization"] }.uniq
  end

  def test_an_api_error_never_carries_the_key
    http = FakeHttp.new([:get, "https://api.tally.so/forms/"] => [401, { "message" => "Bearer tally-secret-key rejected" }])
    error = assert_raises(OpsBridge::ApiError) { connector(http).items }
    assert_equal "Tally answered HTTP 401 listing submissions", error.message
    refute_includes error.message, "tally-secret-key"
  end

  def test_non_json_is_an_api_error
    http = FakeHttp.new([:get, "https://api.tally.so/forms/"] => [200, "<html>"])
    assert_raises(OpsBridge::ApiError) { connector(http).items }
  end

  def test_missing_configuration_is_an_error_that_names_the_variable_not_a_value
    error = assert_raises(OpsBridge::ApiError) { Tally.new(form_id: "", token: "k") }
    assert_includes error.message, "TALLY_FEATURE_REQUEST_FORM_ID"
    assert_raises(OpsBridge::ApiError) { Tally.new(form_id: "f", token: nil) }
  end

  # --- through the bridge -------------------------------------------------------------

  def test_a_second_run_opens_nothing
    http = FakeHttp.new([:get, "https://api.tally.so/forms/"] => [200, page([submission(id: "a"), submission(id: "b", at: "2026-10-19T13:00:00.000Z")])])
    sink = StubSink.new([])
    opened = OpsBridge.sync(connector: connector(http), sink: sink)
    assert_equal 2, opened.size
    again = OpsBridge.sync(connector: connector(http), sink: StubSink.new(%w[a b]))
    assert_empty again
  end

  def test_one_run_opens_at_most_the_cap_and_the_rest_wait
    subs = (1..15).map { |n| submission(id: "s#{n}", at: format("2026-10-19T12:%02d:00.000Z", n)) }
    http = FakeHttp.new([:get, "https://api.tally.so/forms/"] => [200, page(subs)])
    sink = StubSink.new([])
    OpsBridge.sync(connector: connector(http), sink: sink)
    assert_equal (1..10).map { |n| "s#{n}" }, sink.created

    next_run = StubSink.new((1..10).map { |n| "s#{n}" })
    OpsBridge.sync(connector: connector(http), sink: next_run)
    assert_equal (11..15).map { |n| "s#{n}" }, next_run.created
  end

  def test_the_connector_has_no_reverse_mapping
    refute_respond_to connector(FakeHttp.new({})), :resolve
  end

  def test_main_wires_the_connector_and_reports_a_missing_secret
    out = StringIO.new
    code = OpsBridge.main(%w[sync tally-feature-requests], { "GITHUB_REPOSITORY" => "o/r", "GITHUB_TOKEN" => "t" }, out: out)
    assert_equal 1, code
    assert_includes out.string, "TALLY_FEATURE_REQUEST_FORM_ID"
  end
end

require "openssl"
require_relative "ops_bridge_test_helper"

class PlayReviewsTest < Minitest::Test
  Play = OpsBridge::Connectors::PlayReviews

  KEY = OpenSSL::PKey::RSA.new(2048)
  ACCOUNT = JSON.generate("client_email" => "reviews@muviss.iam.gserviceaccount.com", "private_key" => KEY.to_pem, "token_uri" => "https://oauth2.googleapis.com/token")

  def review(stars: 2, text: "Crashes when I open a show", id: "gp:AOqpTOExample-1", name: "Jane Doe", extra: {})
    {
      "reviewId" => id,
      "authorName" => name,
      "comments" => [{
        "userComment" => {
          "text" => text,
          "starRating" => stars,
          "lastModified" => { "seconds" => "1790000000" },
          "appVersionName" => "1.1.0",
          "appVersionCode" => 160,
          "androidOsVersion" => 34,
          "reviewerLanguage" => "en",
          "deviceMetadata" => { "manufacturer" => "Google", "productName" => "Pixel 8" },
        }.merge(extra),
      }],
    }
  end

  # --- which reviews become items ---------------------------------------------------

  def test_a_low_rating_with_text_becomes_an_item
    item = Play.to_item(review)
    assert_equal "gp:AOqpTOExample-1", item.id
    assert_equal ["from-play-review", "user-feedback"], item.labels
    assert_includes item.title, "[Play review ★★]"
    assert_includes item.title, "Crashes when I open a show"
  end

  def test_praise_is_not_an_issue
    assert_nil Play.to_item(review(stars: 4))
    assert_nil Play.to_item(review(stars: 5))
  end

  def test_three_stars_is_still_feedback
    refute_nil Play.to_item(review(stars: 3))
  end

  def test_a_review_without_text_or_with_no_user_comment_is_skipped
    assert_nil Play.to_item(review(text: "   "))
    assert_nil Play.to_item("reviewId" => "x", "comments" => [{ "developerComment" => { "text" => "hi" } }])
    assert_nil Play.to_item("reviewId" => "x", "comments" => [])
  end

  # --- the body ---------------------------------------------------------------------

  def test_the_body_has_the_facts_and_the_marker
    body = Play.to_item(review).body
    assert_includes body, "**Rating:** ★★☆☆☆ (2/5)"
    assert_includes body, "**App version:** `1.1.0` (160)"
    assert_includes body, "**Android:** API 34"
    assert_includes body, "**Device:** Google Pixel 8"
    assert_includes body, "**Language:** en"
    assert_includes body, "**Last edited:** 2026-"
    assert_includes body, "<!-- play-review:gp:AOqpTOExample-1 -->"
  end

  def test_the_reviewers_name_never_reaches_the_issue
    item = Play.to_item(review(name: "Jane Doe"))
    refute_includes item.title, "Jane"
    refute_includes item.body, "Jane"
    refute_includes item.body, "Doe"
  end

  def test_the_text_goes_in_a_fence_so_a_mention_cannot_ping_anyone
    body = Play.to_item(review(text: "thanks @someone and ```boom```")).body
    fenced = body[/```text\n(.*?)\n```/m, 1]
    assert_includes fenced, "@someone"
    refute_includes fenced, "```", "a fence in the text would close ours early"
  end

  def test_a_title_and_body_joined_by_play_with_a_tab_are_both_kept
    item = Play.to_item(review(text: "Broken\tThe app closes on launch"))
    assert_includes item.title, "Broken"
    assert_includes item.body, "The app closes on launch"
  end

  def test_the_marker_round_trips_through_the_bridge
    assert_equal "gp:AOqpTOExample-1", OpsBridge.marker_in("play-review", Play.to_item(review).body)
  end

  # --- the source -----------------------------------------------------------------------

  def connector(responses)
    http = FakeHttp.new(responses)
    [Play.new(service_account_json: ACCOUNT, http: http, clock: -> { 1_790_000_000 }), http]
  end

  TOKEN = [:post, "https://oauth2.googleapis.com/token"].freeze

  def test_it_needs_a_well_formed_service_account
    assert_raises(OpsBridge::ApiError) { Play.new(service_account_json: "") }
    assert_raises(OpsBridge::ApiError) { Play.new(service_account_json: "not json") }
    assert_raises(OpsBridge::ApiError) { Play.new(service_account_json: JSON.generate("client_email" => "x")) }
  end

  def test_the_token_exchange_signs_a_verifiable_jwt_for_the_android_publisher_scope
    source, http = connector(TOKEN => [200, { "access_token" => "ya29.token" }])
    assert_equal "ya29.token", source.access_token

    request = http.requests.last
    assert_equal "application/x-www-form-urlencoded", request[:headers]["Content-Type"]
    form = URI.decode_www_form(request[:body]).to_h
    assert_equal "urn:ietf:params:oauth:grant-type:jwt-bearer", form["grant_type"]

    header, claims, signature = form["assertion"].split(".")
    decode = ->(part) { Base64.urlsafe_decode64(part + "=" * ((4 - part.length % 4) % 4)) }
    assert_equal "RS256", JSON.parse(decode.call(header))["alg"]
    payload = JSON.parse(decode.call(claims))
    assert_equal "reviews@muviss.iam.gserviceaccount.com", payload["iss"]
    assert_equal "https://www.googleapis.com/auth/androidpublisher", payload["scope"]
    assert_equal "https://oauth2.googleapis.com/token", payload["aud"]
    assert_equal 1_790_000_000, payload["iat"]
    assert_equal 1_790_003_600, payload["exp"]
    assert KEY.public_key.verify(OpenSSL::Digest.new("SHA256"), decode.call(signature), "#{header}.#{claims}"), "the signature must verify against the account's public key"
  end

  def test_a_failed_token_exchange_names_the_status_and_never_the_key
    source, = connector(TOKEN => [401, {}])
    error = assert_raises(OpsBridge::ApiError) { source.access_token }
    assert_includes error.message, "401"
    refute_includes error.message, "PRIVATE KEY"
  end

  def test_items_pages_through_the_reviews_and_keeps_only_the_feedback
    page1 = { "reviews" => [review(id: "r1"), review(id: "r2", stars: 5)], "tokenPagination" => { "nextPageToken" => "next" } }
    page2 = { "reviews" => [review(id: "r3", stars: 1)] }
    source, http = connector(
      TOKEN => [200, { "access_token" => "ya29.token" }],
      [:get, "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/com.codingpit.muviss/reviews?maxResults=100&token=next"] => [200, page2],
      [:get, "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/com.codingpit.muviss/reviews?maxResults=100"] => [200, page1],
    )
    assert_equal %w[r1 r3], source.items.map(&:id)
    assert_equal "Bearer ya29.token", http.requests.last[:headers]["Authorization"]
  end

  def test_an_api_error_listing_reviews_is_an_error
    source, = connector(
      TOKEN => [200, { "access_token" => "t" }],
      [:get, "https://androidpublisher.googleapis.com/"] => [403, {}],
    )
    error = assert_raises(OpsBridge::ApiError) { source.items }
    assert_includes error.message, "403"
  end

  def test_there_is_no_reverse_mapping
    source, = connector({})
    refute_respond_to source, :resolve
  end

  def test_a_second_run_opens_nothing_for_a_review_it_already_filed
    source, = connector(
      TOKEN => [200, { "access_token" => "t" }],
      [:get, "https://androidpublisher.googleapis.com/"] => [200, { "reviews" => [review] }],
    )
    sink = StubSink.new(["gp:AOqpTOExample-1"])
    OpsBridge.sync(connector: source, sink: sink)
    assert_empty sink.created
  end
end

# The App Store Connect client against a fake transport (no network):
#   ruby -Ifastlane fastlane/test/app_store_connect_test.rb
require "minitest/autorun"
require "openssl"
require "uri"
require_relative "../app_store_connect"

class FakeTransport
  attr_reader :sent

  # `answers` maps "METHOD path" (path without the query) to [status, body-hash-or-string].
  def initialize(answers = {})
    @answers = answers
    @sent = []
  end

  def call(method, uri, token, body)
    @sent << { method: method, uri: uri, token: token, body: body }
    status, payload = @answers.fetch("#{method.to_s.upcase} #{uri.path}", [200, { "data" => [] }])
    [status, payload.is_a?(String) ? payload : JSON.generate(payload)]
  end
end

class AppStoreConnectTest < Minitest::Test
  def client(answers = {}, transport: nil)
    AppStoreConnect::Client.new(key_id: "KEY", issuer_id: "ISS", key_pem: "pem", transport: transport || FakeTransport.new(answers),
                                token_source: -> { "test-token" })
  end

  def test_version_reads_the_state_of_the_matching_ios_version
    answers = { "GET /v1/apps/app-1/appStoreVersions" => [200, { "data" => [{ "id" => "v1", "attributes" => { "appStoreState" => "IN_REVIEW" } }] }] }
    assert_equal({ "id" => "v1", "state" => "IN_REVIEW" }, client(answers).version("app-1", "1.0.0"))
  end

  def test_version_asks_for_the_ios_platform_and_the_version_string
    transport = FakeTransport.new
    client(transport: transport).version("app-1", "1.0.0")
    query = URI.decode_www_form(transport.sent.first[:uri].query).to_h
    assert_equal "1.0.0", query["filter[versionString]"]
    assert_equal "IOS", query["filter[platform]"]
  end

  def test_a_missing_phased_release_reads_as_nil
    answers = { "GET /v1/appStoreVersions/v1/appStoreVersionPhasedRelease" => [404, { "errors" => [] }] }
    assert_nil client(answers).phased_release("v1")
  end

  def test_the_phased_state_is_patched_on_the_phased_release_resource
    transport = FakeTransport.new
    client(transport: transport).set_phased_state("p1", "PAUSED")
    sent = transport.sent.first
    assert_equal :patch, sent[:method]
    assert_equal "/v1/appStoreVersionPhasedReleases/p1", sent[:uri].path
    assert_equal({ "data" => { "type" => "appStoreVersionPhasedReleases", "id" => "p1", "attributes" => { "phasedReleaseState" => "PAUSED" } } },
                 JSON.parse(JSON.generate(sent[:body])))
  end

  def test_a_failed_request_raises_with_the_status_and_path_but_not_the_body
    answers = { "GET /v1/apps" => [401, "secret-looking-body"] }
    error = assert_raises(AppStoreConnect::Error) { client(answers).app_id("com.codingpit.muviss") }
    assert_includes error.message, "HTTP 401"
    assert_includes error.message, "/v1/apps"
    refute_includes error.message, "secret-looking-body"
  end

  def test_the_beta_review_state_is_read_from_the_build_submission
    answers = { "GET /v1/builds/b1/betaAppReviewSubmission" => [200, { "data" => { "attributes" => { "betaReviewState" => "APPROVED" } } }] }
    assert_equal "APPROVED", client(answers).beta_review_state("b1")
  end

  def test_remove_from_review_cancels_only_the_submission_holding_the_version
    answers = {
      "GET /v1/reviewSubmissions" => [200, { "data" => [{ "id" => "s1" }, { "id" => "s2" }] }],
      "GET /v1/reviewSubmissions/s1/items" => [200, { "data" => [{ "relationships" => { "appStoreVersion" => { "data" => { "id" => "other" } } } }] }],
      "GET /v1/reviewSubmissions/s2/items" => [200, { "data" => [{ "relationships" => { "appStoreVersion" => { "data" => { "id" => "v1" } } } }] }],
    }
    transport = FakeTransport.new(answers)
    assert client(transport: transport).remove_from_review("app-1", "v1")
    patches = transport.sent.select { |s| s[:method] == :patch }
    assert_equal ["/v1/reviewSubmissions/s2"], patches.map { |s| s[:uri].path }
    assert_equal({ "data" => { "type" => "reviewSubmissions", "id" => "s2", "attributes" => { "canceled" => true } } },
                 JSON.parse(JSON.generate(patches.first[:body])))
  end

  def test_remove_from_review_does_nothing_when_no_submission_holds_the_version
    transport = FakeTransport.new("GET /v1/reviewSubmissions" => [200, { "data" => [] }])
    refute client(transport: transport).remove_from_review("app-1", "v1")
    assert_equal 1, transport.sent.size
  end

  def test_the_token_is_an_es256_jwt_naming_the_key_and_issuer
    begin
      require "jwt"
    rescue LoadError
      skip "the jwt gem ships with fastlane; run this test under bundle exec"
    end
    key = OpenSSL::PKey::EC.generate("prime256v1")
    now = Time.at(1_800_000_000)
    token_client = AppStoreConnect::Client.new(key_id: "KEY", issuer_id: "ISS", key_pem: key.to_pem, clock: -> { now })
    payload, header = JWT.decode(token_client.token, key.public_key, true, algorithm: "ES256")
    assert_equal "KEY", header["kid"]
    assert_equal "ISS", payload["iss"]
    assert_equal "appstoreconnect-v1", payload["aud"]
    assert_operator payload["exp"] - payload["iat"], :<=, 20 * 60
  end
end

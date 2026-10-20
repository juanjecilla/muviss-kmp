# A thin App Store Connect REST client for the iOS train (ADR 0033): the handful of
# reads and writes the train needs that fastlane's actions do not expose (the
# phased release, a version's state, a review submission's removal, Beta App
# Review's state). Authenticated with the same API key CI already holds
# (APP_STORE_CONNECT_KEY_ID / _ISSUER_ID / _KEY_P8). The transport is injected, so
# the tests drive this class with a fake and never touch Apple.
#
# Every endpoint below that ADR 0033 lists as unverified is marked VERIFY. They
# are checked on the first live run (docs/RELEASING.md, iOS in the train); the
# method that depends on one says so in its comment, and no caller assumes an
# answer it did not read.
require "json"
require "net/http"
require "openssl"
require "uri"

module AppStoreConnect
  HOST = "https://api.appstoreconnect.apple.com".freeze
  AUDIENCE = "appstoreconnect-v1".freeze

  class Error < StandardError; end

  # One HTTP exchange. Real: Net::HTTP. Tests pass a lambda or an object with `call`.
  class NetHttpTransport
    def call(method, uri, token, body)
      request = Net::HTTP.const_get(method.to_s.capitalize).new(uri)
      request["Authorization"] = "Bearer #{token}"
      request["Content-Type"] = "application/json" if body
      request.body = JSON.generate(body) if body
      response = Net::HTTP.start(uri.host, uri.port, use_ssl: true, open_timeout: 10, read_timeout: 30) { |http| http.request(request) }
      [response.code.to_i, response.body.to_s]
    end
  end

  class Client
    attr_reader :requests

    def initialize(key_id:, issuer_id:, key_pem:, transport: NetHttpTransport.new, clock: -> { Time.now }, token_source: nil)
      @key_id = key_id
      @issuer_id = issuer_id
      @key_pem = key_pem
      @transport = transport
      @clock = clock
      @token_source = token_source || -> { token }
      @requests = []
    end

    # ES256 JWT for App Store Connect: 20 minutes at most. `jwt` ships with fastlane.
    def token
      require "jwt"
      now = @clock.call.to_i
      payload = { iss: @issuer_id, iat: now, exp: now + 15 * 60, aud: AUDIENCE }
      JWT.encode(payload, OpenSSL::PKey::EC.new(@key_pem), "ES256", kid: @key_id)
    end

    # The request path is always under /v1; `query` is a Hash of already-named
    # parameters (filter[...], limit, include, fields[...]).
    def request(method, path, query: nil, body: nil)
      uri = URI("#{HOST}#{path}")
      uri.query = URI.encode_www_form(query) if query && !query.empty?
      @requests << { method: method, path: path, query: query, body: body }
      code, text = @transport.call(method, uri, @token_source.call, body)
      return nil if code == 404 && method == :get
      raise Error, "App Store Connect answered HTTP #{code} for #{method.to_s.upcase} #{path}" unless (200..299).cover?(code)

      text.to_s.empty? ? nil : JSON.parse(text)
    end

    # The app's id from its bundle identifier.
    def app_id(bundle_id)
      data = request(:get, "/v1/apps", query: { "filter[bundleId]" => bundle_id })
      first_id(data)
    end

    # { "id", "state" } of the iOS version with this version string, or nil.
    # `state` is appStoreState (READY_FOR_SALE, WAITING_FOR_REVIEW, ...).
    def version(app_id, version_string)
      data = request(:get, "/v1/apps/#{app_id}/appStoreVersions",
                     query: { "filter[versionString]" => version_string, "filter[platform]" => "IOS", "limit" => 5 })
      row = Array(data && data["data"]).first
      row && { "id" => row["id"], "state" => row.dig("attributes", "appStoreState") }
    end

    # { "id", "state" } of a version's phased release, or nil when it has none.
    # VERIFY (ADR 0033 assumption 1, 2): the state values come back as ACTIVE,
    # COMPLETE, INACTIVE or PAUSED (Apple's PhasedReleaseState).
    def phased_release(version_id)
      data = request(:get, "/v1/appStoreVersions/#{version_id}/appStoreVersionPhasedRelease")
      row = data && data["data"]
      row && { "id" => row["id"], "state" => row.dig("attributes", "phasedReleaseState") }
    end

    # PATCH /v1/appStoreVersionPhasedReleases/{id}. The state is PAUSED (or ACTIVE
    # to resume). VERIFY (ADR 0033 assumption 2, 3): the enum value and whether
    # Apple answers 409 for a PATCH in Pending Developer Release. The train never
    # calls this for a version outside a live rollout (ios_train.rb ramp_decision).
    def set_phased_state(phased_id, state)
      request(:patch, "/v1/appStoreVersionPhasedReleases/#{phased_id}",
              body: { data: { type: "appStoreVersionPhasedReleases", id: phased_id,
                              attributes: { phasedReleaseState: state } } })
    end

    # The build id for a build number of a version, or nil. TestFlight processing
    # finishes before it is listed, so nil can also mean "not yet processed".
    def build_id(app_id, version_string, build_number)
      data = request(:get, "/v1/builds",
                     query: { "filter[app]" => app_id, "filter[version]" => build_number.to_s,
                              "filter[preReleaseVersion.version]" => version_string,
                              "filter[preReleaseVersion.platform]" => "IOS", "limit" => 5 })
      first_id(data)
    end

    # Beta App Review's state for a build: WAITING_FOR_REVIEW, IN_REVIEW, APPROVED,
    # REJECTED, or nil when no review was ever submitted for it.
    # VERIFY (ADR 0033 assumption 5, 6): the state names and the relationship path.
    def beta_review_state(build_id)
      data = request(:get, "/v1/builds/#{build_id}/betaAppReviewSubmission")
      data && data["data"] && data.dig("data", "attributes", "betaReviewState")
    end

    # Removes a version from review: finds the review submission that holds it and
    # cancels it, which returns the version to Developer Rejected-style editable
    # state. VERIFY (ADR 0033 assumption 4): the cancel call's shape
    # (PATCH /v1/reviewSubmissions/{id} with canceled: true). Returns true when a
    # submission was found and cancelled, false when none held the version.
    def remove_from_review(app_id, version_id)
      data = request(:get, "/v1/reviewSubmissions",
                     query: { "filter[app]" => app_id, "filter[platform]" => "IOS",
                              "filter[state]" => "WAITING_FOR_REVIEW,IN_REVIEW", "limit" => 10 })
      Array(data && data["data"]).each do |submission|
        items = request(:get, "/v1/reviewSubmissions/#{submission['id']}/items", query: { "include" => "appStoreVersion" })
        holds = Array(items && items["data"]).any? { |item| item.dig("relationships", "appStoreVersion", "data", "id") == version_id }
        next unless holds

        request(:patch, "/v1/reviewSubmissions/#{submission['id']}",
                body: { data: { type: "reviewSubmissions", id: submission["id"], attributes: { canceled: true } } })
        return true
      end
      false
    end

    private

    def first_id(data)
      row = Array(data && data["data"]).first
      row && row["id"]
    end
  end
end

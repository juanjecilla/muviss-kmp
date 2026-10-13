require "base64"
require "openssl"
require_relative "../bridge"

module OpsBridge
  module Connectors
    # Google Play reviews -> GitHub issue (EPIC 48, connector #2). A low-star review is the one
    # signal about the *released* app that no crash reporter sees, and Play's console is not
    # somewhere anyone looks daily.
    #
    # What the Reviews API gives (developers.google.com/android-publisher/reply-to-reviews):
    # only production versions, only reviews **with text**, and only those created or edited in
    # the **last week**. So the connector must run at least weekly (the workflow runs daily) and
    # dedups on the review id, which also covers an edited review: it keeps its one issue.
    #
    # The repository goes public. A review is public on Play already, but **the reviewer's name
    # is never copied** (nor anything that identifies a person); the text goes in a code fence so
    # an `@name` in it cannot ping someone and markdown in it cannot rewrite the issue.
    class PlayReviews
      KIND = "play-review".freeze
      SOURCE_LABEL = "from-play-review".freeze
      LABELS = [SOURCE_LABEL, "user-feedback"].freeze
      PACKAGE = "com.codingpit.muviss".freeze
      SCOPE = "https://www.googleapis.com/auth/androidpublisher".freeze
      DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token".freeze

      # 3 stars and under is feedback worth a look; 4 and 5 are praise.
      MAX_STARS = 3
      SNIPPET = 70

      def self.to_item(review)
        comment = review.dig("comments", 0, "userComment")
        return nil unless comment.is_a?(Hash)

        stars = comment["starRating"].to_i
        return nil if stars < 1 || stars > MAX_STARS

        text = comment["text"].to_s
        return nil if text.strip.empty?

        id = review.fetch("reviewId")
        headline, body = split_text(text)
        Item.new(id, OpsBridge.title("[Play review #{'★' * stars}]", headline), render_body(id, comment, stars, headline, body), LABELS, nil)
      end

      # Play joins an optional title and the body with a tab.
      def self.split_text(text)
        title, body = text.split("\t", 2)
        if body
          [title.to_s.strip, body.to_s.strip]
        else
          [text.strip.lines.first.to_s.strip.slice(0, SNIPPET), text.strip]
        end
      end

      def self.render_body(id, comment, stars, headline, body)
        rows = [
          ["Rating", "#{'★' * stars}#{'☆' * (5 - stars)} (#{stars}/5)"],
          ["App version", app_version(comment)],
          ["Android", comment["androidOsVersion"] ? "API #{comment['androidOsVersion']}" : nil],
          ["Device", device(comment)],
          ["Language", comment["reviewerLanguage"]],
          ["Last edited", date(comment.dig("lastModified", "seconds"))],
        ].reject { |_, value| value.nil? || value.to_s.empty? }

        lines = ["A review on Google Play, opened by the Ops Bridge. The reviewer's name is deliberately not copied.", ""]
        lines += rows.map { |label, value| "- **#{label}:** #{value}" }
        lines += ["", "```text", fence_safe([headline, body].uniq.reject(&:empty?).join("\n\n")), "```", "", OpsBridge.marker(KIND, id)]
        lines.join("\n")
      end

      def self.app_version(comment)
        name = comment["appVersionName"]
        code = comment["appVersionCode"]
        return nil if name.to_s.empty? && code.to_s.empty?

        "`#{name}`#{code.to_s.empty? ? '' : " (#{code})"}"
      end

      def self.device(comment)
        meta = comment["deviceMetadata"] || {}
        parts = [meta["manufacturer"], meta["productName"]].reject { |part| part.to_s.empty? }
        parts.empty? ? comment["device"] : parts.join(" ")
      end

      def self.date(seconds)
        seconds.to_s.empty? ? nil : Time.at(seconds.to_i).utc.strftime("%Y-%m-%d")
      end

      # A fence inside the text would close ours early.
      def self.fence_safe(text)
        text.gsub("```", "'''")
      end

      # [service_account_json]: the same secret the Play upload uses (PLAY_SERVICE_ACCOUNT_JSON).
      # It needs Play Console's "Reply to reviews" permission, which the upload does not.
      def initialize(service_account_json:, package: PACKAGE, http: Http.new, clock: -> { Time.now.to_i })
        raise ApiError, "PLAY_SERVICE_ACCOUNT_JSON is not set" if service_account_json.to_s.strip.empty?

        @account = parse(service_account_json)
        @package = package
        @http = http
        @clock = clock
      end

      def name
        "play-reviews"
      end

      def kind
        KIND
      end

      def source_label
        SOURCE_LABEL
      end

      def labels
        LABELS
      end

      def items
        token = access_token
        reviews = []
        page = nil
        loop do
          json = get(reviews_url(page), token)
          reviews.concat(Array(json["reviews"]))
          page = json.dig("tokenPagination", "nextPageToken")
          break if page.to_s.empty?
        end
        reviews.map { |review| self.class.to_item(review) }.compact
      end

      # The OAuth access token for the service account, by the JWT-bearer grant (RFC 7523).
      # Stdlib only: the workflow installs no gems, and the token is never logged.
      def access_token
        now = @clock.call
        claims = { iss: @account.fetch("client_email"), scope: SCOPE, aud: token_uri, iat: now, exp: now + 3600 }
        assertion = sign(claims)
        form = URI.encode_www_form(grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: assertion)
        status, body, = @http.request(:post, token_uri, headers: { "Content-Type" => "application/x-www-form-urlencoded" }, body: form)
        raise ApiError, "Google answered HTTP #{status} exchanging the service account for a token" unless (200..299).cover?(status)

        token = JSON.parse(body)["access_token"]
        raise ApiError, "Google answered no access token" if token.to_s.empty?

        token
      rescue JSON::ParserError
        raise ApiError, "Google answered something that is not JSON"
      end

      private

      def parse(json)
        account = JSON.parse(json)
        raise ApiError, "PLAY_SERVICE_ACCOUNT_JSON has no client_email or private_key" unless account.is_a?(Hash) && account["client_email"] && account["private_key"]

        account
      rescue JSON::ParserError
        raise ApiError, "PLAY_SERVICE_ACCOUNT_JSON is not JSON"
      end

      def token_uri
        @account["token_uri"] || DEFAULT_TOKEN_URI
      end

      def sign(claims)
        header = { alg: "RS256", typ: "JWT" }
        signing_input = "#{b64(JSON.generate(header))}.#{b64(JSON.generate(claims))}"
        signature = OpenSSL::PKey::RSA.new(@account.fetch("private_key")).sign(OpenSSL::Digest.new("SHA256"), signing_input)
        "#{signing_input}.#{b64(signature)}"
      rescue OpenSSL::PKey::RSAError
        raise ApiError, "the service account's private key could not be read"
      end

      def b64(data)
        Base64.urlsafe_encode64(data, padding: false)
      end

      def reviews_url(page)
        params = { maxResults: 100 }
        params[:token] = page if page
        "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/#{@package}/reviews?#{URI.encode_www_form(params)}"
      end

      def get(url, token)
        status, body, = @http.request(:get, url, headers: { "Authorization" => "Bearer #{token}" })
        raise ApiError, "Google Play answered HTTP #{status} listing reviews" unless (200..299).cover?(status)

        JSON.parse(body)
      rescue JSON::ParserError
        raise ApiError, "Google Play answered something that is not JSON"
      end
    end
  end
end

require_relative "../bridge"

module OpsBridge
  module Connectors
    # Sentry crash -> GitHub issue, and closing the issue resolves the crash (#300).
    # Sentry's own GitHub integration can open issues automatically only on its Business plan;
    # this does the same on any plan, with the token the Play ramp's crash gate already uses.
    #
    # The body is counts and names only (short id, culprit, releases, events, users, link): never
    # a raw event, so nothing beyond what the app's own scrubber let through reaches GitHub.
    class Sentry
      KIND = "sentry".freeze
      SOURCE_LABEL = "from-sentry".freeze
      LABELS = ["crash", SOURCE_LABEL].freeze

      # Handled errors are ignored: a `MetadataError` is noise, not a crash.
      BASE_QUERY = "is:unresolved error.unhandled:true environment:production".freeze

      def self.query(project: nil)
        project.to_s.empty? ? BASE_QUERY : "#{BASE_QUERY} project:#{project}"
      end

      def self.to_item(issue)
        short_id = issue.fetch("shortId")
        reopen_after = regressed_at(issue)
        Item.new(short_id, OpsBridge.title("[#{short_id}]", issue["title"]), render_body(issue), LABELS, issue["permalink"],
                 reopen_after, reopen_after && reopen_note(issue))
      end

      # When Sentry marks an issue `regressed` (resolved, then seen again), the time it was last
      # seen: the bridge reopens its GitHub issue if that was closed before then (#327). Nil for
      # anything else, or when Sentry gives no usable lastSeen.
      def self.regressed_at(issue)
        return nil unless issue["substatus"] == "regressed" && issue["lastSeen"]

        Time.parse(issue["lastSeen"])
      rescue ArgumentError
        nil
      end

      def self.reopen_note(issue)
        release = release_of(issue["lastRelease"])
        seen_in = release.to_s.empty? ? "" : " in #{code(release)}"
        "Sentry reports this crash as **regressed**: seen again#{seen_in} at #{issue['lastSeen']}, after this issue " \
          "was closed. Reopened by the Ops Bridge. Closing it again resolves it in Sentry for the next release."
      end

      def self.render_body(issue)
        short_id = issue.fetch("shortId")
        rows = [
          ["Sentry", issue["permalink"] ? "[#{short_id}](#{issue['permalink']})" : short_id],
          ["Title", code(issue["title"])],
          ["Culprit", code(issue["culprit"])],
          ["First seen in", code(release_of(issue["firstRelease"]))],
          ["Last seen in", code(release_of(issue["lastRelease"]))],
          ["Events", issue["count"]],
          ["Users affected", issue["userCount"]],
        ].reject { |_, value| value.nil? || value.to_s.empty? }

        lines = ["Unhandled crash in production, opened from Sentry.", ""]
        lines += rows.map { |label, value| "- **#{label}:** #{value}" }
        lines += ["", "Closing this issue resolves it in Sentry for the next release.", "", OpsBridge.marker(KIND, short_id)]
        lines.join("\n")
      end

      def self.release_of(release)
        release.is_a?(Hash) ? release["version"] : release
      end

      def self.code(value)
        value.to_s.empty? ? nil : "`#{value}`"
      end

      def initialize(org:, token:, project: nil, http: Http.new)
        raise ApiError, "SENTRY_AUTH_TOKEN or SENTRY_ORG is not set" if token.to_s.empty? || org.to_s.empty?

        @org = org
        @token = token
        @project = project
        @http = http
      end

      def name
        "sentry"
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

      # New production crashes, as normalised items.
      def items
        uri = URI("https://sentry.io/api/0/organizations/#{@org}/issues/")
        uri.query = URI.encode_www_form(query: self.class.query(project: @project), statsPeriod: "14d", limit: 100)
        json = get(uri.to_s)
        raise ApiError, "Sentry answered something that is not a list" unless json.is_a?(Array)

        json.map { |issue| self.class.to_item(issue) }
      end

      # Resolves in the next release, which is what "closed on GitHub" means here: the fix ships
      # with the next build.
      def resolve(short_id)
        id = group_id(short_id)
        status, = @http.request(
          :put,
          "https://sentry.io/api/0/organizations/#{@org}/issues/?id=#{URI.encode_www_form_component(id)}",
          headers: headers,
          body: { status: "resolved", statusDetails: { inNextRelease: true } },
        )
        raise ApiError, "Sentry answered HTTP #{status} resolving #{short_id}" unless (200..299).cover?(status)
      end

      private

      # The numeric group id behind a short id such as MUVISS-7.
      def group_id(short_id)
        json = get("https://sentry.io/api/0/organizations/#{@org}/shortids/#{short_id}/")
        id = json.is_a?(Hash) ? json["groupId"] : nil
        raise ApiError, "Sentry has no issue #{short_id}" if id.to_s.empty?

        id
      end

      def headers
        { "Authorization" => "Bearer #{@token}", "Content-Type" => "application/json" }
      end

      def get(url)
        status, body, = @http.request(:get, url, headers: headers)
        raise ApiError, "Sentry answered HTTP #{status}" unless (200..299).cover?(status)

        JSON.parse(body)
      rescue JSON::ParserError
        raise ApiError, "Sentry answered something that is not JSON"
      end
    end
  end
end

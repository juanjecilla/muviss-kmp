require "json"
require "net/http"
require "uri"

# The crash gate on the Play rollout ramp (#193, ADR 0025). Before the ramp
# raises a production release's fraction, it asks Sentry for unhandled
# (fatal) issues whose *first* release is this one. Any at all holds the
# fraction where it is; a person then halts or lets it ride. The gate never
# halts on its own and never lowers a fraction.
#
# It fails closed: no token, an HTTP error or an unreadable answer also hold,
# because "we could not look" is not "nothing crashed".
module SentryGate
  APPLICATION_ID = "com.codingpit.muviss".freeze
  SHOWN = 3

  module_function

  # The release name the app reports and the Sentry Gradle plugin stamps on
  # the mapping (`MuvissCrashReporting.releaseOf`): `<appId>@<name>+<code>`.
  def release_name(version_name:, version_code:)
    "#{APPLICATION_ID}@#{version_name}+#{version_code}"
  end

  def query(release)
    %(firstRelease:"#{release}" error.unhandled:true is:unresolved)
  end

  # nil to go ahead; otherwise why the ramp holds.
  def hold_reason(issues:, error: nil)
    return "Sentry could not be asked (#{error}); holding until it can" if error
    return nil if issues.empty?

    named = issues.first(SHOWN).map { |issue| issue["shortId"] || issue["title"] }.join(", ")
    more = issues.size > SHOWN ? " and #{issues.size - SHOWN} more" : ""
    "#{issues.size} new crash issue(s) first seen in this release: #{named}#{more}"
  end

  # Issues for [release], or raises with a message safe to log (never the token).
  def fetch(org:, token:, release:)
    raise "SENTRY_AUTH_TOKEN or SENTRY_ORG is not set" if token.to_s.empty? || org.to_s.empty?

    uri = URI("https://sentry.io/api/0/organizations/#{org}/issues/")
    uri.query = URI.encode_www_form(query: query(release), statsPeriod: "14d", limit: 25)
    request = Net::HTTP::Get.new(uri)
    request["Authorization"] = "Bearer #{token}"
    response = Net::HTTP.start(uri.host, uri.port, use_ssl: true, open_timeout: 10, read_timeout: 20) { |http| http.request(request) }
    raise "Sentry answered HTTP #{response.code}" unless response.is_a?(Net::HTTPSuccess)

    issues = JSON.parse(response.body)
    raise "Sentry answered something that is not a list" unless issues.is_a?(Array)

    issues
  end

  # The whole check: fetch, then decide. Returns nil or the hold reason.
  def check(org:, token:, release:)
    hold_reason(issues: fetch(org: org, token: token, release: release))
  rescue StandardError => e
    hold_reason(issues: [], error: e.message)
  end
end

require "json"
require "net/http"
require "uri"

# Sentry crash -> GitHub issue, and closing the issue resolves the crash (#300,
# connector #1 of the Ops Bridge, EPIC 48, ADR 0028).
#
# Shaped as source -> item -> sink so the Ops Bridge can lift it:
#
#   SentryIssues::SentrySource   polls an API for new production crashes
#   SentryIssues::Item           the normalised thing (id, title, body, labels, link)
#   SentryIssues::GithubSink     creates the issue, finds what it already made
#
# Every run is idempotent: the sink's marker (`<!-- sentry:MUVISS-7 -->`, in the
# body) is looked up before anything is created, so re-running changes nothing.
# Nothing here pastes a raw event payload; the body carries only counts and names
# Sentry already holds after the app's own scrubber ran.
#
# Pure functions plus two thin HTTP clients that take an injectable `http`, so
# the tests cover query building, body rendering, dedup and the close mapping
# without a network.
module SentryIssues
  LABELS = %w[crash from-sentry].freeze
  SOURCE_LABEL = "from-sentry".freeze

  # Handled errors are ignored: a `MetadataError` is noise, not a crash.
  BASE_QUERY = "is:unresolved error.unhandled:true environment:production".freeze

  MARKER_PATTERN = /<!-- sentry:([A-Za-z0-9_]+-[A-Za-z0-9]+) -->/.freeze
  TITLE_LIMIT = 200

  # The normalised thing the source produces and the sink consumes.
  Item = Struct.new(:id, :title, :body, :labels, :link)

  module_function

  def marker(short_id)
    "<!-- sentry:#{short_id} -->"
  end

  # The Sentry short id an issue body carries, or nil.
  def marker_in(text)
    text.to_s[MARKER_PATTERN, 1]
  end

  def query(project: nil)
    project.to_s.empty? ? BASE_QUERY : "#{BASE_QUERY} project:#{project}"
  end

  def to_item(issue)
    short_id = issue.fetch("shortId")
    Item.new(
      short_id,
      title_for(short_id, issue["title"]),
      render_body(issue),
      LABELS,
      issue["permalink"],
    )
  end

  def title_for(short_id, title)
    "[#{short_id}] #{title.to_s.strip}".slice(0, TITLE_LIMIT)
  end

  def render_body(issue)
    short_id = issue.fetch("shortId")
    rows = [
      ["Sentry", issue["permalink"] ? "[#{short_id}](#{issue['permalink']})" : short_id],
      ["Culprit", code(issue["culprit"])],
      ["First seen in", code(release_of(issue["firstRelease"]))],
      ["Last seen in", code(release_of(issue["lastRelease"]))],
      ["Events", issue["count"]],
      ["Users affected", issue["userCount"]],
    ].reject { |_, value| value.nil? || value.to_s.empty? }

    lines = ["Unhandled crash in production, opened from Sentry.", ""]
    lines += rows.map { |label, value| "- **#{label}:** #{value}" }
    lines += ["", "Closing this issue resolves it in Sentry for the next release.", "", marker(short_id)]
    lines.join("\n")
  end

  def release_of(release)
    release.is_a?(Hash) ? release["version"] : release
  end

  def code(value)
    value.to_s.empty? ? nil : "`#{value}`"
  end

  # Items for the Sentry issues nobody has opened a GitHub issue for yet, in the order Sentry listed them.
  def new_items(issues, known_ids)
    issues.reject { |issue| known_ids.include?(issue["shortId"]) }.map { |issue| to_item(issue) }
  end

  # ---------------------------------------------------------------------------
  # HTTP: the one place a request leaves the process. `http.request` returns
  # [status, body, headers]. Never logs a header, so a token cannot reach a log.
  # ---------------------------------------------------------------------------
  class Http
    def request(method, url, headers: {}, body: nil)
      uri = URI(url)
      klass = { get: Net::HTTP::Get, post: Net::HTTP::Post, put: Net::HTTP::Put }.fetch(method)
      req = klass.new(uri)
      headers.each { |name, value| req[name] = value }
      req.body = JSON.generate(body) if body
      response = Net::HTTP.start(uri.host, uri.port, use_ssl: true, open_timeout: 10, read_timeout: 30) { |http| http.request(req) }
      [response.code.to_i, response.body.to_s, { "link" => response["link"] }]
    end
  end

  class ApiError < StandardError; end

  # The source: Sentry's API.
  class SentrySource
    def initialize(org:, token:, project: nil, http: Http.new)
      raise ApiError, "SENTRY_AUTH_TOKEN or SENTRY_ORG is not set" if token.to_s.empty? || org.to_s.empty?

      @org = org
      @token = token
      @project = project
      @http = http
    end

    # New production crashes, newest first (Sentry's default order is by last seen).
    def issues
      uri = URI("https://sentry.io/api/0/organizations/#{@org}/issues/")
      uri.query = URI.encode_www_form(query: SentryIssues.query(project: @project), statsPeriod: "14d", limit: 100)
      json = get(uri.to_s)
      raise ApiError, "Sentry answered something that is not a list" unless json.is_a?(Array)

      json
    end

    # The numeric group id behind a short id such as MUVISS-7.
    def group_id(short_id)
      json = get("https://sentry.io/api/0/organizations/#{@org}/shortids/#{short_id}/")
      id = json.is_a?(Hash) ? json["groupId"] : nil
      raise ApiError, "Sentry has no issue #{short_id}" if id.to_s.empty?

      id
    end

    # Resolves in the next release, which is what "closed on GitHub" means here: the fix ships with the next build.
    def resolve_in_next_release(short_id)
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

  # The sink: GitHub issues.
  class GithubSink
    def initialize(repo:, token:, http: Http.new)
      raise ApiError, "GITHUB_REPOSITORY or GITHUB_TOKEN is not set" if repo.to_s.empty? || token.to_s.empty?

      @repo = repo
      @token = token
      @http = http
    end

    # Every Sentry short id that already has a GitHub issue, open or closed. Read from the issues
    # themselves rather than the search index, which lags by minutes and would let an hourly run
    # open a duplicate.
    def known_ids
      ids = []
      each_page("https://api.github.com/repos/#{@repo}/issues?labels=#{SOURCE_LABEL}&state=all&per_page=100") do |issues|
        issues.each do |issue|
          next if issue.key?("pull_request")

          id = SentryIssues.marker_in(issue["body"])
          ids << id if id
        end
      end
      ids
    end

    # Idempotent: a label that exists already answers 422, which is fine.
    def ensure_labels(labels)
      labels.each do |name|
        status, = @http.request(:post, "https://api.github.com/repos/#{@repo}/labels", headers: headers, body: { name: name, color: "b60205" })
        raise ApiError, "GitHub answered HTTP #{status} creating label #{name}" unless [201, 422].include?(status)
      end
    end

    def create(item)
      status, body, = @http.request(
        :post,
        "https://api.github.com/repos/#{@repo}/issues",
        headers: headers,
        body: { title: item.title, body: item.body, labels: item.labels },
      )
      raise ApiError, "GitHub answered HTTP #{status} creating the issue for #{item.id}" unless status == 201

      JSON.parse(body)["html_url"]
    end

    private

    def headers
      {
        "Authorization" => "Bearer #{@token}",
        "Accept" => "application/vnd.github+json",
        "X-GitHub-Api-Version" => "2022-11-28",
        "Content-Type" => "application/json",
      }
    end

    def each_page(url)
      while url
        status, body, response_headers = @http.request(:get, url, headers: headers)
        raise ApiError, "GitHub answered HTTP #{status} listing issues" unless (200..299).cover?(status)

        yield JSON.parse(body)
        url = next_link(response_headers["link"])
      end
    end

    def next_link(link)
      link.to_s[/<([^>]+)>;\s*rel="next"/, 1]
    end
  end

  # The scheduled run: open one issue per new production crash. Returns the items it opened (or would have).
  def sync(source:, sink:, dry_run: false, log: ->(_line) {})
    issues = source.issues
    items = new_items(issues, sink.known_ids)
    log.call("#{issues.size} unresolved production crash(es) in Sentry, #{items.size} without a GitHub issue")
    return items if items.empty?

    if dry_run
      items.each { |item| log.call("would open: #{item.title}") }
      return items
    end

    sink.ensure_labels(LABELS)
    items.each { |item| log.call("opened #{sink.create(item)}") }
    items
  end

  # The closed-issue run: resolve the Sentry issue a closed `from-sentry` GitHub issue was opened for.
  # Returns the short id it resolved, or nil if the issue is not one of ours.
  def resolve_closed(labels:, body:, source:, dry_run: false, log: ->(_line) {})
    unless labels.include?(SOURCE_LABEL)
      log.call("not a #{SOURCE_LABEL} issue; nothing to resolve")
      return nil
    end

    short_id = marker_in(body)
    unless short_id
      log.call("no Sentry marker in the issue body; nothing to resolve")
      return nil
    end

    if dry_run
      log.call("would resolve #{short_id} in the next release")
    else
      source.resolve_in_next_release(short_id)
      log.call("resolved #{short_id} in the next release")
    end
    short_id
  end

  def main(argv, env, out: $stdout)
    command = argv.first
    dry_run = %w[1 true].include?(env["DRY_RUN"].to_s)
    log = ->(line) { out.puts(line) }
    source = SentrySource.new(org: env["SENTRY_ORG"], token: env["SENTRY_AUTH_TOKEN"], project: env["SENTRY_PROJECT"])

    case command
    when "sync"
      sync(source: source, sink: GithubSink.new(repo: env["GITHUB_REPOSITORY"], token: env["GITHUB_TOKEN"]), dry_run: dry_run, log: log)
    when "resolve"
      event = JSON.parse(File.read(env.fetch("GITHUB_EVENT_PATH")))
      issue = event.fetch("issue")
      resolve_closed(labels: issue.fetch("labels").map { |label| label["name"] }, body: issue["body"], source: source, dry_run: dry_run, log: log)
    else
      raise ArgumentError, "usage: sentry_issues.rb sync|resolve"
    end
    0
  rescue ApiError, ArgumentError, KeyError => e
    out.puts("sentry_issues: #{e.message}")
    1
  end
end

exit(SentryIssues.main(ARGV, ENV)) if $PROGRAM_NAME == __FILE__

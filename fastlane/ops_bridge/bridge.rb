require "json"
require "net/http"
require "uri"

# The Ops Bridge (EPIC 48, ADR 0028): connect the tools the project runs on so a
# signal in one becomes a work item in another. Lives in GitHub Actions plus these
# scripts: no server to host, secrets already in Actions, runs on any plan.
#
# Never called "sync": in this repo that word means SyncEngine, the user-library sync.
#
# One shape for every connector:
#
#   connector.items            polls its source, returns normalised OpsBridge::Items
#   GithubSink                 creates a GitHub issue per item, finds what it already made
#   connector.resolve(id)      optional reverse mapping: sink closed -> source resolved
#
# Idempotent by construction: each item's body ends in a marker
# (`<!-- <kind>:<id> -->`) and the sink looks the marker up in the issues it already
# made, so every run is safe to repeat. How to add a connector: fastlane/ops_bridge/README.md.
module OpsBridge
  # The normalised thing a connector produces and the sink consumes.
  Item = Struct.new(:id, :title, :body, :labels, :link)

  class ApiError < StandardError; end

  TITLE_LIMIT = 200

  module_function

  def marker(kind, id)
    "<!-- #{kind}:#{id} -->"
  end

  # The id a connector's marker carries in [text], or nil.
  def marker_in(kind, text)
    text.to_s[/<!-- #{Regexp.escape(kind)}:(\S+) -->/, 1]
  end

  def title(prefix, text)
    "#{prefix} #{text.to_s.strip}".strip.slice(0, TITLE_LIMIT)
  end

  # Items nobody has opened an issue for yet, in the order the source listed them.
  def new_items(items, known_ids)
    items.reject { |item| known_ids.include?(item.id) }
  end

  # The one place a request leaves the process. `request` returns [status, body, headers].
  # A String body is sent as is (the OAuth token exchange is a form); anything else is JSON.
  # Never logs a header, so a token cannot reach a log.
  class Http
    def request(method, url, headers: {}, body: nil)
      uri = URI(url)
      klass = { get: Net::HTTP::Get, post: Net::HTTP::Post, put: Net::HTTP::Put }.fetch(method)
      req = klass.new(uri)
      headers.each { |name, value| req[name] = value }
      req.body = body.is_a?(String) ? body : JSON.generate(body) if body
      response = Net::HTTP.start(uri.host, uri.port, use_ssl: true, open_timeout: 10, read_timeout: 30) { |http| http.request(req) }
      [response.code.to_i, response.body.to_s, { "link" => response["link"] }]
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

    # Every id [connector] already has a GitHub issue for, open or closed. Read from the issues
    # themselves rather than the search index, which lags by minutes and would let a repeat run
    # open a duplicate.
    def known_ids(connector)
      ids = []
      each_page("https://api.github.com/repos/#{@repo}/issues?labels=#{URI.encode_www_form_component(connector.source_label)}&state=all&per_page=100") do |issues|
        issues.each do |issue|
          next if issue.key?("pull_request")

          id = OpsBridge.marker_in(connector.kind, issue["body"])
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

  # The scheduled run: open one issue per new item. Returns the items it opened (or would have).
  def sync(connector:, sink:, dry_run: false, log: ->(_line) {})
    all = connector.items
    items = new_items(all, sink.known_ids(connector))
    log.call("#{connector.name}: #{all.size} item(s) at the source, #{items.size} without a GitHub issue")
    return items if items.empty?

    if dry_run
      items.each { |item| log.call("would open: #{item.title}") }
      return items
    end

    sink.ensure_labels(connector.labels)
    items.each { |item| log.call("opened #{sink.create(item)}") }
    items
  end

  # The closed-issue run: ask the connector to resolve what a closed issue was opened for.
  # Returns the id it resolved, or nil if the issue is not one of that connector's.
  # Only a close as completed resolves: "not planned" or "duplicate" means nobody fixed it,
  # and resolving would hide it (for Sentry, from the next sync and from the ramp's crash gate).
  def resolve_closed(connector:, labels:, body:, state_reason: "completed", dry_run: false, log: ->(_line) {})
    unless connector.respond_to?(:resolve)
      log.call("#{connector.name} has no reverse mapping; nothing to resolve")
      return nil
    end
    unless labels.include?(connector.source_label)
      log.call("not a #{connector.source_label} issue; nothing to resolve")
      return nil
    end
    unless state_reason.nil? || state_reason == "completed"
      log.call("closed as #{state_reason}; not resolving")
      return nil
    end

    id = marker_in(connector.kind, body)
    unless id
      log.call("no #{connector.kind} marker in the issue body; nothing to resolve")
      return nil
    end

    if dry_run
      log.call("would resolve #{id}")
    else
      connector.resolve(id)
      log.call("resolved #{id}")
    end
    id
  end
end

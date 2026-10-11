require "json"
require_relative "ops_bridge/bridge"
require_relative "ops_bridge/connectors/sentry"
require_relative "ops_bridge/connectors/play_reviews"

# Entry point for the Ops Bridge (EPIC 48, ADR 0028). See fastlane/ops_bridge/README.md.
#
#   ruby fastlane/ops_bridge.rb sync    <connector>    open an issue per new item
#   ruby fastlane/ops_bridge.rb resolve <connector>    a GitHub issue was closed: resolve its source
#
# Connectors: sentry, play-reviews. DRY_RUN=1 says what would happen and writes nothing.
module OpsBridge
  module_function

  def connector_for(name, env)
    case name
    when "sentry"
      Connectors::Sentry.new(org: env["SENTRY_ORG"], token: env["SENTRY_AUTH_TOKEN"], project: env["SENTRY_PROJECT"])
    when "play-reviews"
      Connectors::PlayReviews.new(service_account_json: env["PLAY_SERVICE_ACCOUNT_JSON"])
    else
      raise ArgumentError, "unknown connector #{name.inspect} (sentry, play-reviews)"
    end
  end

  def main(argv, env, out: $stdout)
    command, name = argv
    dry_run = %w[1 true].include?(env["DRY_RUN"].to_s)
    log = ->(line) { out.puts(line) }
    raise ArgumentError, "usage: ops_bridge.rb sync|resolve <sentry|play-reviews>" unless %w[sync resolve].include?(command) && name

    connector = connector_for(name, env)
    if command == "sync"
      sync(connector: connector, sink: GithubSink.new(repo: env["GITHUB_REPOSITORY"], token: env["GITHUB_TOKEN"]), dry_run: dry_run, log: log)
    else
      issue = JSON.parse(File.read(env.fetch("GITHUB_EVENT_PATH"))).fetch("issue")
      resolve_closed(connector: connector, labels: issue.fetch("labels").map { |label| label["name"] }, body: issue["body"], dry_run: dry_run, log: log)
    end
    0
  rescue ApiError, ArgumentError, KeyError => e
    out.puts("ops_bridge: #{e.message}")
    1
  end
end

exit(OpsBridge.main(ARGV, ENV)) if $PROGRAM_NAME == __FILE__

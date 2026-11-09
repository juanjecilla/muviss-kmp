# Pure helpers for the iOS lanes (WS3, ADR 0033). Deliberately free of fastlane
# so fastlane/test/ios_release_test.rb runs under plain ruby.
module IosRelease
  # Written into the review contact files until the owner has filled them in.
  # `release` refuses to upload while any file still carries it.
  PLACEHOLDER = "REPLACE_BEFORE_SUBMIT".freeze

  API_KEY_VARS = %w[APP_STORE_CONNECT_KEY_ID APP_STORE_CONNECT_ISSUER_ID APP_STORE_CONNECT_KEY_P8].freeze
  SENTRY_VARS = %w[SENTRY_AUTH_TOKEN SENTRY_ORG SENTRY_PROJECT].freeze

  module_function

  # Names of the App Store Connect variables that are unset or blank.
  def missing_api_key_vars(env)
    API_KEY_VARS.reject { |name| !env[name].to_s.strip.empty? }
  end

  # Files under `dir` that still hold the placeholder, relative to `dir`, sorted.
  def placeholder_files(dir)
    Dir.glob(File.join(dir, "**", "*.txt")).sort.select do |path|
      File.read(path, encoding: "UTF-8").include?(PLACEHOLDER)
    end.map { |path| path.delete_prefix("#{dir.chomp('/')}/") }
  end

  # The Sentry settings for dSYM upload, or nil when any of them is missing:
  # the lane then skips with a notice rather than failing the RC.
  def sentry_settings(env)
    values = SENTRY_VARS.to_h { |name| [name, env[name].to_s.strip] }
    return nil if values.values.any?(&:empty?)

    { token: values["SENTRY_AUTH_TOKEN"], org: values["SENTRY_ORG"], project: values["SENTRY_PROJECT"] }
  end

  # `vX.Y.Z-rcN` (or a bare vX.Y.Z) to the numeric version App Store Connect
  # shows: the same rule as scripts/ios/set-version.sh.
  def version_name(tag)
    tag.to_s.delete_prefix("v").sub(/-rc\d+\z/, "")
  end

  # Whether `tag` is a release candidate's, vX.Y.Z-rcN: the only kind of tag
  # with a TestFlight build behind it.
  def rc_tag?(tag)
    tag.to_s.match?(/\Av\d+\.\d+\.\d+-rc\d+\z/)
  end

  # Extra xcodebuild arguments that let `-allowProvisioningUpdates` create and
  # fetch cloud-managed signing assets without an Apple ID session.
  def xcode_auth_args(key_id:, issuer_id:, key_path:, team_id: nil)
    args = ["-allowProvisioningUpdates",
            "-authenticationKeyID", key_id,
            "-authenticationKeyIssuerID", issuer_id,
            "-authenticationKeyPath", key_path]
    args += ["DEVELOPMENT_TEAM=#{team_id}"] if team_id && !team_id.empty?
    args.join(" ")
  end

  # The TestFlight group names from a comma-separated variable, or nil.
  def testflight_groups(value)
    names = value.to_s.split(",").map(&:strip).reject(&:empty?)
    names.empty? ? nil : names
  end
end

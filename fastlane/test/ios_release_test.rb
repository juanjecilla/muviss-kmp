# Tests the iOS release helpers (fastlane/ios_release.rb). Run with the other
# fastlane tests:  ruby -Ifastlane fastlane/test/ios_release_test.rb
require "minitest/autorun"
require "tmpdir"
require "ios_release"

class IosReleaseTest < Minitest::Test
  def test_missing_api_key_vars_lists_each_blank_or_unset_name
    env = { "APP_STORE_CONNECT_KEY_ID" => "ABC", "APP_STORE_CONNECT_ISSUER_ID" => "  " }
    assert_equal %w[APP_STORE_CONNECT_ISSUER_ID APP_STORE_CONNECT_KEY_P8], IosRelease.missing_api_key_vars(env)
  end

  def test_missing_api_key_vars_is_empty_when_all_set
    env = { "APP_STORE_CONNECT_KEY_ID" => "a", "APP_STORE_CONNECT_ISSUER_ID" => "b", "APP_STORE_CONNECT_KEY_P8" => "c" }
    assert_empty IosRelease.missing_api_key_vars(env)
  end

  def test_placeholder_files_names_every_file_still_holding_the_stub
    Dir.mktmpdir do |dir|
      FileUtils.mkdir_p(File.join(dir, "review_information"))
      File.write(File.join(dir, "review_information", "email_address.txt"), "REPLACE_BEFORE_SUBMIT\n")
      File.write(File.join(dir, "review_information", "notes.txt"), "No account needed.\n")
      File.write(File.join(dir, "name.txt"), "Muviss\n")
      assert_equal ["review_information/email_address.txt"], IosRelease.placeholder_files(dir)
    end
  end

  def test_sentry_settings_is_nil_unless_all_three_are_set
    full = { "SENTRY_AUTH_TOKEN" => "t", "SENTRY_ORG" => "o", "SENTRY_PROJECT" => "p" }
    assert_equal({ token: "t", org: "o", project: "p" }, IosRelease.sentry_settings(full))
    assert_nil IosRelease.sentry_settings(full.merge("SENTRY_PROJECT" => ""))
    assert_nil IosRelease.sentry_settings({})
  end

  def test_version_name_drops_the_v_and_the_rc_suffix
    assert_equal "1.4.0", IosRelease.version_name("v1.4.0-rc3")
    assert_equal "1.4.0", IosRelease.version_name("v1.4.0")
  end

  def test_rc_tag_accepts_only_a_release_candidate
    assert IosRelease.rc_tag?("v1.4.0-rc3")
    refute IosRelease.rc_tag?("v1.4.0")
    refute IosRelease.rc_tag?("v1.4.0-rc")
    refute IosRelease.rc_tag?("1.4.0-rc3")
    refute IosRelease.rc_tag?("")
  end

  def test_xcode_auth_args_carries_the_key_and_the_team
    args = IosRelease.xcode_auth_args(key_id: "KEY", issuer_id: "ISS", key_path: "/tmp/AuthKey_KEY.p8", team_id: "TEAM")
    assert_equal "-allowProvisioningUpdates -authenticationKeyID KEY -authenticationKeyIssuerID ISS " \
                 "-authenticationKeyPath /tmp/AuthKey_KEY.p8 DEVELOPMENT_TEAM=TEAM", args
  end

  def test_xcode_auth_args_omits_the_team_when_blank
    args = IosRelease.xcode_auth_args(key_id: "K", issuer_id: "I", key_path: "/p", team_id: "")
    refute_includes args, "DEVELOPMENT_TEAM"
  end

  def test_testflight_groups_splits_and_drops_blanks
    assert_equal ["Internal", "Core"], IosRelease.testflight_groups(" Internal, ,Core ")
    assert_nil IosRelease.testflight_groups("")
    assert_nil IosRelease.testflight_groups(nil)
  end
end

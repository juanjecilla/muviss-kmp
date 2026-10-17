# Checks the iOS App Store metadata (fastlane/metadata/ios) against App Store
# Connect's field limits and the rules that keep it honest. Run with the other
# fastlane tests:  ruby -Ifastlane fastlane/test/ios_metadata_test.rb
require "minitest/autorun"

class IosMetadataTest < Minitest::Test
  ROOT = File.expand_path("../..", __dir__)
  IOS = File.join(ROOT, "fastlane/metadata/ios")
  ANDROID = File.join(ROOT, "fastlane/metadata/android")
  LOCALES = %w[en-US es-ES].freeze

  # App Store Connect's limits, in characters.
  LIMITS = { "name.txt" => 30, "subtitle.txt" => 30, "keywords.txt" => 100,
             "promotional_text.txt" => 170, "description.txt" => 4000 }.freeze

  REQUIRED = %w[name.txt subtitle.txt keywords.txt promotional_text.txt description.txt
                release_notes.txt support_url.txt marketing_url.txt privacy_url.txt].freeze

  REVIEW = %w[first_name.txt last_name.txt email_address.txt phone_number.txt notes.txt].freeze
  PLACEHOLDER = "REPLACE_BEFORE_SUBMIT"

  def read(*parts)
    File.read(File.join(IOS, *parts), encoding: "UTF-8").strip
  end

  def test_every_locale_has_every_required_field
    LOCALES.each do |locale|
      REQUIRED.each do |file|
        path = File.join(IOS, locale, file)
        assert File.file?(path), "#{locale}/#{file} is missing"
        refute_empty read(locale, file), "#{locale}/#{file} is empty"
      end
    end
  end

  def test_fields_stay_within_app_store_connect_limits
    LOCALES.each do |locale|
      LIMITS.each do |file, limit|
        length = read(locale, file).length
        assert_operator length, :<=, limit, "#{locale}/#{file} is #{length} characters, limit #{limit}"
      end
    end
  end

  def test_urls_are_https
    LOCALES.each do |locale|
      %w[support_url.txt marketing_url.txt privacy_url.txt].each do |file|
        assert_match %r{\Ahttps://[^\s]+\z}, read(locale, file), "#{locale}/#{file} must be an https URL"
      end
    end
  end

  def test_the_locales_carry_the_same_fields
    assert_equal Dir.children(File.join(IOS, "en-US")).sort - ["review_information"],
                 Dir.children(File.join(IOS, "es-ES")).sort - ["review_information"]
  end

  def test_description_is_the_android_listing_copy_until_it_is_rewritten_for_ios
    LOCALES.each do |locale|
      assert_equal File.read(File.join(ANDROID, locale, "full_description.txt"), encoding: "UTF-8").strip,
                   read(locale, "description.txt"),
                   "#{locale} description.txt drifted from the Android copy it is taken from; update both or neither"
    end
  end

  def test_local_only_copy_makes_no_sync_co_watch_or_paid_claims
    LOCALES.each do |locale|
      copy = %w[name.txt subtitle.txt keywords.txt promotional_text.txt description.txt].map { |f| read(locale, f) }.join("\n")
      %w[sync co-watch cowatch subscription premium sincroniz suscripci].each do |word|
        refute_match(/#{word}/i, copy, "#{locale} copy mentions '#{word}', which is not in the local-only v1 release")
      end
    end
  end

  def test_review_contact_fields_are_placeholders_not_values
    REVIEW.each do |file|
      path = File.join(IOS, "review_information", file)
      assert File.file?(path), "review_information/#{file} is missing"
    end
    assert_equal PLACEHOLDER, File.read(File.join(IOS, "review_information", "first_name.txt")).strip
    assert_equal PLACEHOLDER, File.read(File.join(IOS, "review_information", "email_address.txt")).strip
  end
end

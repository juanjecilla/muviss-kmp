#!/usr/bin/env ruby
# Adds the `iosAppUITests` XCUITest bundle to app/iosApp/iosApp.xcodeproj and
# its Testables to the shared `iosApp` scheme. Run once and commit the result;
# re-running is a no-op because the target already exists.
#
#   gem install --user-install xcodeproj
#   ruby scripts/ios/add-ui-test-target.rb
#
# The smoke test itself lives in app/iosApp/iosAppUITests/. CI runs it through
# the `iosApp` scheme (ci.yml's `ios` job, `xcodebuild test`).
require 'xcodeproj'

ROOT = File.expand_path('../..', __dir__)
PROJECT_PATH = File.join(ROOT, 'app/iosApp/iosApp.xcodeproj')
TEST_NAME = 'iosAppUITests'
APP_NAME = 'iosApp'
TEST_SOURCES = ['MuvissSmokeUITests.swift'].freeze

project = Xcodeproj::Project.open(PROJECT_PATH)
if project.targets.any? { |t| t.name == TEST_NAME }
  puts "#{TEST_NAME} already exists in #{PROJECT_PATH}; nothing to do"
  exit 0
end

app = project.targets.find { |t| t.name == APP_NAME } or abort "no #{APP_NAME} target"

test = project.new_target(:ui_test_bundle, TEST_NAME, :ios, '18.0', nil, :swift)
test.add_dependency(app)

# Settings shared by Debug and Release, matching the app target's conventions.
test.build_configurations.each do |config|
  config.build_settings.merge!(
    'PRODUCT_BUNDLE_IDENTIFIER' => 'com.codingpit.muviss.uitests',
    'PRODUCT_NAME' => '$(TARGET_NAME)',
    'TEST_TARGET_NAME' => APP_NAME,
    'GENERATE_INFOPLIST_FILE' => 'YES',
    'IPHONEOS_DEPLOYMENT_TARGET' => '18.0',
    'SWIFT_VERSION' => '5.0',
    'TARGETED_DEVICE_FAMILY' => '1,2',
    'CODE_SIGN_STYLE' => 'Automatic',
    'DEVELOPMENT_TEAM' => '${TEAM_ID}',
    'ARCHS' => 'arm64',
    'CODE_SIGN_IDENTITY' => 'Apple Development',
    'SWIFT_EMIT_LOC_STRINGS' => 'NO'
  )
end

# A plain group (not a synchronized folder): the test sources are few and this
# keeps the script's output readable in review.
group = project.main_group.find_subpath(TEST_NAME, true)
group.set_source_tree('<group>')
group.set_path(TEST_NAME)
TEST_SOURCES.each do |name|
  ref = group.files.find { |f| f.path == name } || group.new_file(name)
  test.source_build_phase.add_file_reference(ref, true)
end

# UI-test bundles name the app they drive (TestTargetID), which Xcode needs
# before it will run them from a scheme.
project.root_object.attributes['TargetAttributes'] ||= {}
project.root_object.attributes['TargetAttributes'][test.uuid] = {
  'CreatedOnToolsVersion' => '16.2',
  'TestTargetID' => app.uuid
}

project.save

# Shared scheme: the test goes into Testables, so `xcodebuild test -scheme iosApp`
# runs it. Nothing else in the scheme changes.
scheme_path = File.join(PROJECT_PATH, 'xcshareddata/xcschemes/iosApp.xcscheme')
scheme = Xcodeproj::XCScheme.new(scheme_path)
scheme.add_test_target(test) unless scheme.test_action.testables.any? { |t| t.buildable_references.any? { |r| r.target_name == TEST_NAME } }
scheme.save!

puts "added #{TEST_NAME} (#{test.uuid}) and its Testables entry to the iosApp scheme"

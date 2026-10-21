# Every `runs-on:` names a specific runner image (#481). `ubuntu-latest` and
# `macos-latest` float: GitHub moved `ubuntu-latest` to Ubuntu 26 on a date this
# repo did not choose, and the lane that breaks is an unrelated PR's. Actions are
# pinned to SHAs for the same reason; the image is the one input left that was not.
# A matrix expression (`${{ matrix.os }}`) is followed to the matrix's own values.
#
#   ruby scripts/ci/check-runner-pins.rb [ROOT]    exit 1 and list offenders
require "yaml"
require "date"

FLOATING = /(\A|-)(latest|slim)\z/

def labels(value, matrix)
  Array(value).flat_map do |label|
    next [label] unless label.is_a?(String) && label =~ /\A\$\{\{\s*matrix\.(\w+)\s*\}\}\z/

    matrix.fetch($1, ["<unresolved #{label}>"])
  end
end

def floating_runners(root)
  Dir[File.join(root, ".github/workflows/*.{yml,yaml}")].sort.flat_map do |file|
    doc = YAML.safe_load(File.read(file), permitted_classes: [Symbol, Date, Time], aliases: true)
    name = file.delete_prefix("#{File.expand_path(root)}/")
    ((doc.is_a?(Hash) && doc["jobs"]) || {}).flat_map do |job, spec|
      next [] unless spec.is_a?(Hash) && spec.key?("runs-on")

      matrix = {}
      (spec.dig("strategy", "matrix", "include") || []).each do |row|
        row.each { |k, v| (matrix[k] ||= []) << v }
      end
      labels(spec["runs-on"], matrix).select { |l| !l.is_a?(String) || l.match?(FLOATING) || l.include?("${{") || l.start_with?("<unresolved") }
                                     .map { |l| [name, job, l] }
    end
  end
end

if $PROGRAM_NAME == __FILE__
  bad = floating_runners(ARGV.fetch(0, "."))
  if bad.empty?
    puts "every runner image is pinned"
  else
    puts "::error::pin these runners to a specific image (ubuntu-24.04, macos-15, windows-2025):"
    bad.each { |file, job, label| puts "  #{file} (#{job}): #{label}" }
    exit 1
  end
end

# Every workflow declares a top-level `permissions:` block (#482). Without one a
# workflow runs with the repository's default token, which is a setting and not
# part of the file: widen it, transfer the repo, or fork it, and every job that
# runs build scripts and third-party actions silently gains write access. A job
# that needs more than the top level says so in its own `permissions:`.
#
#   ruby scripts/ci/check-workflow-permissions.rb [ROOT]    exit 1 and list offenders
require "yaml"
require "date"

def without_permissions(root)
  Dir[File.join(root, ".github/workflows/*.{yml,yaml}")].sort.filter_map do |file|
    doc = YAML.safe_load(File.read(file), permitted_classes: [Symbol, Date, Time], aliases: true)
    file.delete_prefix("#{File.expand_path(root)}/") unless doc.is_a?(Hash) && doc.key?("permissions")
  end
end

if $PROGRAM_NAME == __FILE__
  bad = without_permissions(ARGV.fetch(0, ".")).map { |f| f.delete_prefix("./") }
  if bad.empty?
    puts "every workflow declares top-level permissions"
  else
    puts "::error::add a top-level `permissions:` (start from `contents: read`) to:"
    bad.each { |file| puts "  #{file}" }
    exit 1
  end
end

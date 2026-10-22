# Every `uses:` in the workflows (and any composite action) names a local path
# or a third-party action pinned to a full commit SHA, or a docker:// image
# pinned to its sha256 digest (#311). It reads the
# parsed YAML, so a comment can never make a moving tag pass: only the value of
# the `uses` key is looked at.
#
#   ruby scripts/ci/check-action-pins.rb [ROOT]    exit 1 and list offenders
require "yaml"
require "date"

PINNED = /\A[^@\s]+@[0-9a-f]{40}\z/
# A container image is pinned only by its digest: a tag can be re-pushed.
DOCKER_PINNED = %r{\Adocker://[^@\s]+@sha256:[0-9a-f]{64}\z}

def uses_in(node, path, out)
  case node
  when Hash
    node.each do |key, value|
      if key == "uses" && value.is_a?(String)
        out << [path, value]
      else
        uses_in(value, path, out)
      end
    end
  when Array
    node.each { |value| uses_in(value, path, out) }
  end
  out
end

# Local references (`./path`) are followed into that directory's action.yml,
# wherever it lives: GitHub allows a composite action anywhere in the repo, and
# its own `uses` must be pinned too. A local reusable workflow is a file in
# .github/workflows, which is scanned anyway.
def unpinned(root)
  queue = Dir[File.join(root, ".github/workflows/*.{yml,yaml}")] +
          Dir[File.join(root, ".github/actions/**/action.{yml,yaml}")]
  seen = {}
  bad = []
  until queue.empty?
    file = File.expand_path(queue.shift)
    next if seen[file]

    seen[file] = true
    uses_in(YAML.safe_load(File.read(file), permitted_classes: [Symbol, Date, Time], aliases: true), file.delete_prefix("#{File.expand_path(root)}/"), []).each do |path, ref|
      if ref.start_with?("./")
        dir = File.join(root, ref.delete_prefix("./").split("@").first)
        queue.concat(Dir[File.join(dir, "action.{yml,yaml}")]) if File.directory?(dir)
      elsif !(ref.match?(DOCKER_PINNED) || ref.match?(PINNED))
        bad << [path, ref]
      end
    end
  end
  bad.uniq.sort
end

if $PROGRAM_NAME == __FILE__
  bad = unpinned(ARGV.fetch(0, "."))
  if bad.empty?
    puts "every action is pinned to a commit SHA"
  else
    puts "::error::pin these actions to a full commit SHA (tag as a comment):"
    bad.each { |file, ref| puts "  #{file}: #{ref}" }
    exit 1
  end
end

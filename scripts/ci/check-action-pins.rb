# Every `uses:` in the workflows (and any composite action) names a local path
# or a third-party action pinned to a full commit SHA (#311). It reads the
# parsed YAML, so a comment can never make a moving tag pass: only the value of
# the `uses` key is looked at.
#
#   ruby scripts/ci/check-action-pins.rb [ROOT]    exit 1 and list offenders
require "yaml"

PINNED = /\A[^@\s]+@[0-9a-f]{40}\z/

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

def unpinned(root)
  files = Dir[File.join(root, ".github/workflows/*.{yml,yaml}")] +
          Dir[File.join(root, ".github/actions/**/action.{yml,yaml}")]
  files.sort.flat_map { |file| uses_in(YAML.load_file(file), file.delete_prefix("#{root}/"), []) }
       .reject { |_, ref| ref.start_with?("./") || ref.start_with?("docker://") || ref.match?(PINNED) }
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

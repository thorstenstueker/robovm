# prints duplicate mapping keys in a YAML file (Psych keeps the last one silently)
require 'psych'
ARGV.each do |f|
  doc = Psych.parse_file(f)
  dups = []
  walk = lambda do |node|
    if node.is_a?(Psych::Nodes::Mapping)
      seen = {}
      node.children.each_slice(2) do |k, v|
        key = k.respond_to?(:value) ? k.value : k.to_s
        dups << "#{f}: duplicate key #{key.inspect} at line #{k.start_line + 1} (first at line #{seen[key]})" if seen[key]
        seen[key] ||= k.start_line + 1
        walk.call(v)
      end
    elsif node.respond_to?(:children) && node.children
      node.children.each { |c| walk.call(c) }
    end
  end
  walk.call(doc)
  puts(dups.empty? ? "#{f}: ok" : dups)
end

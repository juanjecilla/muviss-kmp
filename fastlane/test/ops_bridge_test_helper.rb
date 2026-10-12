require "json"
require "minitest/autorun"
require "stringio"
require_relative "../ops_bridge"

# A scripted stand-in for OpsBridge::Http: answers from a table keyed by [method, url prefix]
# (first match wins), records every request.
class FakeHttp
  attr_reader :requests

  def initialize(responses)
    @responses = responses
    @requests = []
  end

  def request(method, url, headers: {}, body: nil)
    @requests << { method: method, url: url, headers: headers, body: body }
    key = @responses.keys.find { |k| k[0] == method && url.start_with?(k[1]) }
    raise "unscripted request: #{method} #{url}" unless key

    status, payload, response_headers = @responses.fetch(key)
    [status, payload.is_a?(String) ? payload : JSON.generate(payload), response_headers || {}]
  end
end

# A connector that is only a list, for testing the bridge on its own.
class StubConnector
  attr_reader :resolved

  def initialize(items, resolvable: true)
    @items = items
    @resolved = []
    @resolvable = resolvable
  end

  def name
    "stub"
  end

  def kind
    "stub"
  end

  def source_label
    "from-stub"
  end

  def labels
    ["from-stub"]
  end

  def items
    @items
  end

  def respond_to_missing?(method, include_private = false)
    method == :resolve ? @resolvable : super
  end

  def respond_to?(method, include_all = false)
    method == :resolve ? @resolvable : super
  end

  def resolve(id)
    @resolved << id
  end
end

class StubSink
  attr_reader :created, :labelled

  def initialize(known)
    @known = known
    @created = []
    @labelled = []
  end

  def known_ids(_connector)
    @known
  end

  def ensure_labels(labels)
    @labelled << labels
  end

  def create(item)
    @created << item.id
    "https://github.com/o/r/issues/#{@created.size}"
  end
end

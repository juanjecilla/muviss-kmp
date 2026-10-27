require_relative "../bridge"

module OpsBridge
  module Connectors
    # Tally Feature Request form -> GitHub issue (EPIC 53 follow-up, #448; ADR 0035, ADR 0028).
    # Each completed submission of the form behind Settings > "Request a feature" becomes one
    # issue, so a request lands where the work is planned instead of only in the owner's inbox.
    #
    # Polling, not a webhook: Tally's webhook cannot be pointed at GitHub's dispatch API (it sends
    # its own fixed JSON), so it would need a hosted receiver — which ADR 0028 rules out until a
    # connector needs sub-hourly latency. Tally keeps every submission, so an hourly poll loses
    # nothing and needs no signing secret, no public endpoint and no token beyond TALLY_API_KEY.
    #
    # The repository is public, and what a user types is untrusted text:
    #   * **The respondent's email is never copied.** The form asks for one so the owner can reply
    #     from the Tally dashboard; the issue only says whether one was left.
    #   * The text goes in a code fence, so an `@name` cannot ping anyone and markdown or HTML in
    #     it cannot rewrite the issue; a fence inside the text is defused.
    #   * An empty submission (a click on Submit) is skipped, and one run opens at most
    #     MAX_PER_RUN issues, so a flood costs the owner an hour at a time, not a page of spam.
    #
    # The same code serves the Bug Report form (#449): a subclass only says what its issues are
    # called and which labels they carry; the privacy rules above hold for both.
    class TallyFeatureRequests
      # What differs between the forms: the marker kind, labels, title prefix, connector name and
      # the wording of the issue body.
      Profile = Struct.new(:name, :kind, :source_label, :labels, :title_prefix, :intro, :request_heading, :why_heading, :form_id_env)

      PROFILE = Profile.new(
        "tally-feature-requests",
        "tally-feature-request",
        "from-feature-request",
        ["from-feature-request", "user-feedback"].freeze,
        "[Feature request]",
        "A feature request from the in-app form, opened by the Ops Bridge. The respondent's email, if they left one, is deliberately not copied.",
        "What should Muviss do?",
        "Why would it help?",
        "TALLY_FEATURE_REQUEST_FORM_ID",
      ).freeze
      KIND = PROFILE.kind
      SOURCE_LABEL = PROFILE.source_label
      LABELS = PROFILE.labels
      API = "https://api.tally.so".freeze

      SNIPPET = 70
      TEXT_LIMIT = 4000
      MAX_PER_RUN = 10
      # The hidden fields the app sends (FeatureRequestContext). Anything else in the answer is ignored.
      CONTEXT_FIELDS = %w[version platform os locale].freeze
      TEXT_TYPES = %w[TEXTAREA INPUT_TEXT].freeze

      # [submission] is one entry of Tally's `submissions`, [questions] its `questions` (in form
      # order): the request is the answer to the form's first text question, the "why" to its
      # second, the email the INPUT_EMAIL, the context the HIDDEN_FIELDS answer. Text answers are
      # matched to their question by id, never by where they sit in `responses`: a "why" listed
      # first must not become the issue title.
      def self.to_item(submission, questions)
        return nil unless submission["isCompleted"]

        answers = answers_by_type(submission, questions)
        request = answers[:text].first.to_s.strip
        return nil if request.empty?

        id = submission.fetch("id")
        profile = self::PROFILE
        Item.new(id, OpsBridge.title(profile.title_prefix, headline(request)), render_body(id, submission, answers, request), profile.labels, nil)
      end

      def self.answers_by_type(submission, questions)
        type_of = questions.to_h { |question| [question["id"], question["type"]] }
        text_ids = questions.select { |question| TEXT_TYPES.include?(question["type"]) }.map { |question| question["id"] }
        text_by_id = {}
        email = nil
        context = {}
        Array(submission["responses"]).each do |response|
          answer = response["answer"]
          case type_of[response["questionId"]]
          when *TEXT_TYPES then text_by_id[response["questionId"]] = answer.to_s
          when "INPUT_EMAIL" then email = answer
          when "HIDDEN_FIELDS" then context = answer.is_a?(Hash) ? answer : {}
          end
        end
        { text: text_ids.map { |id| text_by_id[id].to_s }, email: email, context: context }
      end

      def self.headline(request)
        request.lines.first.to_s.strip.slice(0, SNIPPET)
      end

      def self.render_body(id, submission, answers, request)
        profile = self::PROFILE
        why = answers[:text][1].to_s.strip
        rows = CONTEXT_FIELDS.map { |field| [field, answers[:context][field]] }.reject { |_, value| value.to_s.strip.empty? }
        lines = [profile.intro, ""]
        lines += rows.map { |field, value| "- **#{field}:** `#{sanitize(value)}`" }
        lines << "- **Submitted:** #{submission['submittedAt'].to_s[0, 10]}" unless submission["submittedAt"].to_s.empty?
        lines << "- **Can be replied to:** #{answers[:email].to_s.strip.empty? ? 'no' : 'yes, from the Tally dashboard'}"
        lines += ["", "**#{profile.request_heading}**", "", fence(request)]
        lines += ["", "**#{profile.why_heading}**", "", fence(why)] unless why.empty?
        lines += ["", OpsBridge.marker(profile.kind, id)]
        lines.join("\n")
      end

      # A hidden field is a query parameter anyone can set, so it is not trusted either.
      def self.sanitize(value)
        value.to_s.gsub(/[^A-Za-z0-9 ._+-]/, "?").slice(0, 40)
      end

      # A fence inside the text would close ours early.
      def self.fence(text)
        ["```text", text.gsub("```", "'''").slice(0, TEXT_LIMIT), "```"].join("\n")
      end

      def initialize(form_id:, token:, http: Http.new)
        env_name = self.class::PROFILE.form_id_env
        raise ApiError, "#{env_name} or TALLY_API_KEY is not set" if form_id.to_s.strip.empty? || token.to_s.strip.empty?

        @form_id = form_id.to_s.strip
        @token = token
        @http = http
      end

      def name
        self.class::PROFILE.name
      end

      def kind
        self.class::PROFILE.kind
      end

      def source_label
        self.class::PROFILE.source_label
      end

      def labels
        self.class::PROFILE.labels
      end

      # How many issues one run may open (the bridge applies it after it drops what already has one).
      def max_new
        MAX_PER_RUN
      end

      # Oldest first, so a capped run catches up in the order requests came in.
      def items
        found = []
        page = 1
        loop do
          json = get(page)
          questions = Array(json["questions"])
          Array(json["submissions"]).each do |submission|
            item = self.class.to_item(submission, questions)
            found << [submission["submittedAt"].to_s, item] if item
          end
          break unless json["hasMore"]

          page += 1
        end
        found.sort_by(&:first).map(&:last)
      end

      private

      def get(page)
        url = "#{API}/forms/#{URI.encode_www_form_component(@form_id)}/submissions?filter=completed&page=#{page}"
        status, body, = @http.request(:get, url, headers: { "Authorization" => "Bearer #{@token}" })
        raise ApiError, "Tally answered HTTP #{status} listing submissions" unless (200..299).cover?(status)

        JSON.parse(body)
      rescue JSON::ParserError
        raise ApiError, "Tally answered something that is not JSON"
      end
    end

    # Tally Bug Report form -> GitHub issue (#449): the same polling, the same privacy rules
    # (email never copied, text fenced, hidden fields sanitised, capped per run), different
    # labels so the owner can triage a problem apart from a wish.
    class TallyBugReports < TallyFeatureRequests
      PROFILE = Profile.new(
        "tally-bug-reports",
        "tally-bug-report",
        "from-bug-report",
        ["from-bug-report", "bug", "user-feedback"].freeze,
        "[Bug report]",
        "A bug report from the in-app form, opened by the Ops Bridge. The respondent's email, if they left one, is deliberately not copied.",
        "What happened?",
        "What did you expect?",
        "TALLY_BUG_REPORT_FORM_ID",
      ).freeze
    end
  end
end

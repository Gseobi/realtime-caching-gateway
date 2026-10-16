package com.github.gseobi.assistant.contract;

import java.util.Objects;

/** An explicit no-answer result prevents empty retrieval from masquerading as a grounded answer. */
public sealed interface AssistantResult {
    record Grounded(ModelInference.Reply reply) implements AssistantResult {
        public Grounded {
            Objects.requireNonNull(reply, "reply");
            if (reply.citations().isEmpty()) throw new IllegalArgumentException("grounded answer requires citations");
        }
    }
    record Abstained(Reason reason) implements AssistantResult {
        public Abstained { Objects.requireNonNull(reason, "reason"); }
    }
    enum Reason { NO_AUTHORIZED_EVIDENCE, DEADLINE_EXCEEDED, MODEL_UNAVAILABLE }
}

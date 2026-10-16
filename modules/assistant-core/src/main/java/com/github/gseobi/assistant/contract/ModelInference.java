package com.github.gseobi.assistant.contract;

import com.github.gseobi.foundation.identity.RequestContext;
import com.github.gseobi.knowledge.contract.EvidenceChunk;
import com.github.gseobi.knowledge.contract.SourceCitation;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** No provider is selected. A self-hosted adapter must enforce deadline/cancellation and concurrency/token limits. */
public interface ModelInference {
    Reply generate(Request request);

    /** Keep trusted instructions, user input and untrusted retrieval evidence separate in the adapter. */
    record Request(RequestContext context, String instructions, String question, List<EvidenceChunk> evidence,
                   String modelRevision, int maxOutputTokens, Instant deadline) {
        public Request {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(deadline, "deadline");
            evidence = List.copyOf(evidence);
            for (EvidenceChunk chunk : evidence) context.requireTenant(chunk.document().tenantId());
            if (instructions == null || instructions.isBlank() || question == null || question.isBlank())
                throw new IllegalArgumentException("instructions and question are required");
            if (modelRevision == null || modelRevision.isBlank() || maxOutputTokens < 1)
                throw new IllegalArgumentException("model revision and positive token budget are required");
        }
    }

    /** Provider output is untrusted until its source references and model revision are checked. */
    record Reply(String answer, String modelRevision, List<SourceCitation> citations) {
        public Reply {
            if (answer == null || answer.isBlank() || modelRevision == null || modelRevision.isBlank())
                throw new IllegalArgumentException("answer and actual model revision are required");
            citations = List.copyOf(citations);
        }
    }
}

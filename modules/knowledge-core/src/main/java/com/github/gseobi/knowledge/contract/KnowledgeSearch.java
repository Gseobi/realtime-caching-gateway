package com.github.gseobi.knowledge.contract;

import com.github.gseobi.foundation.identity.RequestContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public interface KnowledgeSearch {
    /** Require tenant prefilter and authoritative current ACL/version/tombstone checks, including cache hits.
     * Return bounded authorized evidence only; a vector index's ACL metadata alone is not authoritative. */
    List<EvidenceChunk> search(Query query);

    record Query(RequestContext context, String question, int limit, IndexProfile index, Instant deadline) {
        public Query {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(index, "index");
            Objects.requireNonNull(deadline, "deadline");
            if (question == null || question.isBlank()) throw new IllegalArgumentException("question is required");
            if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }
}

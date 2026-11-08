package com.github.gseobi.knowledge.contract;

import com.github.gseobi.foundation.identity.RequestContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Text ingestion and lexical evidence; vector and remote-source adapters have separate contracts. */
public interface LocalKnowledge {
    Job create(RequestContext context, Document input);
    Job replace(RequestContext context, UUID id, long expectedRevision, Document input);
    Job rebuild(RequestContext context, UUID id, long expectedRevision);
    Job delete(RequestContext context, UUID id, long expectedRevision);
    Optional<Job> job(RequestContext context, UUID jobId);
    List<Hit> search(RequestContext context, String query, int limit);

    record Document(String source, String text, Set<String> readers) {
        public Document { readers = Set.copyOf(readers); }
    }
    record Job(UUID jobId, UUID documentId, long revision, String status) {}
    record Hit(UUID documentId, long revision, int chunk, String source, String text) {}
}

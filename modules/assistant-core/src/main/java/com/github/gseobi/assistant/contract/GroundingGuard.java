package com.github.gseobi.assistant.contract;

/** Structural citation check only: it cannot establish semantic truth, current ACLs or prompt-injection immunity. */
public final class GroundingGuard {
    private GroundingGuard() { }

    public static void validate(ModelInference.Request request, ModelInference.Reply reply) {
        if (!request.modelRevision().equals(reply.modelRevision()))
            throw new IllegalStateException("unexpected model revision");
        if (reply.citations().isEmpty()) throw new IllegalStateException("grounded answer requires citations");
        for (var citation : reply.citations()) {
            request.context().requireTenant(citation.document().tenantId());
            if (!request.evidence().stream().anyMatch(chunk ->
                    chunk.document().equals(citation.document()) && chunk.chunkId().equals(citation.chunkId())
                    && chunk.index().equals(citation.index())))
                throw new IllegalStateException("citation was not supplied as authorized evidence");
        }
    }
}

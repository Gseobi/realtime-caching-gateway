package com.github.gseobi.assistant.contract;

import com.github.gseobi.foundation.identity.RequestContext;
import com.github.gseobi.foundation.identity.TenantId;
import com.github.gseobi.knowledge.contract.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GroundingGuardTest {
    private final TenantId tenant = new TenantId("test-tenant");
    private final RequestContext context = new RequestContext(tenant, "test-principal", "test-request");
    private final DocumentRef document = new DocumentRef(tenant, "doc-1", 2, 3);
    private final IndexProfile index = new IndexProfile(new EmbeddingProfile("embed@v1", 2), "chunker@v1");
    private final EvidenceChunk evidence = new EvidenceChunk(document, "chunk-1", index, "Synthetic evidence");

    private ModelInference.Request request(List<EvidenceChunk> chunks) {
        return new ModelInference.Request(context, "Answer from supplied sources", "Question", chunks,
                "local-model@revision-1", 100, Instant.parse("2030-01-01T00:00:00Z"));
    }
    private ModelInference.Reply reply(SourceCitation citation) {
        return new ModelInference.Reply("Answer", "local-model@revision-1", List.of(citation));
    }
    @Test void acceptsOnlySuppliedSourceIdentity() {
        assertDoesNotThrow(() -> GroundingGuard.validate(request(List.of(evidence)),
                reply(new SourceCitation(document, "chunk-1", index))));
    }
    @Test void rejectsStaleContentGeneration() {
        assertThrows(IllegalStateException.class, () -> GroundingGuard.validate(request(List.of(evidence)),
                reply(new SourceCitation(new DocumentRef(tenant, "doc-1", 1, 3), "chunk-1", index))));
    }
    @Test void rejectsStaleAclRevision() {
        assertThrows(IllegalStateException.class, () -> GroundingGuard.validate(request(List.of(evidence)),
                reply(new SourceCitation(new DocumentRef(tenant, "doc-1", 2, 2), "chunk-1", index))));
    }
    @Test void rejectsForeignTenantEvidenceBeforeInference() {
        var foreign = new EvidenceChunk(new DocumentRef(new TenantId("other"), "doc-1", 2, 3), "chunk-1", index, "text");
        assertThrows(SecurityException.class, () -> request(List.of(foreign)));
    }
    @Test void rejectsForeignTenantCitation() {
        assertThrows(SecurityException.class, () -> GroundingGuard.validate(request(List.of(evidence)),
                reply(new SourceCitation(new DocumentRef(new TenantId("other"), "doc-1", 2, 3), "chunk-1", index))));
    }
    @Test void rejectsModelRevisionDrift() {
        var result = new ModelInference.Reply("Answer", "other-model", List.of(new SourceCitation(document, "chunk-1", index)));
        assertThrows(IllegalStateException.class, () -> GroundingGuard.validate(request(List.of(evidence)), result));
    }
    @Test void emptyRetrievalCannotProduceGroundedAnswer() {
        assertThrows(IllegalStateException.class, () -> GroundingGuard.validate(request(List.of()),
                reply(new SourceCitation(document, "chunk-1", index))));
        assertThrows(IllegalArgumentException.class, () -> new AssistantResult.Grounded(
                new ModelInference.Reply("Unsupported answer", "local-model@revision-1", List.of())));
        assertDoesNotThrow(() -> new AssistantResult.Abstained(AssistantResult.Reason.NO_AUTHORIZED_EVIDENCE));
    }
    @Test void rejectsChunkingRevisionDrift() {
        var changed = new IndexProfile(index.embedding(), "chunker@v2");
        assertThrows(IllegalStateException.class, () -> GroundingGuard.validate(request(List.of(evidence)),
                reply(new SourceCitation(document, "chunk-1", changed))));
    }
    @Test void promptEvidenceCannotBeMutatedAfterValidation() {
        var chunks = new ArrayList<>(List.of(evidence));
        var request = request(chunks);
        chunks.clear();
        assertEquals(1, request.evidence().size());
        assertThrows(UnsupportedOperationException.class, () -> request.evidence().clear());
    }
}

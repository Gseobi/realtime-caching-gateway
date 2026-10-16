package com.github.gseobi.knowledge.contract;

import java.util.Objects;

/** Text is untrusted source data, never system instructions or tool authorization. */
public record EvidenceChunk(DocumentRef document, String chunkId, IndexProfile index, String text) {
    public EvidenceChunk {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(index, "index");
        if (chunkId == null || chunkId.isBlank()) throw new IllegalArgumentException("chunkId is required");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text is required");
    }
}

package com.github.gseobi.knowledge.contract;

import java.util.Objects;

/** Rebuild/index namespace identity includes embedding AND chunking/preprocessing revision. */
public record IndexProfile(EmbeddingProfile embedding, String chunkingRevision) {
    public IndexProfile {
        Objects.requireNonNull(embedding, "embedding");
        if (chunkingRevision == null || chunkingRevision.isBlank())
            throw new IllegalArgumentException("chunking revision is required");
    }
}

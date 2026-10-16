package com.github.gseobi.knowledge.contract;

import java.util.Objects;

/** Reference only; the model must never supply replacement source text. */
public record SourceCitation(DocumentRef document, String chunkId, IndexProfile index) {
    public SourceCitation {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(index, "index");
        if (chunkId == null || chunkId.isBlank()) throw new IllegalArgumentException("chunkId is required");
    }
}

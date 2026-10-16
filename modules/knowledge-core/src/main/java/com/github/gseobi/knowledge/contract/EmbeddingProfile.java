package com.github.gseobi.knowledge.contract;

/** Index compatibility key. Model identity includes revision; equal dimensions alone are insufficient. */
public record EmbeddingProfile(String modelRevision, int dimensions) {
    public EmbeddingProfile {
        if (modelRevision == null || modelRevision.isBlank()) throw new IllegalArgumentException("model revision is required");
        if (dimensions < 1) throw new IllegalArgumentException("dimensions must be positive");
    }
}

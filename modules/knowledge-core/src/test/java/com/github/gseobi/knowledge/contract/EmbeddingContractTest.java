package com.github.gseobi.knowledge.contract;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingContractTest {
    @Test void rejectsInvalidVectorsBeforeIndexing() {
        var profile = new EmbeddingProfile("embedding@v1", 2);
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingModel.Vector(profile, List.of(1f)));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingModel.Vector(profile, List.of(1f, Float.NaN)));
    }
    @Test void equalDimensionsDoNotMeanCompatibleEmbeddingSpace() {
        assertNotEquals(new EmbeddingProfile("embedding@v1", 2), new EmbeddingProfile("embedding@v2", 2));
    }
    @Test void changedChunkingRequiresDifferentIndexIdentity() {
        var embedding = new EmbeddingProfile("embedding@v1", 2);
        assertNotEquals(new IndexProfile(embedding, "chunker@v1"), new IndexProfile(embedding, "chunker@v2"));
    }
    @Test void vectorsAreImmutableAcrossAsyncBoundaries() {
        var values = new ArrayList<>(List.of(1f, 2f));
        var vector = new EmbeddingModel.Vector(new EmbeddingProfile("embedding@v1", 2), values);
        values.set(0, 99f);
        assertEquals(1f, vector.values().getFirst());
    }
}

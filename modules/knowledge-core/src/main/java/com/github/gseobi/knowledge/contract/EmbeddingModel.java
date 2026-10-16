package com.github.gseobi.knowledge.contract;

import com.github.gseobi.foundation.identity.RequestContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Adapter must bound input, concurrency and execution time and enforce tenant data handling policy. */
public interface EmbeddingModel {
    Vector embed(RequestContext context, String text, EmbeddingProfile profile, Instant deadline);

    record Vector(EmbeddingProfile profile, List<Float> values) {
        public Vector {
            Objects.requireNonNull(profile, "profile");
            values = List.copyOf(values);
            if (values.size() != profile.dimensions() || values.stream().anyMatch(v -> !Float.isFinite(v)))
                throw new IllegalArgumentException("embedding dimensions/values do not match profile");
        }
    }
}

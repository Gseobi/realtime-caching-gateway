package com.github.gseobi.crm.contract;

import java.time.LocalDateTime;
import java.util.Objects;

/** Exclusive tuple boundary matching the existing timestamp-without-timezone API.
 * A future HTTP adapter must authenticate/scope the cursor and validate direction/limit. */
public record MessageCursor(LocalDateTime sentAt, String messageId) {
    public MessageCursor {
        Objects.requireNonNull(sentAt, "sentAt");
        if (messageId == null || messageId.isBlank()) throw new IllegalArgumentException("messageId is required");
    }
}

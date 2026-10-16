package com.github.gseobi.cachinggateway.domain.message.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
public class MessageQueryRequest {
    private LocalDateTime before;
    private LocalDateTime after;
    @jakarta.validation.constraints.Min(1)
    @jakarta.validation.constraints.Max(200)
    private Integer limit = 50;

    @jakarta.validation.constraints.AssertTrue(message = "after must precede before")
    public boolean isValidRange() {
        return before == null || after == null || after.isBefore(before);
    }
}

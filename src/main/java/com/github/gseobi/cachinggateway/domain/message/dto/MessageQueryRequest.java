package com.github.gseobi.cachinggateway.domain.message.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
public class MessageQueryRequest {
    private LocalDateTime before;
    private LocalDateTime after;
    private Integer limit = 50;
}

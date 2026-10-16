package com.github.gseobi.cachinggateway.domain.message.dto;

import com.github.gseobi.cachinggateway.domain.message.model.MessageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
public class MessageSaveRequest {

    @NotBlank(message = "'messageId' is required")
    private String messageId;
    private String conversationId;
    @NotBlank(message = "'senderId' is required")
    private String senderId;
    @NotNull(message = "'messageType' is required")
    private MessageType messageType;

    private String content;
    private String metadataJson;
    private LocalDateTime sentAt;
}

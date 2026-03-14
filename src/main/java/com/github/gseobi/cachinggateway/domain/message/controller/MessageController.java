package com.github.gseobi.cachinggateway.domain.message.controller;

import com.github.gseobi.cachinggateway.domain.message.dto.MessageQueryRequest;
import com.github.gseobi.cachinggateway.domain.message.dto.MessageResponse;
import com.github.gseobi.cachinggateway.domain.message.dto.MessageSaveRequest;
import com.github.gseobi.cachinggateway.domain.message.service.MessageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/api/conversations/{conversationId}/messages")
public class MessageController {

    private final MessageService messageService;

    @PostMapping
    public void saveMessage(@NotBlank @PathVariable("conversationId") String conversationId,
                            @Valid @RequestBody MessageSaveRequest messageSaveRequest) {
        messageSaveRequest.setConversationId(conversationId);
        this.messageService.saveMessage(messageSaveRequest);
    }

    @GetMapping
    public List<MessageResponse> getMessages(@PathVariable("conversationId") String conversationId,
                                             @ModelAttribute MessageQueryRequest messageQueryRequest) {
        return this.messageService.getMessages(conversationId, messageQueryRequest);
    }
}

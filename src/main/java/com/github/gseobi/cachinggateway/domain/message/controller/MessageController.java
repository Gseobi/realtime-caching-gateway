package com.github.gseobi.cachinggateway.domain.message.controller;

import com.github.gseobi.cachinggateway.domain.message.dto.MessageQueryRequest;
import com.github.gseobi.cachinggateway.domain.message.dto.MessageSaveRequest;
import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;
import com.github.gseobi.cachinggateway.domain.message.service.MessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/api/conversations/{conversationId}/messages")
public class MessageController {

    private final MessageService messageService;

    @PostMapping
    public void saveMessage(@PathVariable("conversationId") String conversationId,
                            @Valid @RequestBody MessageSaveRequest messageSaveRequest) {
        messageSaveRequest.setConversationId(conversationId);
        this.messageService.saveMessage(messageSaveRequest);
    }

    @GetMapping
    public List<MessageCacheData> getMessages(@PathVariable("conversationId") String conversationId,
                                              @ModelAttribute MessageQueryRequest messageQueryRequest) {
        return this.messageService.getMessages(conversationId, messageQueryRequest);
    }
}

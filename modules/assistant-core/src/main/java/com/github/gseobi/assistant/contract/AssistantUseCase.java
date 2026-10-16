package com.github.gseobi.assistant.contract;

import com.github.gseobi.foundation.identity.RequestContext;
import java.time.Instant;

/** Future orchestrator: authorize conversation, retrieve authorized evidence, bound inference and validate sources.
 * Recheck authorization/deletion before response; no evidence produces explicit abstention, not invented sources.
 * No DB transaction may remain open during retrieval or inference. No CRM tool execution is implied. */
public interface AssistantUseCase {
    AssistantResult answer(RequestContext context, String conversationId, String question, Instant deadline);
}

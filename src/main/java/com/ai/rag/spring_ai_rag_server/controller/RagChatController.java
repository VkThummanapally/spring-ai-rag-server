package com.ai.rag.spring_ai_rag_server.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rag")
public class RagChatController {

    private static final Logger log = LoggerFactory.getLogger(RagChatController.class);

    private final ChatClient chatClient;

    public RagChatController(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        log.info("Initializing RagChatController with QuestionAnswerAdvisor...");
        this.chatClient = chatClientBuilder
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore).build())
                .build();
        log.info("RagChatController initialized successfully.");
    }

    @PostMapping("/chat")
    public String chat(@RequestBody String message) {
        log.info("Received chat request with message: {}", message);
        long startTime = System.currentTimeMillis();
        try {
            String response = chatClient.prompt()
                    .user(message)
                    .call()
                    .content();
            long duration = System.currentTimeMillis() - startTime;
            log.info("Chat response generated in {} ms, response length: {} chars", duration, response != null ? response.length() : 0);
            return response;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Chat request failed after {} ms for message: '{}' - {}", duration, message, e.getMessage());
            throw e;
        }
    }
}

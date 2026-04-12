package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.common.dto.ChatRequestDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
final class ChatRequestValidator {

    void requireValid(ChatRequestDto request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        if (request.userId() == null || request.userId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        if (request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }
    }
}

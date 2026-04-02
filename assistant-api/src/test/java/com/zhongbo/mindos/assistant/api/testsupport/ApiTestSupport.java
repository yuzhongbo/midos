package com.zhongbo.mindos.assistant.api.testsupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

public final class ApiTestSupport {

    public static final String ADMIN_TOKEN_HEADER = "X-MindOS-Admin-Token";
    public static final String ADMIN_TOKEN = "test-admin-token";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private ApiTestSupport() {
    }

    public static String uniqueUserId(String prefix) {
        String normalized = prefix == null ? "test-user" : prefix.trim();
        if (normalized.isBlank()) {
            normalized = "test-user";
        }
        return normalized + "-" + UUID.randomUUID();
    }

    public static String readString(MvcResult result, String jsonPath) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
    }

    public static ResultMatcher channelIn(String... channels) {
        Set<String> expected = new LinkedHashSet<>(Arrays.asList(channels));
        return result -> {
            String actual = readString(result, "$.channel");
            assertTrue(expected.contains(actual),
                    () -> "Expected channel in " + expected + " but was: " + actual);
        };
    }

    public static String chatRequest(String userId, String message) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of(
                    "userId", userId == null ? "" : userId,
                    "message", message == null ? "" : message
            ));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to build chat request JSON", ex);
        }
    }

    public static MockHttpServletRequestBuilder withAdminToken(MockHttpServletRequestBuilder builder) {
        return builder.header(ADMIN_TOKEN_HEADER, ADMIN_TOKEN);
    }
}


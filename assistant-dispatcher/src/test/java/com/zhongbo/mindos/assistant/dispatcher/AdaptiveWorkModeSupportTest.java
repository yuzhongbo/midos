package com.zhongbo.mindos.assistant.dispatcher;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveWorkModeSupportTest {

    @Test
    void shouldInferDeveloperModeAndEnableDeveloperPackForExplicitCodeRequest() {
        Map<String, Object> context = AdaptiveWorkModeSupport.enrichProfileContext(
                Map.of("role", "personal-assistant"),
                "帮我修复 Spring Boot 登录接口空指针 bug"
        );

        assertEquals("adaptive-multi-domain", context.get("assistantMode"));
        assertEquals("single-persona-dynamic-modes", context.get("roleStrategy"));
        assertTrue(asList(context.get("workingModes")).contains("developer"));
        assertTrue(asList(context.get("capabilityPacks")).contains("developer"));
        assertTrue(asMap(context.get("workingModeConfidence")).containsKey("developer"));
        assertTrue(asDouble(asMap(context.get("workingModeConfidence")).get("developer")) >= 0.72d);
        assertEquals("developer", context.get("primaryWorkMode"));
    }

    @Test
    void shouldCarryDeveloperPackForTechnicalContinuationWithoutSwitchingRole() {
        Map<String, Object> context = AdaptiveWorkModeSupport.enrichProfileContext(
                Map.of(),
                "继续按之前方式",
                "当前事项：修复 Spring 接口 bug；下一步：继续修改 UserController 并补单测",
                List.of("当前事项：修复 Spring 接口 bug", "下一步：继续修改 UserController 并补单测")
        );

        assertTrue(asList(context.get("workingModes")).contains("developer"));
        assertTrue(asList(context.get("capabilityPacks")).contains("developer"));
        assertTrue(asDouble(asMap(context.get("workingModeConfidence")).get("developer")) >= 0.72d);
    }

    @Test
    void shouldInferBusinessModesAndIndustryFocus() {
        Map<String, Object> context = AdaptiveWorkModeSupport.enrichProfileContext(
                Map.of(),
                "给我一个医疗行业用户增长复盘方案"
        );

        assertTrue(asList(context.get("workingModes")).contains("planner"));
        assertTrue(asList(context.get("workingModes")).contains("analyst"));
        assertEquals("healthcare", context.get("industryFocus"));
        assertTrue(asDouble(context.get("industryFocusConfidence")) >= 0.30d);
    }

    @SuppressWarnings("unchecked")
    private List<String> asList(Object value) {
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0d;
    }
}

package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.common.LlmMetricsReader;
import com.zhongbo.mindos.assistant.common.dto.LlmMetricsResponseDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/metrics/llm")
public class LlmMetricsController {

    private final LlmMetricsReader llmMetricsReader;

    public LlmMetricsController(LlmMetricsReader llmMetricsReader) {
        this.llmMetricsReader = llmMetricsReader;
    }

    @GetMapping
    public LlmMetricsResponseDto getLlmMetrics(@RequestParam(defaultValue = "60") int windowMinutes,
                                               @RequestParam(required = false) String provider,
                                               @RequestParam(defaultValue = "false") boolean includeRecent,
                                               @RequestParam(defaultValue = "20") int recentLimit) {
        return llmMetricsReader.snapshot(windowMinutes, provider, includeRecent, recentLimit);
    }
}


package com.zhongbo.mindos.assistant.skill.cloudapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * JSON definition for a cloud REST API skill.
 *
 * Example file (weather-query.json):
 * <pre>
 * {
 *   "name": "weather.query",
 *   "description": "Queries current weather for a given city",
 *   "keywords": ["天气", "weather", "温度", "forecast", "查天气"],
 *   "url": "https://api.weatherstack.com/current",
 *   "method": "GET",
 *   "queryParams": {
 *     "access_key": "${apiKey}",
 *     "query": "${input.city}"
 *   },
 *   "apiKey": "your-api-key-here",
 *   "resultPath": "current",
 *   "resultTemplate": "天气：${weather_descriptions}, 温度：${temperature}°C, 湿度：${humidity}%"
 * }
 * </pre>
 *
 * Template placeholder rules (used in url, queryParams values, bodyTemplate, resultTemplate):
 * <ul>
 *   <li>{@code ${input}}           — full user input text</li>
 *   <li>{@code ${input.fieldName}} — context attribute by field name</li>
 *   <li>{@code ${apiKey}}          — definition's apiKey value</li>
 *   <li>{@code ${env.VAR_NAME}}    — system environment variable</li>
 * </ul>
 *
 * {@code resultPath} supports dot-notation traversal of the JSON response
 * (e.g., {@code "data.result"} extracts {@code response["data"]["result"]}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudApiSkillDefinition(
        String name,
        String description,
        List<String> keywords,
        String url,
        String method,
        Map<String, String> headers,
        Map<String, String> queryParams,
        String bodyTemplate,
        String apiKey,
        String apiKeyHeader,
        String resultPath,
        String resultTemplate
) {
    public CloudApiSkillDefinition {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        queryParams = queryParams == null ? Map.of() : Map.copyOf(queryParams);
        method = method == null || method.isBlank() ? "GET" : method.toUpperCase();
        apiKeyHeader = apiKeyHeader == null || apiKeyHeader.isBlank() ? "X-API-Key" : apiKeyHeader;
    }
}

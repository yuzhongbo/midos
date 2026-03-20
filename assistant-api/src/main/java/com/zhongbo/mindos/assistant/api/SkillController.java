package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import com.zhongbo.mindos.assistant.skill.loader.CustomSkillLoader;
import com.zhongbo.mindos.assistant.skill.loader.ExternalSkillLoader;
import com.zhongbo.mindos.assistant.skill.mcp.McpSkillLoader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for skill management.
 *
 * GET  /api/skills            — list all registered skills
 * POST /api/skills/reload     — hot-reload custom JSON skills from disk
 * POST /api/skills/load-jar   — download and register an external skill JAR on demand
 * POST /api/skills/reload-mcp — reload MCP skills from configured servers
 * POST /api/skills/load-mcp   — load one MCP server on demand
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRegistry skillRegistry;
    private final CustomSkillLoader customSkillLoader;
    private final ExternalSkillLoader externalSkillLoader;
    private final McpSkillLoader mcpSkillLoader;

    public SkillController(SkillRegistry skillRegistry,
                           CustomSkillLoader customSkillLoader,
                           ExternalSkillLoader externalSkillLoader,
                           McpSkillLoader mcpSkillLoader) {
        this.skillRegistry = skillRegistry;
        this.customSkillLoader = customSkillLoader;
        this.externalSkillLoader = externalSkillLoader;
        this.mcpSkillLoader = mcpSkillLoader;
    }

    /**
     * Lists all currently registered skills sorted by name.
     * <p>Example response:</p>
     * <pre>
     * [{"name":"echo","description":"Echoes back the text after the 'echo' command.","type":"builtin"}]
     * </pre>
     */
    @GetMapping
    public List<Map<String, String>> listSkills() {
        return skillRegistry.getAllSkills().stream()
                .map(s -> Map.of(
                        "name", s.name(),
                        "description", s.description() == null ? "" : s.description()
                ))
                .sorted(Comparator.comparing(m -> m.get("name")))
                .toList();
    }

    /**
     * Hot-reloads all JSON custom skill definitions from the configured directory.
     * Existing skills with the same name are overwritten.
     * <p>Request body: (none)</p>
     * <p>Response: {"reloaded": 3, "dir": "/path/to/custom-skills"}</p>
     */
    @PostMapping("/reload")
    public Map<String, Object> reloadCustomSkills() {
        int count = customSkillLoader.reload();
        return Map.of(
                "reloaded", count,
                "dir", customSkillLoader.getCustomSkillDir() == null ? "" : customSkillLoader.getCustomSkillDir(),
                "status", "ok"
        );
    }

    /**
     * Downloads and registers an external skill JAR on demand.
     * <p>Request body: {"url": "https://example.com/skill-weather.jar"}</p>
     * <p>Response: {"loaded": 2, "url": "...", "status": "ok"}</p>
     */
    @PostMapping("/load-jar")
    public Map<String, Object> loadExternalJar(@RequestBody Map<String, String> request) {
        String jarUrl = request == null ? null : request.get("url");
        if (jarUrl == null || jarUrl.isBlank()) {
            return Map.of("status", "error", "error", "Field 'url' is required.");
        }
        int count = externalSkillLoader.loadFromJar(jarUrl.trim());
        return Map.of(
                "loaded", count,
                "url", jarUrl.trim(),
                "status", count > 0 ? "ok" : "no_skills_found"
        );
    }

    @PostMapping("/reload-mcp")
    public Map<String, Object> reloadMcpSkills() {
        int count = mcpSkillLoader.reload();
        return Map.of(
                "reloaded", count,
                "servers", mcpSkillLoader.getConfiguredServers() == null ? "" : mcpSkillLoader.getConfiguredServers(),
                "status", "ok"
        );
    }

    @PostMapping("/load-mcp")
    public Map<String, Object> loadMcpServer(@RequestBody Map<String, Object> request) {
        String alias = request == null ? null : asTrimmedString(request.get("alias"));
        String url = request == null ? null : asTrimmedString(request.get("url"));
        if (alias == null || alias.isBlank()) {
            return Map.of("status", "error", "error", "Field 'alias' is required.");
        }
        if (url == null || url.isBlank()) {
            return Map.of("status", "error", "error", "Field 'url' is required.");
        }
        Map<String, String> headers = extractHeaders(request == null ? null : request.get("headers"));
        int count = mcpSkillLoader.loadServer(alias, url, headers);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("loaded", count);
        response.put("alias", alias);
        response.put("url", url);
        if (!headers.isEmpty()) {
            response.put("headersApplied", headers.size());
        }
        response.put("status", count > 0 ? "ok" : "no_tools_found");
        return Map.copyOf(response);
    }

    private String asTrimmedString(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        String value = String.valueOf(rawValue).trim();
        return value.isBlank() ? null : value;
    }

    private Map<String, String> extractHeaders(Object rawHeaders) {
        if (!(rawHeaders instanceof Map<?, ?> headerMap)) {
            return Map.of();
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : headerMap.entrySet()) {
            String key = asTrimmedString(entry.getKey());
            String value = asTrimmedString(entry.getValue());
            if (key == null || value == null) {
                continue;
            }
            parsed.put(key, value);
        }
        return parsed.isEmpty() ? Map.of() : Map.copyOf(parsed);
    }
}


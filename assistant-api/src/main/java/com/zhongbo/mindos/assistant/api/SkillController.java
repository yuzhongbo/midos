package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import com.zhongbo.mindos.assistant.skill.cloudapi.CloudApiSkillLoader;
import com.zhongbo.mindos.assistant.skill.loader.CustomSkillLoader;
import com.zhongbo.mindos.assistant.skill.loader.ExternalSkillLoader;
import com.zhongbo.mindos.assistant.skill.mcp.McpSkillLoader;
import com.zhongbo.mindos.assistant.skill.learning.GeneratedSkillDeployment;
import com.zhongbo.mindos.assistant.skill.learning.ToolGenerationRequest;
import com.zhongbo.mindos.assistant.skill.learning.ToolLearningService;
import jakarta.servlet.http.HttpServletRequest;
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
 * GET  /api/skills                 — list all registered skills
 * POST /api/skills/reload          — hot-reload custom JSON skills from disk
 * POST /api/skills/load-jar        — download and register an external skill JAR on demand
 * POST /api/skills/reload-mcp      — reload MCP skills from configured servers
 * POST /api/skills/load-mcp        — load one MCP server on demand
 * POST /api/skills/reload-cloud    — hot-reload cloud API skills from configured directory
 * POST /api/skills/generate        — generate and register a skill from a user request
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRegistry skillRegistry;
    private final CustomSkillLoader customSkillLoader;
    private final ExternalSkillLoader externalSkillLoader;
    private final McpSkillLoader mcpSkillLoader;
    private final CloudApiSkillLoader cloudApiSkillLoader;
    private final ToolLearningService toolLearningService;
    private final SecurityPolicyGuard securityPolicyGuard;
    private final SkillControllerRequestParser requestParser = new SkillControllerRequestParser();

    public SkillController(SkillRegistry skillRegistry,
                           CustomSkillLoader customSkillLoader,
                           ExternalSkillLoader externalSkillLoader,
                           McpSkillLoader mcpSkillLoader,
                           CloudApiSkillLoader cloudApiSkillLoader,
                           ToolLearningService toolLearningService,
                           SecurityPolicyGuard securityPolicyGuard) {
        this.skillRegistry = skillRegistry;
        this.customSkillLoader = customSkillLoader;
        this.externalSkillLoader = externalSkillLoader;
        this.mcpSkillLoader = mcpSkillLoader;
        this.cloudApiSkillLoader = cloudApiSkillLoader;
        this.toolLearningService = toolLearningService;
        this.securityPolicyGuard = securityPolicyGuard;
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
    public Map<String, Object> loadExternalJar(@RequestBody Map<String, String> request,
                                               HttpServletRequest servletRequest) {
        SkillControllerRequestParser.ValidationResult<SkillControllerRequestParser.LoadJarRequest> parsedRequest =
                requestParser.parseLoadJar(request);
        String jarUrl = parsedRequest.valid() ? parsedRequest.value().url() : null;
        securityPolicyGuard.verifyRiskyOperationApproval(
                servletRequest,
                "skills.load-jar",
                jarUrl == null ? "" : jarUrl.trim(),
                "system"
        );
        if (!parsedRequest.valid()) {
            return parsedRequest.errorResponse();
        }
        securityPolicyGuard.verifyExternalSkillUrl(jarUrl, true);
        int count = externalSkillLoader.loadFromJar(jarUrl);
        return Map.of(
                "loaded", count,
                "url", jarUrl,
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
    public Map<String, Object> loadMcpServer(@RequestBody Map<String, Object> request,
                                             HttpServletRequest servletRequest) {
        SkillControllerRequestParser.ValidationResult<SkillControllerRequestParser.LoadMcpRequest> parsedRequest =
                requestParser.parseLoadMcp(request);
        String alias = parsedRequest.valid() ? parsedRequest.value().alias() : null;
        String url = parsedRequest.valid() ? parsedRequest.value().url() : null;
        String resource = (alias == null ? "" : alias) + "@" + (url == null ? "" : url);
        securityPolicyGuard.verifyRiskyOperationApproval(
                servletRequest,
                "skills.load-mcp",
                resource,
                "system"
        );
        if (!parsedRequest.valid()) {
            return parsedRequest.errorResponse();
        }
        securityPolicyGuard.verifyExternalSkillUrl(url, false);
        Map<String, String> headers = parsedRequest.value().headers();
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

    /**
     * Hot-reloads all cloud API skill definitions from the configured directory.
     * <p>Request body: (none)</p>
     * <p>Response: {"reloaded": 2, "dir": "/path/to/cloud-skills", "status": "ok"}</p>
     */
    @PostMapping("/reload-cloud")
    public Map<String, Object> reloadCloudApiSkills() {
        int count = cloudApiSkillLoader.reload();
        return Map.of(
                "reloaded", count,
                "dir", cloudApiSkillLoader.getConfigDir() == null ? "" : cloudApiSkillLoader.getConfigDir(),
                "status", "ok"
        );
    }

    /**
     * Generates a new skill from the request, compiles it, and registers it dynamically.
     */
    @PostMapping("/generate")
    public Map<String, Object> generateSkill(@RequestBody Map<String, Object> request,
                                             HttpServletRequest servletRequest) {
        SkillControllerRequestParser.ValidationResult<SkillControllerRequestParser.GenerateSkillRequest> parsedRequest =
                requestParser.parseGenerate(request);
        String prompt = parsedRequest.valid() ? parsedRequest.value().prompt() : null;
        securityPolicyGuard.verifyRiskyOperationApproval(
                servletRequest,
                "skills.generate",
                prompt == null ? "" : prompt,
                "system"
        );
        if (!parsedRequest.valid()) {
            return parsedRequest.errorResponse();
        }
        SkillControllerRequestParser.GenerateSkillRequest validated = parsedRequest.value();
        GeneratedSkillDeployment deployment = toolLearningService.generateAndRegister(
                new ToolGenerationRequest(validated.userId(), validated.prompt(), validated.skillName(), validated.hints())
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("skillName", deployment.registeredSkillName());
        response.put("kind", deployment.artifact().kind().name());
        response.put("replaced", deployment.replaced());
        response.put("description", deployment.artifact().description());
        response.put("keywords", deployment.artifact().routingKeywords());
        response.put("packageName", deployment.artifact().packageName());
        response.put("className", deployment.artifact().className());
        response.put("source", deployment.artifact().sourceCode());
        response.put("rationale", deployment.artifact().rationale());
        return response;
    }
}

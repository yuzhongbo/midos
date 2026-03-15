package com.zhongbo.mindos.assistant.skill.mcp;

import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads MCP tools from configured HTTP JSON-RPC MCP servers and exposes them as Skills.
 *
 * Configuration format:
 *   mindos.skills.mcp-servers=docs:http://localhost:8081/mcp,search:https://example.com/mcp
 *
 * Registered skill names are namespaced as:
 *   mcp.<serverAlias>.<toolName>
 */
@Component
public class McpSkillLoader {

    private static final Logger LOGGER = Logger.getLogger(McpSkillLoader.class.getName());
    private static final String SKILL_PREFIX = "mcp.";

    private final SkillRegistry skillRegistry;
    private final McpJsonRpcClient mcpClient;
    private final String configuredServers;

    @Autowired
    public McpSkillLoader(SkillRegistry skillRegistry,
                          @Value("${mindos.skills.mcp-servers:}") String configuredServers) {
        this(skillRegistry, new McpJsonRpcClient(), configuredServers);
    }

    McpSkillLoader(SkillRegistry skillRegistry, McpJsonRpcClient mcpClient, String configuredServers) {
        this.skillRegistry = skillRegistry;
        this.mcpClient = mcpClient;
        this.configuredServers = configuredServers;
    }

    @PostConstruct
    public void loadOnStartup() {
        if (configuredServers == null || configuredServers.isBlank()) {
            return;
        }
        int count = reload();
        LOGGER.info("McpSkillLoader: loaded " + count + " MCP skill(s) from configured servers.");
    }

    public int reload() {
        skillRegistry.unregisterByPrefix(SKILL_PREFIX);
        return loadConfiguredServers();
    }

    public int loadConfiguredServers() {
        Map<String, String> servers = parseServerConfig(configuredServers);
        int total = 0;
        for (Map.Entry<String, String> entry : servers.entrySet()) {
            total += loadServer(entry.getKey(), entry.getValue());
        }
        return total;
    }

    public int loadServer(String alias, String serverUrl) {
        if (alias == null || alias.isBlank() || serverUrl == null || serverUrl.isBlank()) {
            return 0;
        }
        try {
            String normalizedAlias = normalizeAlias(alias);
            mcpClient.initialize(serverUrl);
            List<McpToolDefinition> tools = mcpClient.listTools(normalizedAlias, serverUrl);
            for (McpToolDefinition tool : tools) {
                skillRegistry.register(new McpToolSkill(tool, mcpClient));
                LOGGER.info("McpSkillLoader: registered MCP tool skill '" + tool.skillName() + "'");
            }
            return tools.size();
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING,
                    "McpSkillLoader: failed to load MCP server alias=" + alias + ", url=" + serverUrl, ex);
            return 0;
        }
    }

    public String getConfiguredServers() {
        return configuredServers;
    }

    Map<String, String> parseServerConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        String[] entries = raw.split(",");
        for (String entry : entries) {
            int separator = entry.indexOf(':');
            if (separator <= 0 || separator >= entry.length() - 1) {
                continue;
            }
            String alias = normalizeAlias(entry.substring(0, separator));
            String url = entry.substring(separator + 1).trim();
            if (!alias.isBlank() && !url.isBlank()) {
                parsed.put(alias, url);
            }
        }
        return Map.copyOf(parsed);
    }

    private String normalizeAlias(String alias) {
        String normalized = alias == null ? "" : alias.trim().toLowerCase();
        return normalized.replaceAll("[^a-z0-9._-]", "-");
    }
}


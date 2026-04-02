package com.zhongbo.mindos.assistant.skill.mcp;

import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
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
    private static final String PLACEHOLDER_PREFIX_REPLACE = "REPLACE_WITH_";
    private static final String PLACEHOLDER_PREFIX_YOUR = "YOUR_";

    private final SkillRegistry skillRegistry;
    private final McpJsonRpcClient mcpClient;
    private final String configuredServers;
    private final String configuredServerHeaders;
    private final boolean braveEnabled;
    private final String braveAlias;
    private final String braveUrl;
    private final String braveApiKey;
    private final String braveApiKeyHeader;

    @Autowired
    public McpSkillLoader(SkillRegistry skillRegistry,
                          @Value("${mindos.skills.mcp-servers:}") String configuredServers,
                          @Value("${mindos.skills.mcp-server-headers:}") String configuredServerHeaders,
                          @Value("${mindos.skills.mcp.brave.enabled:false}") boolean braveEnabled,
                          @Value("${mindos.skills.mcp.brave.alias:brave}") String braveAlias,
                          @Value("${mindos.skills.mcp.brave.url:}") String braveUrl,
                          @Value("${mindos.skills.mcp.brave.api-key:}") String braveApiKey,
                          @Value("${mindos.skills.mcp.brave.api-key-header:X-Subscription-Token}") String braveApiKeyHeader) {
        this(skillRegistry,
                new McpJsonRpcClient(),
                configuredServers,
                configuredServerHeaders,
                braveEnabled,
                braveAlias,
                braveUrl,
                braveApiKey,
                braveApiKeyHeader);
    }

    McpSkillLoader(SkillRegistry skillRegistry,
                   McpJsonRpcClient mcpClient,
                   String configuredServers,
                   String configuredServerHeaders) {
        this(skillRegistry,
                mcpClient,
                configuredServers,
                configuredServerHeaders,
                false,
                "brave",
                "",
                "",
                "X-Subscription-Token");
    }

    McpSkillLoader(SkillRegistry skillRegistry,
                   McpJsonRpcClient mcpClient,
                   String configuredServers,
                   String configuredServerHeaders,
                   boolean braveEnabled,
                   String braveAlias,
                   String braveUrl,
                   String braveApiKey,
                   String braveApiKeyHeader) {
        this.skillRegistry = skillRegistry;
        this.mcpClient = mcpClient;
        this.configuredServers = configuredServers;
        this.configuredServerHeaders = configuredServerHeaders;
        this.braveEnabled = braveEnabled;
        this.braveAlias = braveAlias;
        this.braveUrl = braveUrl;
        this.braveApiKey = braveApiKey;
        this.braveApiKeyHeader = braveApiKeyHeader;
    }

    @PostConstruct
    public void loadOnStartup() {
        if ((configuredServers == null || configuredServers.isBlank())
                && (!braveEnabled || braveUrl == null || braveUrl.isBlank())) {
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
        Map<String, String> servers = new LinkedHashMap<>(parseServerConfig(configuredServers));
        Map<String, Map<String, String>> headersByAlias = new LinkedHashMap<>(parseHeadersConfig(configuredServerHeaders));
        mergeBraveConfig(servers, headersByAlias);
        int total = 0;
        for (Map.Entry<String, String> entry : servers.entrySet()) {
            Map<String, String> headers = headersByAlias.getOrDefault(normalizeAlias(entry.getKey()), Map.of());
            total += loadServer(entry.getKey(), entry.getValue(), headers);
        }
        return total;
    }

    public int loadServer(String alias, String serverUrl) {
        return loadServer(alias, serverUrl, Map.of());
    }

    public int loadServer(String alias, String serverUrl, Map<String, String> headers) {
        if (alias == null || alias.isBlank() || serverUrl == null || serverUrl.isBlank()) {
            return 0;
        }
        try {
            String normalizedAlias = normalizeAlias(alias);
            Map<String, String> safeHeaders = headers == null ? Map.of() : Map.copyOf(headers);
            if (isBraveRestSearchEndpoint(normalizedAlias, serverUrl)) {
                McpToolDefinition braveTool = new McpToolDefinition(
                        normalizedAlias,
                        serverUrl,
                        "webSearch",
                        "Brave latest web news search",
                        safeHeaders
                );
                skillRegistry.register(new McpToolSkill(braveTool, new BraveSearchRestClient()));
                LOGGER.info("McpSkillLoader: registered Brave REST search skill '" + braveTool.skillName() + "'");
                return 1;
            }
            mcpClient.initialize(serverUrl, safeHeaders);
            List<McpToolDefinition> tools = mcpClient.listTools(normalizedAlias, serverUrl, safeHeaders);
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

    public String getConfiguredServerHeaders() {
        return configuredServerHeaders;
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
            if (!alias.isBlank() && !url.isBlank() && !isPlaceholder(url)) {
                parsed.put(alias, url);
            }
        }
        return Map.copyOf(parsed);
    }

    Map<String, Map<String, String>> parseHeadersConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, Map<String, String>> parsed = new LinkedHashMap<>();
        String[] entries = raw.split(",");
        for (String entry : entries) {
            int separator = entry.indexOf(':');
            if (separator <= 0 || separator >= entry.length() - 1) {
                continue;
            }
            String alias = normalizeAlias(entry.substring(0, separator));
            String headersPart = entry.substring(separator + 1);
            Map<String, String> headerMap = new LinkedHashMap<>();
            for (String headerEntry : headersPart.split(";")) {
                int eq = headerEntry.indexOf('=');
                if (eq <= 0 || eq >= headerEntry.length() - 1) {
                    continue;
                }
                String key = headerEntry.substring(0, eq).trim();
                String value = headerEntry.substring(eq + 1).trim();
                if (!key.isEmpty() && !value.isEmpty() && !isPlaceholder(value)) {
                    headerMap.put(key, value);
                }
            }
            if (!alias.isBlank() && !headerMap.isEmpty()) {
                parsed.put(alias, Map.copyOf(headerMap));
            }
        }
        return Map.copyOf(parsed);
    }

    private void mergeBraveConfig(Map<String, String> servers, Map<String, Map<String, String>> headersByAlias) {
        if (!braveEnabled) {
            return;
        }
        String alias = normalizeAlias(braveAlias == null || braveAlias.isBlank() ? "brave" : braveAlias);
        String url = braveUrl == null ? "" : braveUrl.trim();
        if (url.isBlank() || isPlaceholder(url)) {
            LOGGER.warning("McpSkillLoader: brave MCP enabled but no URL configured; skipping brave registration.");
            return;
        }
        servers.putIfAbsent(alias, url);
        String apiKey = braveApiKey == null ? "" : braveApiKey.trim();
        if (apiKey.isBlank() || isPlaceholder(apiKey)) {
            return;
        }
        String headerName = braveApiKeyHeader == null || braveApiKeyHeader.isBlank()
                ? "X-Subscription-Token"
                : braveApiKeyHeader.trim();
        Map<String, String> mergedHeaders = new LinkedHashMap<>(headersByAlias.getOrDefault(alias, Map.of()));
        mergedHeaders.putIfAbsent(headerName, apiKey);
        headersByAlias.put(alias, Map.copyOf(mergedHeaders));
    }

    private boolean isBraveRestSearchEndpoint(String alias, String serverUrl) {
        if (alias == null || serverUrl == null || !alias.contains("brave")) {
            return false;
        }
        try {
            URI uri = URI.create(serverUrl.trim());
            String path = uri.getPath() == null ? "" : uri.getPath().trim().toLowerCase();
            return path.endsWith("/res/v1/web/search");
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean isPlaceholder(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toUpperCase();
        return normalized.startsWith(PLACEHOLDER_PREFIX_REPLACE)
                || normalized.startsWith(PLACEHOLDER_PREFIX_YOUR)
                || normalized.contains(PLACEHOLDER_PREFIX_REPLACE)
                || normalized.contains(PLACEHOLDER_PREFIX_YOUR);
    }

    private String normalizeAlias(String alias) {
        String normalized = alias == null ? "" : alias.trim().toLowerCase();
        return normalized.replaceAll("[^a-z0-9._-]", "-");
    }
}

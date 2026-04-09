package com.zhongbo.mindos.assistant.skill.mcp;

import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import com.zhongbo.mindos.assistant.skill.search.SearchSourceConfig;
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
    private static final String PLACEHOLDER_PREFIX_REPLACE = "REPLACE_WITH_";
    private static final String PLACEHOLDER_PREFIX_YOUR = "YOUR_";

    private final SkillRegistry skillRegistry;
    private final McpJsonRpcClient mcpClient;
    private final String configuredServers;
    private final String configuredServerHeaders;
    private final String configuredSearchSources;
    private final boolean braveEnabled;
    private final String braveAlias;
    private final String braveUrl;
    private final String braveApiKey;
    private final String braveApiKeyHeader;
    private final boolean serperEnabled;
    private final String serperAlias;
    private final String serperUrl;
    private final String serperApiKey;
    private final String serperApiKeyHeader;
    private final SearchToolAdapterChain searchToolAdapterChain;

    @Autowired
    public McpSkillLoader(SkillRegistry skillRegistry,
                          @Value("${mindos.skills.mcp-servers:}") String configuredServers,
                          @Value("${mindos.skills.mcp-server-headers:}") String configuredServerHeaders,
                          @Value("${mindos.skills.search-sources:}") String configuredSearchSources,
                          @Value("${mindos.skills.mcp.brave.enabled:false}") boolean braveEnabled,
                          @Value("${mindos.skills.mcp.brave.alias:brave}") String braveAlias,
                          @Value("${mindos.skills.mcp.brave.url:}") String braveUrl,
                          @Value("${mindos.skills.mcp.brave.api-key:}") String braveApiKey,
                          @Value("${mindos.skills.mcp.brave.api-key-header:X-Subscription-Token}") String braveApiKeyHeader,
                          @Value("${mindos.skills.mcp.serper.enabled:false}") boolean serperEnabled,
                          @Value("${mindos.skills.mcp.serper.alias:serper}") String serperAlias,
                          @Value("${mindos.skills.mcp.serper.url:}") String serperUrl,
                          @Value("${mindos.skills.mcp.serper.api-key:}") String serperApiKey,
                          @Value("${mindos.skills.mcp.serper.api-key-header:X-API-KEY}") String serperApiKeyHeader) {
        this(skillRegistry,
                new McpJsonRpcClient(),
                configuredServers,
                configuredServerHeaders,
                configuredSearchSources,
                braveEnabled,
                braveAlias,
                braveUrl,
                braveApiKey,
                braveApiKeyHeader,
                serperEnabled,
                serperAlias,
                serperUrl,
                serperApiKey,
                serperApiKeyHeader);
    }

    McpSkillLoader(SkillRegistry skillRegistry,
                   McpJsonRpcClient mcpClient,
                   String configuredServers,
                   String configuredServerHeaders) {
        this(skillRegistry,
                mcpClient,
                configuredServers,
                configuredServerHeaders,
                "",
                false,
                "brave",
                "",
                "",
                "X-Subscription-Token",
                false,
                "serper",
                "",
                "",
                "X-API-KEY");
    }

    /**
     * Backwards-compatible constructor used by tests and some callers that don't provide configuredSearchSources.
     */
    McpSkillLoader(SkillRegistry skillRegistry,
                   McpJsonRpcClient mcpClient,
                   String configuredServers,
                   String configuredServerHeaders,
                   boolean braveEnabled,
                   String braveAlias,
                   String braveUrl,
                   String braveApiKey,
                   String braveApiKeyHeader) {
        this(skillRegistry,
                mcpClient,
                configuredServers,
                configuredServerHeaders,
                "",
                braveEnabled,
                braveAlias,
                braveUrl,
                braveApiKey,
                braveApiKeyHeader);
    }

    /**
     * Backwards-compatible constructor variant that includes serper flags but omits configuredSearchSources.
     */
    McpSkillLoader(SkillRegistry skillRegistry,
                   McpJsonRpcClient mcpClient,
                   String configuredServers,
                   String configuredServerHeaders,
                   boolean braveEnabled,
                   String braveAlias,
                   String braveUrl,
                   String braveApiKey,
                   String braveApiKeyHeader,
                   boolean serperEnabled,
                   String serperAlias,
                   String serperUrl,
                   String serperApiKey,
                   String serperApiKeyHeader) {
        this(skillRegistry,
                mcpClient,
                configuredServers,
                configuredServerHeaders,
                "",
                braveEnabled,
                braveAlias,
                braveUrl,
                braveApiKey,
                braveApiKeyHeader,
                serperEnabled,
                serperAlias,
                serperUrl,
                serperApiKey,
                serperApiKeyHeader);
    }

    McpSkillLoader(SkillRegistry skillRegistry,
                   McpJsonRpcClient mcpClient,
                   String configuredServers,
                   String configuredServerHeaders,
                   String configuredSearchSources,
                   boolean braveEnabled,
                   String braveAlias,
                   String braveUrl,
                   String braveApiKey,
                   String braveApiKeyHeader) {
        this(skillRegistry,
                mcpClient,
                configuredServers,
                configuredServerHeaders,
                configuredSearchSources,
                braveEnabled,
                braveAlias,
                braveUrl,
                braveApiKey,
                braveApiKeyHeader,
                false,
                "serper",
                "",
                "",
                "X-API-KEY");
    }

    McpSkillLoader(SkillRegistry skillRegistry,
                   McpJsonRpcClient mcpClient,
                   String configuredServers,
                   String configuredServerHeaders,
                   String configuredSearchSources,
                   boolean braveEnabled,
                   String braveAlias,
                   String braveUrl,
                   String braveApiKey,
                   String braveApiKeyHeader,
                   boolean serperEnabled,
                   String serperAlias,
                   String serperUrl,
                   String serperApiKey,
                   String serperApiKeyHeader) {
        this.skillRegistry = skillRegistry;
        this.mcpClient = mcpClient;
        this.configuredServers = configuredServers;
        this.configuredServerHeaders = configuredServerHeaders;
        this.configuredSearchSources = configuredSearchSources;
        this.braveEnabled = braveEnabled;
        this.braveAlias = braveAlias;
        this.braveUrl = braveUrl;
        this.braveApiKey = braveApiKey;
        this.braveApiKeyHeader = braveApiKeyHeader;
        this.serperEnabled = serperEnabled;
        this.serperAlias = serperAlias;
        this.serperUrl = serperUrl;
        this.serperApiKey = serperApiKey;
        this.serperApiKeyHeader = serperApiKeyHeader;
        this.searchToolAdapterChain = SearchToolAdapterRegistry.standard();
    }

    @PostConstruct
    public void loadOnStartup() {
        if ((configuredServers == null || configuredServers.isBlank())
                && (configuredSearchSources == null || configuredSearchSources.isBlank())
                && (!braveEnabled || braveUrl == null || braveUrl.isBlank())
                && (!serperEnabled || serperUrl == null || serperUrl.isBlank())) {
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
        logConfigPrecedenceHints();
        mergeSearchSources(servers, headersByAlias, configuredSearchSources);
        if (hasConfiguredSearchSources()) {
            logShortcutDeprecationHints();
        } else {
            mergeBraveConfig(servers, headersByAlias);
            mergeSerperConfig(servers, headersByAlias);
        }
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
            SearchSourceConfig searchSource = SearchSourceConfig.shortcut(normalizedAlias, serverUrl, "", "");
            SearchToolBinding searchBinding = searchToolAdapterChain.resolve(searchSource, safeHeaders).orElse(null);
            if (searchBinding != null) {
                skillRegistry.register(new McpToolSkill(searchBinding.definition(), searchBinding.client()));
                LOGGER.info("McpSkillLoader: registered search skill '" + searchBinding.definition().skillName() + "'");
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

    public String getConfiguredSearchSources() {
        return configuredSearchSources;
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
        String alias = braveAlias == null || braveAlias.isBlank() ? "brave" : braveAlias;
        mergeShortcutConfig(servers,
                headersByAlias,
                SearchSourceConfig.brave(alias, braveUrl, braveApiKey, braveApiKeyHeader),
                "brave");
    }

    private void mergeSerperConfig(Map<String, String> servers, Map<String, Map<String, String>> headersByAlias) {
        if (!serperEnabled) {
            return;
        }
        String alias = serperAlias == null || serperAlias.isBlank() ? "serper" : serperAlias;
        mergeShortcutConfig(servers,
                headersByAlias,
                SearchSourceConfig.shortcut(alias, serperUrl, serperApiKey, serperApiKeyHeader),
                "serper");
    }

    private void mergeShortcutConfig(Map<String, String> servers,
                                     Map<String, Map<String, String>> headersByAlias,
                                     SearchSourceConfig source,
                                     String label) {
        if (source == null) {
            return;
        }
        if (!registerSearchSource(servers, headersByAlias, source, true, label)) {
            LOGGER.warning("McpSkillLoader: " + label + " MCP enabled but no URL configured; skipping " + label + " registration.");
        }
    }


    private void mergeSearchSources(Map<String, String> servers,
                                    Map<String, Map<String, String>> headersByAlias,
                                    String rawSources) {
        for (SearchSourceConfig source : SearchSourceConfig.parseList(rawSources)) {
            registerSearchSource(servers, headersByAlias, source, false, "search source");
        }
    }

    private boolean registerSearchSource(Map<String, String> servers,
                                         Map<String, Map<String, String>> headersByAlias,
                                         SearchSourceConfig source,
                                         boolean logCollisionAsShortcut,
                                         String label) {
        if (source == null) {
            return false;
        }
        String alias = normalizeAlias(source.alias());
        String url = source.resolvedMcpUrl();
        if (alias.isBlank() || url.isBlank() || isPlaceholder(url)) {
            return false;
        }
        if (servers.containsKey(alias)) {
            if (logCollisionAsShortcut) {
                LOGGER.info("McpSkillLoader: " + label + " shortcut alias='" + alias + "' skipped because an explicit MCP/search-source entry already exists.");
            } else {
                LOGGER.info("McpSkillLoader: search source alias='" + alias + "' skipped because an explicit MCP server entry already exists.");
            }
        }
        servers.putIfAbsent(alias, url);
        mergeApiKeyHeader(headersByAlias, alias, source);
        return true;
    }

    private void mergeApiKeyHeader(Map<String, Map<String, String>> headersByAlias,
                                   String alias,
                                   SearchSourceConfig source) {
        String apiKey = source.apiKey() == null ? "" : source.apiKey().trim();
        if (apiKey.isBlank() || isPlaceholder(apiKey)) {
            return;
        }
        String headerName = source.resolvedApiKeyHeader();
        Map<String, String> mergedHeaders = new LinkedHashMap<>(headersByAlias.getOrDefault(alias, Map.of()));
        mergedHeaders.putIfAbsent(headerName, apiKey);
        headersByAlias.put(alias, Map.copyOf(mergedHeaders));
    }

    private void logConfigPrecedenceHints() {
        boolean hasExplicitServers = configuredServers != null && !configuredServers.isBlank();
        boolean hasExplicitHeaders = configuredServerHeaders != null && !configuredServerHeaders.isBlank();
        boolean hasSearchSources = hasConfiguredSearchSources();
        if (hasSearchSources && hasExplicitServers) {
            LOGGER.info("McpSkillLoader: both mindos.skills.mcp-servers and mindos.skills.search-sources are configured; explicit MCP server entries keep precedence on alias collisions.");
        }
        if (hasExplicitHeaders && !hasExplicitServers) {
            LOGGER.info("McpSkillLoader: mindos.skills.mcp-server-headers is set without explicit mcp-servers; headers will only apply if aliases are supplied by search-source or shortcut configs.");
        }
    }

    private void logShortcutDeprecationHints() {
        if (braveEnabled) {
            LOGGER.info("McpSkillLoader: mindos.skills.search-sources is configured; deprecated brave shortcut config is ignored.");
        }
        if (serperEnabled) {
            LOGGER.info("McpSkillLoader: mindos.skills.search-sources is configured; deprecated serper shortcut config is ignored.");
        }
    }

    private boolean hasConfiguredSearchSources() {
        return configuredSearchSources != null && !configuredSearchSources.isBlank();
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

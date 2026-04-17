package com.zhongbo.mindos.assistant.dispatcher.system;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.SkillStudioCommandSupport;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class SkillStudioWorkflowService {

    private final SkillStudioCommandSupport commandSupport;

    public SkillStudioWorkflowService() {
        this(new SkillStudioCommandSupport());
    }

    SkillStudioWorkflowService(SkillStudioCommandSupport commandSupport) {
        this.commandSupport = commandSupport == null ? new SkillStudioCommandSupport() : commandSupport;
    }

    public SkillResult execute(String skillName, SkillContext context) {
        DraftPlan draft = buildDraft(context);
        return SkillResult.success(skillName == null || skillName.isBlank() ? "skill.factory" : skillName, renderDraft(draft));
    }

    DraftPlan buildDraft(SkillContext context) {
        Map<String, Object> attributes = commandSupport.resolveAttributes(context);
        String request = asText(attributes.get("request"));
        String action = firstNonBlank(asText(attributes.get("action")), "create");
        String sourceType = firstNonBlank(asText(attributes.get("sourceType")), "builtin");
        String sourceUrl = asText(attributes.get("sourceUrl"));
        String publishMode = firstNonBlank(asText(attributes.get("publishMode")), "draft");
        String goal = firstNonBlank(asText(attributes.get("goal")), request);
        String implementationTrack = recommendImplementation(sourceType, request, goal, sourceUrl);
        String riskLevel = firstNonBlank(asText(attributes.get("riskLevel")), resolveRiskLevel(implementationTrack, sourceType));
        String draftName = buildDraftName(
                asText(attributes.get("skillNameHint")),
                goal,
                sourceType,
                sourceUrl,
                request
        );
        return new DraftPlan(
                request,
                action,
                sourceType,
                sourceUrl,
                goal,
                draftName,
                implementationTrack,
                publishMode,
                riskLevel,
                suggestedParams(implementationTrack),
                releaseSteps(implementationTrack, sourceType),
                guardrails(implementationTrack, sourceType),
                needsMoreDetail(goal, request)
        );
    }

    private String renderDraft(DraftPlan draft) {
        StringBuilder reply = new StringBuilder("[skill.studio]\n");
        reply.append("阶段: draft\n");
        reply.append("动作: ").append(humanizeAction(draft.action())).append('\n');
        reply.append("原始需求: ").append(fallbackText(draft.request(), "未提供")).append('\n');
        reply.append("目标: ").append(draft.missingGoal() ? "（还需要补充更具体的能力目标）" : draft.goal()).append('\n');
        reply.append("建议实现: ").append(humanizeImplementation(draft.implementationTrack())).append('\n');
        reply.append("建议 skill 名: ").append(draft.draftName()).append('\n');
        reply.append("来源类型: ").append(humanizeSourceType(draft.sourceType())).append('\n');
        if (!draft.sourceUrl().isBlank()) {
            reply.append("来源地址: ").append(draft.sourceUrl()).append('\n');
        }
        reply.append("发布模式: ").append(draft.publishMode()).append('\n');
        reply.append("风险等级: ").append(draft.riskLevel()).append('\n');
        reply.append('\n').append("参数草案:\n");
        for (String param : draft.suggestedParams()) {
            reply.append("- ").append(param).append('\n');
        }
        reply.append('\n').append("后续发布路径:\n");
        for (int index = 0; index < draft.releaseSteps().size(); index++) {
            reply.append(index + 1).append(". ").append(draft.releaseSteps().get(index)).append('\n');
        }
        reply.append('\n').append("约束:\n");
        for (String guardrail : draft.guardrails()) {
            reply.append("- ").append(guardrail).append('\n');
        }
        if (draft.missingGoal()) {
            reply.append('\n')
                    .append("还缺少的信息: 请继续告诉我这个 skill 具体要做什么，或给我来源地址（OpenAPI / MCP / GitHub / JAR）。");
        }
        return reply.toString().trim();
    }

    private String recommendImplementation(String sourceType,
                                           String request,
                                           String goal,
                                           String sourceUrl) {
        String normalizedSourceType = normalize(sourceType);
        String corpus = normalize(firstNonBlank(goal, request, sourceUrl));
        if ("mcp".equals(normalizedSourceType)) {
            return "mcp-binding";
        }
        if ("openapi".equals(normalizedSourceType) || "cloud-api".equals(normalizedSourceType)) {
            return "cloud-api";
        }
        if ("repo".equals(normalizedSourceType)) {
            return "repo-review";
        }
        if ("jar".equals(normalizedSourceType)) {
            return "jar-review";
        }
        if (containsAny(corpus, "抓取", "爬虫", "scrape", "crawl", "网页", "网站", "html", "url")) {
            return "generated-web-scraper";
        }
        if (containsAny(corpus, "模板", "固定回复", "快捷回复", "别名", "简单包装", "prompt wrapper", "模板skill")) {
            return "script-json";
        }
        return "java-builtin";
    }

    private List<String> suggestedParams(String implementationTrack) {
        return switch (normalize(implementationTrack)) {
            case "generated-web-scraper" -> List.of(
                    "url (required): 需要抓取的目标网址",
                    "focus (optional): 希望优先提取的内容主题",
                    "limit (optional): 结果条数或候选页数"
            );
            case "cloud-api" -> List.of(
                    "query (required): 查询主题或主参数",
                    "endpoint (config): 外部 API 地址 / operation",
                    "auth (config): API key / token / header 配置"
            );
            case "mcp-binding" -> List.of(
                    "query (required): 传给 MCP 工具的主查询",
                    "serverAlias (config): MCP 服务别名",
                    "toolName (config): 目标工具名"
            );
            case "repo-review" -> List.of(
                    "sourceUrl (required): 开源仓库地址",
                    "entrypoint (optional): 想保留的公开入口名称",
                    "allowlist (review): 依赖、网络、文件系统权限审查"
            );
            case "jar-review" -> List.of(
                    "sourceUrl (required): 外部 JAR 地址",
                    "spiEntry (review): Skill SPI / 入口类检查",
                    "allowlist (review): 依赖与网络权限审查"
            );
            case "script-json" -> List.of(
                    "input (required): 原始用户输入",
                    "template (config): 返回模板 / 占位符",
                    "triggers (config): 触发关键词"
            );
            default -> List.of(
                    "task (required): 要让 skill 完成的能力目标",
                    "mode (optional): create / import / adapt",
                    "constraints (optional): 运行边界或格式要求"
            );
        };
    }

    private List<String> releaseSteps(String implementationTrack, String sourceType) {
        List<String> steps = new ArrayList<>();
        steps.add("先保留为 draft，只产出 SkillSpec / ParamSchema / 测试建议。");
        switch (normalize(implementationTrack)) {
            case "mcp-binding" -> {
                steps.add("补齐 MCP server alias、tool 名称和 host allowlist。");
                steps.add("做一次连通性 + 参数映射测试，再进入 shadow。");
            }
            case "cloud-api" -> {
                steps.add("补齐接口地址、鉴权方式和结果模板。");
                steps.add("通过接口回归后再进入 shadow，再决定是否 live。");
            }
            case "repo-review", "jar-review" -> {
                steps.add("先做源码 / 依赖 / license / 风险审查，不直接接入运行时。");
                steps.add("审查通过后再决定转成 MCP / Cloud API / Java builtin 适配。");
            }
            case "generated-web-scraper" -> {
                steps.add("先按抓取型原型 skill 落地，并补站点白名单与限流策略。");
                steps.add("通过执行稳定性测试后再考虑 shadow。");
            }
            case "script-json" -> {
                steps.add("先生成 JSON / template 原型，确认触发词和输出格式。");
                steps.add("确认命中率后再决定是否升格为稳定 capability。");
            }
            default -> {
                steps.add("先按 Java builtin 方案补纯执行逻辑和参数校验。");
                steps.add("通过资格测试后先 shadow，再决定是否 live。");
            }
        }
        if (normalize(sourceType).equals("builtin")) {
            steps.add("当前没有外部来源，优先从你这轮自然语言需求直接生成草案。");
        }
        return List.copyOf(steps);
    }

    private List<String> guardrails(String implementationTrack, String sourceType) {
        List<String> guardrails = new ArrayList<>();
        guardrails.add("当前阶段只生成 draft，不会自动发布到默认 live 路由。");
        guardrails.add("不会引入第二套 orchestrator；后续仍走 Hermes 单决策路径。");
        if (containsAny(normalize(implementationTrack), "repo", "jar")) {
            guardrails.add("不会直接执行外部仓库 / JAR 代码，必须先审查再适配。");
        }
        if (containsAny(normalize(implementationTrack), "cloud", "mcp")) {
            guardrails.add("外部联网能力必须补 host allowlist、凭证和失败回退策略。");
        }
        if ("generated-web-scraper".equals(normalize(implementationTrack))) {
            guardrails.add("抓取类 skill 后续需要补站点范围、速率和可读性提取策略。");
        }
        if ("builtin".equals(normalize(sourceType))) {
            guardrails.add("当前先从自然语言需求生成 spec，不自动编译或注册新代码。");
        }
        return List.copyOf(guardrails);
    }

    private boolean needsMoreDetail(String goal, String request) {
        String normalizedGoal = normalize(goal);
        if (normalizedGoal.isBlank()) {
            return true;
        }
        return normalizedGoal.equals(normalize(request)) && normalizedGoal.length() <= 12;
    }

    private String buildDraftName(String explicitHint,
                                  String goal,
                                  String sourceType,
                                  String sourceUrl,
                                  String request) {
        String normalizedHint = slug(explicitHint);
        if (!normalizedHint.isBlank()) {
            return "draft." + normalizedHint;
        }
        String urlHost = hostSegment(sourceUrl);
        String goalSlug = slug(goal);
        if (!urlHost.isBlank() && !goalSlug.isBlank()) {
            return "draft." + urlHost + "." + goalSlug;
        }
        if (!goalSlug.isBlank()) {
            return "draft." + goalSlug;
        }
        String sourceSegment = slug(sourceType);
        if (!sourceSegment.isBlank()) {
            return "draft." + sourceSegment + ".skill." + shortFingerprint(request);
        }
        return "draft.skill." + shortFingerprint(request);
    }

    private String slug(String value) {
        String normalized = normalize(value).replaceAll("[^a-z0-9]+", ".").replaceAll("\\.+", ".");
        normalized = normalized.replaceAll("^\\.|\\.$", "");
        if (normalized.length() > 48) {
            normalized = normalized.substring(0, 48).replaceAll("\\.$", "");
        }
        return normalized;
    }

    private String hostSegment(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().trim().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            if (host.isBlank()) {
                return "";
            }
            String[] parts = host.split("\\.");
            return parts.length == 0 ? "" : slug(parts[0]);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private String shortFingerprint(String request) {
        String text = request == null ? "" : request;
        return Long.toHexString(Integer.toUnsignedLong(text.hashCode())).substring(0, Math.min(6, Long.toHexString(Integer.toUnsignedLong(text.hashCode())).length()));
    }

    private String humanizeAction(String action) {
        return switch (normalize(action)) {
            case "import" -> "导入 skill";
            case "adapt" -> "学习 / 适配外部 skill";
            case "test" -> "测试 skill";
            case "publish" -> "发布 skill";
            case "retire" -> "下线 skill";
            case "inspect" -> "检查 / 设计方案";
            default -> "创建 skill";
        };
    }

    private String humanizeImplementation(String implementationTrack) {
        return switch (normalize(implementationTrack)) {
            case "mcp-binding" -> "mcp-binding（复用外部 MCP 工具）";
            case "cloud-api" -> "cloud-api（包装标准 HTTP / OpenAPI 接口）";
            case "repo-review" -> "repo-review（先分析开源仓库，再决定适配方式）";
            case "jar-review" -> "jar-review（先审查外部 JAR，再决定是否接入）";
            case "generated-web-scraper" -> "generated-web-scraper（抓取型原型 skill）";
            case "script-json" -> "script-json（简单模板 / 别名型 skill）";
            default -> "java-builtin（稳定纯执行 skill）";
        };
    }

    private String humanizeSourceType(String sourceType) {
        return switch (normalize(sourceType)) {
            case "repo" -> "开源仓库";
            case "jar" -> "外部 JAR";
            case "mcp" -> "MCP 服务";
            case "openapi" -> "OpenAPI / Swagger";
            case "cloud-api" -> "HTTP / Cloud API";
            case "external" -> "外部来源";
            default -> "内生需求";
        };
    }

    private String resolveRiskLevel(String implementationTrack, String sourceType) {
        String normalized = normalize(implementationTrack + " " + sourceType);
        if (containsAny(normalized, "repo", "jar")) {
            return "high";
        }
        if (containsAny(normalized, "mcp", "cloud", "openapi", "scraper")) {
            return "medium";
        }
        return "low";
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String haystack, String... terms) {
        if (haystack == null || haystack.isBlank() || terms == null) {
            return false;
        }
        for (String term : terms) {
            if (term != null && !term.isBlank() && haystack.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String fallbackText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    record DraftPlan(String request,
                     String action,
                     String sourceType,
                     String sourceUrl,
                     String goal,
                     String draftName,
                     String implementationTrack,
                     String publishMode,
                     String riskLevel,
                     List<String> suggestedParams,
                     List<String> releaseSteps,
                     List<String> guardrails,
                     boolean missingGoal) {
    }
}

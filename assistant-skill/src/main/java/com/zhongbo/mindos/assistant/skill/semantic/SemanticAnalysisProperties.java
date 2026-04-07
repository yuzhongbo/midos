package com.zhongbo.mindos.assistant.skill.semantic;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@ConfigurationProperties(prefix = "mindos.dispatcher.semantic-analysis")
public class SemanticAnalysisProperties {

    private boolean enabled = true;
    private boolean llmEnabled = false;
    private boolean forceLocal = true;
    private String delegateSkill = "";
    private String llmProvider = "local";
    private String llmPreset = "cost";
    private int maxTokens = 120;
    private LlmComplexity llmComplexity = new LlmComplexity();
    private LocalEscalation localEscalation = new LocalEscalation();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isLlmEnabled() {
        return llmEnabled;
    }

    public void setLlmEnabled(boolean llmEnabled) {
        this.llmEnabled = llmEnabled;
    }

    public boolean isForceLocal() {
        return forceLocal;
    }

    public void setForceLocal(boolean forceLocal) {
        this.forceLocal = forceLocal;
    }

    public String getDelegateSkill() {
        return delegateSkill;
    }

    public void setDelegateSkill(String delegateSkill) {
        this.delegateSkill = delegateSkill == null ? "" : delegateSkill.trim();
    }

    public String getLlmProvider() {
        return llmProvider;
    }

    public void setLlmProvider(String llmProvider) {
        this.llmProvider = llmProvider == null ? "" : llmProvider.trim();
    }

    public String getLlmPreset() {
        return llmPreset;
    }

    public void setLlmPreset(String llmPreset) {
        this.llmPreset = llmPreset == null ? "" : llmPreset.trim();
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = Math.max(0, maxTokens);
    }

    public LocalEscalation getLocalEscalation() {
        return localEscalation;
    }

    public void setLocalEscalation(LocalEscalation localEscalation) {
        this.localEscalation = localEscalation == null ? new LocalEscalation() : localEscalation;
    }

    public LlmComplexity getLlmComplexity() {
        return llmComplexity;
    }

    public void setLlmComplexity(LlmComplexity llmComplexity) {
        this.llmComplexity = llmComplexity == null ? new LlmComplexity() : llmComplexity;
    }

    public static class LlmComplexity {
        private int minInputChars = 10;
        private String triggerTerms = "新闻,搜索,实时,分析,规划,计划,代码,排查,debug,search,latest,news,plan,report";

        public int getMinInputChars() {
            return minInputChars;
        }

        public void setMinInputChars(int minInputChars) {
            this.minInputChars = Math.max(0, minInputChars);
        }

        public String getTriggerTerms() {
            return triggerTerms;
        }

        public void setTriggerTerms(String triggerTerms) {
            this.triggerTerms = triggerTerms == null ? "" : triggerTerms.trim();
        }
    }

    public static class LocalEscalation {
        private boolean enabled = false;
        private String cloudProvider = "qwen";
        private String cloudPreset = "quality";
        private double minConfidence = 0.78;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCloudProvider() {
            return cloudProvider;
        }

        public void setCloudProvider(String cloudProvider) {
            this.cloudProvider = cloudProvider == null ? "" : cloudProvider.trim().toLowerCase(Locale.ROOT);
        }

        public String getCloudPreset() {
            return cloudPreset;
        }

        public void setCloudPreset(String cloudPreset) {
            this.cloudPreset = cloudPreset == null ? "" : cloudPreset.trim();
        }

        public double getMinConfidence() {
            return minConfidence;
        }

        public void setMinConfidence(double minConfidence) {
            this.minConfidence = Math.max(0.0, Math.min(1.0, minConfidence));
        }
    }
}

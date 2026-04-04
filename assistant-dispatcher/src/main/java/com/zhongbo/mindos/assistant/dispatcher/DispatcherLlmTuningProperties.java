package com.zhongbo.mindos.assistant.dispatcher;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "mindos.dispatcher")
public class DispatcherLlmTuningProperties {

    private final LlmDsl llmDsl = new LlmDsl();
    private final LlmFallback llmFallback = new LlmFallback();
    private final SkillFinalizeWithLlm skillFinalizeWithLlm = new SkillFinalizeWithLlm();
    private final LocalEscalation localEscalation = new LocalEscalation();

    public LlmDsl getLlmDsl() {
        return llmDsl;
    }

    public LlmFallback getLlmFallback() {
        return llmFallback;
    }

    public SkillFinalizeWithLlm getSkillFinalizeWithLlm() {
        return skillFinalizeWithLlm;
    }

    public LocalEscalation getLocalEscalation() {
        return localEscalation;
    }

    public static class LlmDsl {
        private String provider = "";
        private String preset = "";
        private int maxTokens = 0;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider == null ? "" : provider.trim();
        }

        public String getPreset() {
            return preset;
        }

        public void setPreset(String preset) {
            this.preset = preset == null ? "" : preset.trim();
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = Math.max(0, maxTokens);
        }
    }

    public static class LlmFallback {
        private String provider = "";
        private String preset = "";
        private int maxTokens = 0;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider == null ? "" : provider.trim();
        }

        public String getPreset() {
            return preset;
        }

        public void setPreset(String preset) {
            this.preset = preset == null ? "" : preset.trim();
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = Math.max(0, maxTokens);
        }
    }

    public static class SkillFinalizeWithLlm {
        private int maxTokens = 0;

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = Math.max(0, maxTokens);
        }
    }

    public static class LocalEscalation {
        private boolean enabled = false;
        private String cloudProvider = "qwen";
        private String cloudPreset = "quality";
        private final Quality quality = new Quality();

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
            this.cloudProvider = cloudProvider == null ? "" : cloudProvider.trim();
        }

        public String getCloudPreset() {
            return cloudPreset;
        }

        public void setCloudPreset(String cloudPreset) {
            this.cloudPreset = cloudPreset == null ? "" : cloudPreset.trim();
        }

        public Quality getQuality() {
            return quality;
        }
    }

    public static class Quality {
        private boolean enabled = true;
        private int maxReplyChars = 32;
        private String inputTerms = "分析,方案,架构,tradeoff,trade-off,对比,设计,复杂,深度,沟通,情绪,关系,计划,why,explain";
        private String replyTerms = "好的,收到,已收到,ok,okay,明白,可以,稍后,后面再说";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxReplyChars() {
            return maxReplyChars;
        }

        public void setMaxReplyChars(int maxReplyChars) {
            this.maxReplyChars = Math.max(8, maxReplyChars);
        }

        public String getInputTerms() {
            return inputTerms;
        }

        public void setInputTerms(String inputTerms) {
            this.inputTerms = inputTerms == null ? "" : inputTerms.trim();
        }

        public String getReplyTerms() {
            return replyTerms;
        }

        public void setReplyTerms(String replyTerms) {
            this.replyTerms = replyTerms == null ? "" : replyTerms.trim();
        }
    }
}

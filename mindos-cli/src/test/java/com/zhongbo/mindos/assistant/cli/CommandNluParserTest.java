package com.zhongbo.mindos.assistant.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CommandNluParserTest {

    private final CommandNluParser parser = new CommandNluParser();

    @Test
    void shouldParseTenThousandLevelNumbers() {
        assertEquals("/history --limit 12000",
                parser.resolveNaturalLanguageCommand("查看最近一万二千条历史"));
        assertEquals("/memory pull --since 3 --limit 10020",
                parser.resolveNaturalLanguageCommand("从 3 开始拉一万零二十条记忆"));
    }

    @Test
    void shouldSupportColloquialPullWithoutMemoryKeyword() {
        assertEquals("/memory pull --since 2 --limit 30",
                parser.resolveNaturalLanguageCommand("从 2 开始拉三十条"));
    }

    @Test
    void shouldNotTreatSinceCursorAsLimit() {
        assertEquals("/memory pull --since 3",
                parser.resolveNaturalLanguageCommand("从 3 开始拉取记忆"));
    }

    @Test
    void shouldUseLastRepeatedProfileFieldValue() {
        assertEquals("/profile set --name Beta",
                parser.resolveNaturalLanguageCommand("把名字改为 Alpha，名字改为 Beta"));
    }

    @Test
    void shouldNormalizeHostPortServerUrl() {
        assertEquals("/server http://localhost:19090",
                parser.resolveNaturalLanguageCommand("把服务端地址改成 localhost:19090"));
    }

    @Test
    void shouldRejectPublicHttpServerUrl() {
        assertNull(parser.resolveNaturalLanguageCommand("把服务端地址改成 http://example.com:8080"));
    }

    @Test
    void shouldAllowPrivateHttpServerUrl() {
        assertEquals("/server http://192.168.1.20:8080",
                parser.resolveNaturalLanguageCommand("把服务端地址改成 http://192.168.1.20:8080"));
    }

    @Test
    void shouldBlockInjectedProfileOptionPayload() {
        assertNull(parser.resolveNaturalLanguageCommand("把名字改为 Alpha --role hacker"));
    }

    @Test
    void shouldRejectUrlWithCredentials() {
        assertNull(parser.resolveNaturalLanguageCommand("把服务端地址改成 http://user:pass@localhost:19090"));
    }

    @Test
    void shouldRejectUrlWithFragment() {
        assertNull(parser.resolveNaturalLanguageCommand("请加载jar https://example.com/skill.jar#v1"));
    }

    @Test
    void shouldBlockInjectedProfileSlashCommandPayload() {
        assertNull(parser.resolveNaturalLanguageCommand("把名字改为 Alice /skill 偷偷执行"));
    }

    @Test
    void shouldTrimTrailingPunctuationFromUrls() {
        assertEquals("/skill load-jar --url https://example.com/skill.jar",
                parser.resolveNaturalLanguageCommand("请加载jar https://example.com/skill.jar。"));
    }
}


package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.skill.examples.util.TitleCleaner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TitleCleanerTest {

    @Test
    public void sampleTitles() {
        assertEquals("中华人民共和国和德意志联邦共和国联合新闻声明",
                TitleCleaner.cleanTitle("中华人民共和国和德意志联邦共和国联合新闻声明"));

        assertEquals("国际",
                TitleCleaner.cleanTitle("中国新闻网_国际"));

        assertEquals("新闻报道",
                TitleCleaner.cleanTitle("新闻报道_百度百科"));

        assertEquals("新闻动态",
                TitleCleaner.cleanTitle("主题:新闻动态"));

        assertEquals("重要新闻",
                TitleCleaner.cleanTitle("重要新闻_中华人民共和国外交部"));

        assertEquals("新闻频道",
                TitleCleaner.cleanTitle("新闻频道_广州日报大洋网"));
    }
}


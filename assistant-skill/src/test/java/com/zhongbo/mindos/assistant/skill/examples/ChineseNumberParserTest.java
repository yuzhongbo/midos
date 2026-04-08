package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.skill.examples.util.ChineseNumberParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ChineseNumberParserTest {

    @Test
    public void parseArabic() {
        assertEquals(6, ChineseNumberParser.parseFlexibleNumber("6").intValue());
        assertEquals(12, ChineseNumberParser.parseFlexibleNumber("12").intValue());
    }

    @Test
    public void parseSingleChinese() {
        assertEquals(6, ChineseNumberParser.parseFlexibleNumber("六").intValue());
        assertEquals(2, ChineseNumberParser.parseFlexibleNumber("两").intValue());
        assertEquals(10, ChineseNumberParser.parseFlexibleNumber("十").intValue());
    }

    @Test
    public void parseCompoundChinese() {
        assertEquals(12, ChineseNumberParser.parseFlexibleNumber("十二").intValue());
        assertEquals(20, ChineseNumberParser.parseFlexibleNumber("二十").intValue());
        assertEquals(105, ChineseNumberParser.parseFlexibleNumber("一百零五").intValue());
        assertEquals(12345, ChineseNumberParser.parseFlexibleNumber("一万二千三百四十五").intValue());
    }

    @Test
    public void parseWithSuffixes() {
        assertEquals(6, ChineseNumberParser.parseFlexibleNumber("六周").intValue());
        assertEquals(8, ChineseNumberParser.parseFlexibleNumber("8周").intValue());
    }

    @Test
    public void invalidReturnsNull() {
        assertNull(ChineseNumberParser.parseFlexibleNumber("很多"));
        assertNull(ChineseNumberParser.parseFlexibleNumber("abcxyz"));
    }
}


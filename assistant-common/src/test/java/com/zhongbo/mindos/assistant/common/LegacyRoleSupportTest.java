package com.zhongbo.mindos.assistant.common;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class LegacyRoleSupportTest {

    @Test
    void shouldTreatBlankAndDefaultRoleAsInactive() {
        assertNull(LegacyRoleSupport.explicitRole(null));
        assertNull(LegacyRoleSupport.explicitRole(""));
        assertNull(LegacyRoleSupport.explicitRole("   "));
        assertNull(LegacyRoleSupport.explicitRole("personal-assistant"));
        assertTrue(LegacyRoleSupport.isDefaultRole("personal-assistant"));
    }

    @Test
    void shouldReturnExplicitLegacyRoleForCompatibility() {
        assertEquals("programmer", LegacyRoleSupport.explicitRole("programmer"));
        assertEquals("高一", LegacyRoleSupport.explicitRole(" 高一 "));
    }
}

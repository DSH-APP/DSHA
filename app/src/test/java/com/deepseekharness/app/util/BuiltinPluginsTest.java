package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/** 内置插件纯逻辑的不变式（重构时绝不能改坏）。 */
public class BuiltinPluginsTest {
    @org.junit.Test public void hidesOnlyRuntimeInfrastructure() {
        org.junit.Assert.assertTrue(BuiltinPlugins.internal("@deepseek-ai/dsh-base"));
        org.junit.Assert.assertTrue(BuiltinPlugins.internal("@deepseek-ai/dsh-web-app"));
        org.junit.Assert.assertTrue(BuiltinPlugins.internal("dsh-app-integration"));
        for (String name : BuiltinPlugins.DEFAULT_BUILTINS) org.junit.Assert.assertFalse(BuiltinPlugins.internal(name));
        org.junit.Assert.assertFalse(BuiltinPlugins.internal("@other/my-plugin"));
    }

    @Test
    public void parseBuiltinNames_逐行解析_跳过空行与注释() {
        String txt = "# 内置插件\n\ndsh-device-shell-guide\n  dsh-task-notifier  \n\n# 注释行\ndsh-status-overlay\ndsh-web-mobile\n";
        assertEquals(Arrays.asList("dsh-device-shell-guide", "dsh-task-notifier",
                "dsh-status-overlay", "dsh-web-mobile"),
                BuiltinPlugins.parseBuiltinNames(txt));
    }

    @Test
    public void parseBuiltinNames_null与空串返回空表() {
        assertTrue(BuiltinPlugins.parseBuiltinNames(null).isEmpty());
        assertTrue(BuiltinPlugins.parseBuiltinNames("").isEmpty());
        assertTrue(BuiltinPlugins.parseBuiltinNames("  \n# 只有注释\n").isEmpty());
    }

    @Test
    public void entityDir_把dsh前缀换成dsha前缀() {
        assertEquals("/root/dsha-device-shell-guide",
                BuiltinPlugins.entityDir("dsh-device-shell-guide"));
        assertEquals("/root/dsha-web-mobile", BuiltinPlugins.entityDir("dsh-web-mobile"));
    }

    @Test
    public void entityDir_非dsh前缀名字统一补dsha前缀() {
        assertEquals("/root/dsha-foo", BuiltinPlugins.entityDir("foo"));
    }

    @Test
    public void inBundlesSection_只在bundles数组里判定_不被dependencies干扰() {
        // dependencies 里有同名 link:，但 bundles 没有 → 未注册
        String manifest = "{\n"
                + "  \"dependencies\": { \"dsh-device-shell-guide\": \"link:/root/dsha-device-shell-guide\" },\n"
                + "  \"dsh\": { \"profile\": { \"bundles\": [\"@deepseek-ai/dsh-base\"] } }\n"
                + "}";
        assertFalse(BuiltinPlugins.inBundlesSection(manifest, "dsh-device-shell-guide"));
        assertTrue(BuiltinPlugins.inBundlesSection(manifest, "@deepseek-ai/dsh-base"));

        // bundles 里有 → 已注册
        String registered = "{\"dsh\":{\"profile\":{\"bundles\":[\"@deepseek-ai/dsh-base\","
                + "\"dsh-device-shell-guide\",\"dsh-web-mobile\"]}}}";
        assertTrue(BuiltinPlugins.inBundlesSection(registered, "dsh-device-shell-guide"));
        assertTrue(BuiltinPlugins.inBundlesSection(registered, "dsh-web-mobile"));
        assertFalse(BuiltinPlugins.inBundlesSection(registered, "dsh-task-notifier"));
    }

    @Test
    public void inBundlesSection_缺bundles键或非法输入返回false() {
        assertFalse(BuiltinPlugins.inBundlesSection(null, "dsh-web-mobile"));
        assertFalse(BuiltinPlugins.inBundlesSection("{\"dependencies\":{}}", "dsh-web-mobile"));
        assertFalse(BuiltinPlugins.inBundlesSection("{\"dsh\":{\"profile\":{}}}", null));
        assertFalse(BuiltinPlugins.inBundlesSection("{\"dsh\":{\"profile\":{\"bundles\":[]}}}",
                "dsh-web-mobile"));
    }
}

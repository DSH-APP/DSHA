package com.deepseekharness.app.util;

import java.util.Locale;

/**
 * 读取<b>系统</b>首选语言，供「跟随系统」使用。
 *
 * <h3>为什么必须缓存（真机验证的坑）</h3>
 *
 * <p>直觉做法是每次读 {@code Resources.getSystem().getConfiguration()}，但<b>真机上它会被污染</b>：
 * {@code LanguageController.apply()} 为了日期/数字格式会调 {@code Locale.setDefault(界面语言)}，
 * vivid 等实现下 {@code Resources.getSystem()} 拿到的 Configuration 会跟着这个默认 Locale 变。
 * 结果是「跟随系统」在切换一次语言之后读到的是<b>界面语言</b>而不是系统语言 —— 自我锁死。
 *
 * <p>因此在<b>进程最早的时刻</b>（{@link #initialize()}，早于任何 {@code setDefault}）
 * 读一次真实系统语言并缓存，之后无论应用语言怎么切换，{@link #tag()} 都返回那一份。
 *
 * <p>取值顺序（逐级回退，任何一级不可用都不抛异常）：
 * <ol>
 *   <li>{@code Resources.getSystem().getConfiguration().getLocales().get(0)}（API 24+）</li>
 *   <li>{@code Configuration.locale} 字段（API 1，API 24 起 deprecated）</li>
 *   <li>{@link Locale#getDefault()}（无 Android 运行时的 JVM 单测兜底）</li>
 * </ol>
 */
public final class SystemLanguage {

    /** 进程生命周期内固定的系统语言标签；null = 尚未初始化。 */
    private static volatile String cached;

    private SystemLanguage() {
    }

    /**
     * 在进程最早时刻调用（{@code Application.onCreate} 最前面），
     * 锁存真实系统语言。重复调用只生效第一次。
     */
    public static void initialize() {
        if (cached == null) cached = detect();
    }

    /**
     * 系统首选语言的 BCP-47 标签。
     *
     * <p>已 {@link #initialize()} 过则返回锁存值（不受后续 {@code Locale.setDefault} 影响）；
     * 未初始化时按需探测 —— 单测与异常路径仍能工作。
     */
    public static String tag() {
        String value = cached;
        if (value != null && !value.isEmpty()) return value;
        String detected = detect();
        if (detected == null || detected.isEmpty()) detected = Locale.getDefault().toLanguageTag();
        // 未初始化时也顺手锁存，保证同一进程内一致。
        cached = detected;
        return detected;
    }

    /** 仅供单测：清掉锁存值。 */
    static void resetForTest() { cached = null; }

    /** 探测系统语言；全部失败返回 null。 */
    private static String detect() {
        Object configuration = systemConfiguration();
        if (configuration != null) {
            String viaList = firstOfLocaleList(configuration);
            if (viaList != null && !viaList.isEmpty()) return viaList;
            String viaField = localeFieldTag(configuration);
            if (viaField != null && !viaField.isEmpty()) return viaField;
        }
        return null;
    }

    /**
     * 取 {@code Resources.getSystem().getConfiguration()}。
     *
     * <p>用反射而非直接调用，是为了让本类在没有 Android 运行时的 JVM 单测里也能加载
     * （找不到类就返回 null，由调用方回退）。生产路径（Android）永远走得到。
     */
    private static Object systemConfiguration() {
        try {
            Class<?> resources = Class.forName("android.content.res.Resources");
            Object system = resources.getMethod("getSystem").invoke(null);
            if (system == null) return null;
            return resources.getMethod("getConfiguration").invoke(system);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** API 24+：{@code Configuration.getLocales().get(0)}。 */
    private static String firstOfLocaleList(Object configuration) {
        try {
            Object locales = configuration.getClass().getMethod("getLocales").invoke(configuration);
            if (locales == null) return null;
            Locale first = (Locale) locales.getClass().getMethod("get", int.class).invoke(locales, 0);
            return first == null ? null : first.toLanguageTag();
        } catch (Throwable unavailable) {
            // 兼容版 minSdk 23：API 23 上 getLocales() 不存在，继续走字段回退。
            return null;
        }
    }

    /** API 1 起可用：{@code Configuration.locale} 公开字段（API 24 起 deprecated）。 */
    private static String localeFieldTag(Object configuration) {
        try {
            Object legacy = configuration.getClass().getField("locale").get(configuration);
            return legacy instanceof Locale ? ((Locale) legacy).toLanguageTag() : null;
        } catch (Throwable unavailable) {
            return null;
        }
    }
}

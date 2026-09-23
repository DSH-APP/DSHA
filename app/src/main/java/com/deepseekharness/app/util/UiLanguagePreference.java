package com.deepseekharness.app.util;

/**
 * 界面语言偏好：<b>跟随系统</b> / 中文 / 英文。
 *
 * <p>构建契约见 AGENTS.md「中英文界面默认中文」：仅当用户显式选择语言时才偏离系统，
 * 其余情况都跟随系统 —— 系统是中文（或无法识别）落中文，系统的首选语言是英文落英文，
 * 其他语言也落英文（英语是唯一可用的国际语言，比中文更可能被看懂）。
 *
 * <p>偏好值只有三个：{@code system}（默认）、{@code zh}、{@code en}。旧版本只写过
 * {@code zh}/{@code en}，所以老用户升级后保持原语言；从未设置过的用户视为 {@code system}。
 */
public final class UiLanguagePreference {

    /** 跟随系统语言（默认值）。 */
    public static final String SYSTEM = "system";
    /** 简体中文。 */
    public static final String ZH = "zh";
    /** 英文。 */
    public static final String EN = "en";

    private UiLanguagePreference() {
    }

    /** 规范化偏好值：只认显式选择的 en/zh，其余（含 null、旧脏值）一律跟随系统。 */
    public static String normalize(String value) {
        if (EN.equals(value)) return EN;
        if (ZH.equals(value)) return ZH;
        return SYSTEM;
    }

    /** 这个值是否是可保存的偏好（三个之一）。 */
    public static boolean supported(String value) {
        return SYSTEM.equals(value) || ZH.equals(value) || EN.equals(value);
    }

    /** 偏好是否表示「跟随系统」。 */
    public static boolean followsSystem(String value) {
        return SYSTEM.equals(normalize(value));
    }

    /**
     * 把系统首选语言映射到应用实际语言（只有 {@code zh} 与 {@code en}）。
     *
     * <p>规则：首选语言是中文（{@code zh}、{@code zh-CN}、{@code zh-Hant}…）→ 中文；
     * 其他任何语言（含 {@code en}、{@code fr}、无法识别）→ 英文。
     *
     * @param languageTag 系统 Locale 的 BCP-47 标签，可以为 null
     */
    public static String resolveLanguage(String languageTag) {
        return isChinese(languageTag) ? ZH : EN;
    }

    /** 由偏好 + 系统语言算出实际生效语言。 */
    public static String resolve(String preference, String languageTag) {
        String chosen = normalize(preference);
        return SYSTEM.equals(chosen) ? resolveLanguage(languageTag) : chosen;
    }

    /** 语言标签的主语言子标签是否为 {@code zh}（大小写不敏感）。 */
    public static boolean isChinese(String languageTag) {
        if (languageTag == null) return false;
        String value = languageTag.trim();
        if (value.isEmpty()) return false;
        int dash = value.indexOf('-');
        if (dash < 0) dash = value.indexOf('_');
        String primary = dash < 0 ? value : value.substring(0, dash);
        return "zh".equalsIgnoreCase(primary);
    }
}

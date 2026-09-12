package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 短信授权只匹配可完整解释的 query 参数，不接受 provider 写入或任意 SQL。 */
public final class SmsQuery {
    private SmsQuery() { }
    private static final String NUMERIC = "(?:_id|thread_id|date|date_sent|type|read|seen|sub_id|status|locked)";
    private static final Set<String> COLUMNS = new HashSet<>(Arrays.asList(
            "_id", "thread_id", "address", "person", "date", "date_sent", "protocol", "read", "status", "type",
            "reply_path_present", "subject", "body", "service_center", "locked", "error_code", "seen", "sub_id"));

    public static void validate(List<String> argv) {
        if (argv.size() < 4 || !argv.get(0).equals("content") || !argv.get(1).equals("query"))
            throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("短信能力只允许 content query"));
        Set<String> seen = new HashSet<>();
        for (int i = 2; i < argv.size(); i += 2) {
            String flag = argv.get(i);
            if (i + 1 == argv.size() || !seen.add(flag)) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("查询选项缺值或重复"));
            String value = argv.get(i + 1);
            switch (flag) {
                case "--uri":
                    if (!value.matches("content://sms(?:/(?:inbox|sent|draft|outbox|failed|queued))?(?:/[0-9]+)?"))
                        throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("短信授权不包含其他内容提供者或 URI 参数"));
                    break;
                case "--user":
                    if (!value.matches("current|[0-9]{1,5}")) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("只允许本 Android 用户"));
                    break;
                case "--projection":
                    for (String column : value.split(":", -1))
                        if (!COLUMNS.contains(column)) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("只能选择已识别的短信字段"));
                    break;
                case "--where":
                    String number = NUMERIC + "\\s*(?:=|!=|>=|<=|>|<)\\s*[0-9]{1,19}";
                    String address = "address\\s*=\\s*'[+0-9 -]{1,40}'";
                    String atom = "(?:" + number + "|" + address + ")";
                    if (!value.equals("0") && !value.equals("1=0")
                            && !value.matches("(?i)" + atom + "(?:\\s+AND\\s+" + atom + "){0,7}"))
                        throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("筛选只支持短信数字字段、号码与 AND 条件"));
                    break;
                case "--sort":
                    if (!value.matches("(?i)(?:_id|date|date_sent)(?:\\s+(?:ASC|DESC))?"))
                        throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("排序只支持短信编号或时间"));
                    break;
                default: throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("短信查询不支持此选项：") + flag);
            }
        }
        if (!seen.contains("--uri")) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("缺少短信 URI"));
    }

    /** content 工具实际接受数字用户号；默认号也固定为 DSHA 所在的 Android 用户。 */
    public static List<String> forUser(List<String> argv, int user) {
        validate(argv);
        if (user < 0) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("无法确认当前 Android 用户"));
        List<String> result = new ArrayList<>(argv);
        int index = result.indexOf("--user");
        if (index >= 0) {
            String requested = result.get(index + 1);
            if (!requested.equals("current") && Integer.parseInt(requested) != user)
                throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("短信授权不包含其他 Android 用户或工作资料"));
            result.set(index + 1, Integer.toString(user));
        } else {
            result.add("--user"); result.add(Integer.toString(user));
        }
        return result;
    }
}

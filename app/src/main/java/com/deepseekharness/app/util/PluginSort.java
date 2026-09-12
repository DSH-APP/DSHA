package com.deepseekharness.app.util;

import java.text.Collator;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/** 名称按字母/中文拼音及自然数字顺序排列；切换界面语言不改变列表顺序。 */
public final class PluginSort {
    private PluginSort() { }
    public enum Mode {
        NAME_ASC, NAME_DESC, ENABLED_FIRST, UPDATE_FIRST;
        public static Mode parse(String key) {
            try { return valueOf(key); } catch (IllegalArgumentException | NullPointerException ignored) { return NAME_ASC; }
        }
    }
    public static <T> Comparator<T> comparator(Mode mode, Function<T,String> name,
                                               Predicate<T> enabled, Predicate<T> update) {
        Collator collator=Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
        collator.setStrength(Collator.SECONDARY);
        Comparator<T> names=(a,b)->compareNames(name.apply(a),name.apply(b),collator);
        if(mode==Mode.NAME_DESC)return names.reversed();
        if(mode==Mode.ENABLED_FIRST)return Comparator.<T,Boolean>comparing(item->!enabled.test(item)).thenComparing(names);
        if(mode==Mode.UPDATE_FIRST)return Comparator.<T,Boolean>comparing(item->!update.test(item)).thenComparing(names);
        return names;
    }
    private static String normalize(String value) { return Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC); }
    private static boolean digit(char value) { return value>='0' && value<='9'; }
    private static int compareNames(String first,String second,Collator collator) {
        String a=normalize(first),b=normalize(second);int i=0,j=0;
        while(i<a.length() && j<b.length()) {
            boolean numbers=digit(a.charAt(i)) && digit(b.charAt(j));
            int ai=i,bj=j;
            if(numbers) {
                while(ai<a.length()&&digit(a.charAt(ai)))ai++;
                while(bj<b.length()&&digit(b.charAt(bj)))bj++;
                int az=i,bz=j;while(az<ai-1&&a.charAt(az)=='0')az++;while(bz<bj-1&&b.charAt(bz)=='0')bz++;
                int byLength=Integer.compare(ai-az,bj-bz);if(byLength!=0)return byLength;
                int byValue=a.substring(az,ai).compareTo(b.substring(bz,bj));if(byValue!=0)return byValue;
            } else {
                // 不同时为数字时按字符排序，保持与后续数字段的一致边界。
                ai++;bj++;
                int byLetter=collator.compare(a.substring(i,ai),b.substring(j,bj));if(byLetter!=0)return byLetter;
            }
            i=ai;j=bj;
        }
        int remaining=Integer.compare(a.length()-i,b.length()-j);
        return remaining!=0?remaining:a.compareTo(b);
    }
}

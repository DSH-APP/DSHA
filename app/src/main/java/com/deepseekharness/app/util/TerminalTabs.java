package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 会话身份永久递增；显示编号复用空缺，关闭失败或关闭中仍占用编号。 */
public final class TerminalTabs<T> {
    public static final class Tab<T> {
        public final long id;
        public final int number;
        public final T value;
        private boolean closing;
        private Tab(long id, int number, T value) { this.id=id; this.number=number; this.value=value; }
        public boolean isClosing() { return closing; }
    }
    private final List<Tab<T>> tabs=new ArrayList<>();
    private long next=1, selected;
    private boolean initialized;
    public synchronized Tab<T> add(T value) {
        if(value==null)throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("终端会话不能为空"));
        int number=1;
        java.util.HashSet<Integer> occupied=new java.util.HashSet<>();
        for(Tab<T> item:tabs)occupied.add(item.number);
        while(occupied.contains(number))number++;
        Tab<T> tab=new Tab<>(next++,number,value);tabs.add(tab);selected=tab.id;initialized=true;return tab;
    }
    public synchronized List<Tab<T>> snapshot() { return Collections.unmodifiableList(new ArrayList<>(tabs)); }
    public synchronized Tab<T> current() { return find(selected); }
    public synchronized Tab<T> find(long id) { for(Tab<T> tab:tabs)if(tab.id==id)return tab;return null; }
    public synchronized boolean select(long id) { if(find(id)==null)return false;selected=id;return true; }
    public synchronized boolean beginClose(long id) { Tab<T> tab=find(id);if(tab==null||tab.closing)return false;tab.closing=true;return true; }
    public synchronized void closeFailed(long id) { Tab<T> tab=find(id);if(tab!=null)tab.closing=false; }
    public synchronized void remove(long id) {
        Tab<T> tab=find(id);if(tab==null)return;int index=tabs.indexOf(tab);tabs.remove(index);
        if(selected==id)selected=tabs.isEmpty()?0:tabs.get(Math.min(index,tabs.size()-1)).id;
    }
    public synchronized boolean wasInitialized() { return initialized; }
}

package com.deepseekharness.app.util;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class PluginSortTest {
    private static final class Item {
        final String name;final boolean enabled,update;
        Item(String name,boolean enabled,boolean update){this.name=name;this.enabled=enabled;this.update=update;}
    }
    private Comparator<Item> order(PluginSort.Mode mode){return PluginSort.comparator(mode,i->i.name,i->i.enabled,i->i.update);}
    @Test public void alphabeticNaturalNumbersAndReverseUseActualNames() {
        List<Item> items=new ArrayList<>(Arrays.asList(new Item("zeta",true,false),new Item("plugin10",false,false),new Item("Alpha",false,false),new Item("plugin2",false,false)));
        items.sort(order(PluginSort.Mode.NAME_ASC));assertEquals("Alpha,plugin2,plugin10,zeta",String.join(",",items.stream().map(i->i.name).toArray(String[]::new)));
        items.sort(order(PluginSort.Mode.NAME_DESC));assertEquals("zeta,plugin10,plugin2,Alpha",String.join(",",items.stream().map(i->i.name).toArray(String[]::new)));
    }
    @Test public void statusGroupsStillSortNamesWithinEachGroup() {
        Item enabled=new Item("Zulu",true,false),update=new Item("Beta",false,true),other=new Item("Alpha",false,false);
        List<Item> items=new ArrayList<>(Arrays.asList(other,update,enabled));items.sort(order(PluginSort.Mode.ENABLED_FIRST));assertSame(enabled,items.get(0));assertSame(other,items.get(1));
        items.sort(order(PluginSort.Mode.UPDATE_FIRST));assertSame(update,items.get(0));assertSame(other,items.get(1));
    }
    @Test public void chineseNamesUsePinyinAndUnknownPreferenceHasSafeDefault() {
        assertTrue(order(PluginSort.Mode.NAME_ASC).compare(new Item("阿尔法",false,false),new Item("测试",false,false))<0);
        assertEquals(PluginSort.Mode.NAME_ASC,PluginSort.Mode.parse("old-value"));assertEquals(PluginSort.Mode.NAME_ASC,PluginSort.Mode.parse(null));
    }
    @Test public void largeNumbersAndTiesAreStableAndComparatorIsTransitive() {
        String[] names={"a2","A2","a02","a10","a12345678901234567890","a999999999999999999999","阿尔法","测试","@scope/a","éclair","eclair","","１２"};
        Comparator<Item> c=order(PluginSort.Mode.NAME_ASC);
        for(String a:names)for(String b:names){Item x=new Item(a,false,false),y=new Item(b,false,false);assertEquals(-Integer.signum(c.compare(x,y)),Integer.signum(c.compare(y,x)));
            for(String d:names){Item z=new Item(d,false,false);if(c.compare(x,y)<=0&&c.compare(y,z)<=0)assertTrue(a+" / "+b+" / "+d,c.compare(x,z)<=0);}}
    }
}

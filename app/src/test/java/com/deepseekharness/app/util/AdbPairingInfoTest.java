package com.deepseekharness.app.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class AdbPairingInfoTest {
    @Test public void pairingAndConnectionPortsStaySeparate(){
        var main=AdbPairingInfo.connection("无线调试\nIP 地址和端口\n10.0.0.5:33555\n使用配对码配对设备\n");
        var popup=AdbPairingInfo.pairing("与设备配对\nWLAN 配对码\n123456\nIP 地址和端口\n10.0.0.5:38371\n");
        assertEquals("33555",main.port);assertEquals("38371",popup.port);assertEquals("123456",popup.code);assertNull(AdbPairingInfo.connection("无线调试\nWLAN 配对码\n123456\nIP 地址和端口\n10.0.0.5:38371\n"));
    }
    @Test public void deviceNamesAndPartialCodeAreNotPairingCodes(){
        assertNull(AdbPairingInfo.pairing("无线调试\n设备名称\n123456\n使用配对码配对设备\n10.0.0.5:33555"));
        assertNull(AdbPairingInfo.pairing("WLAN 配对码\n12345\n10.0.0.5:38371"));
        assertNull(AdbPairingInfo.pairing("WLAN 配对码\n1234567\n10.0.0.5:38371"));
    }
    @Test public void englishDialogAndInvalidAddress(){
        assertEquals("123456",AdbPairingInfo.pairing("Wi-Fi pairing code\n123456\nIP address & port\n192.168.1.2:50001").code);
        assertNull(AdbPairingInfo.pairing("WLAN 配对码\n123456\n999.0.0.1:3000"));assertNull(AdbPairingInfo.pairing("WLAN 配对码\n123456\n127.0.0.1:0"));
    }
}

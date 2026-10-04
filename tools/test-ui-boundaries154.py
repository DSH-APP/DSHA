#!/usr/bin/env python3
"""Static Android resource/consumer contracts; does not substitute for TalkBack or platform NSC."""
import pathlib
import unittest
import xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1]
A='{http://schemas.android.com/apk/res/android}'

class UiBoundaryTest(unittest.TestCase):
    def xml(self,path):return ET.parse(ROOT/path).getroot()
    def test_known_update_domains_deny_http_and_api23_still_declares_compatibility(self):
        config=self.xml('app/src/main/res/xml/network_security_config.xml')
        self.assertEqual('true',config.find('base-config').get('cleartextTrafficPermitted'))
        rules={d.text:c.get('cleartextTrafficPermitted') for c in config.findall('domain-config') for d in c.findall('domain')}
        for host in ['localhost','127.0.0.1','::1']:self.assertEqual('true',rules[host])
        for host in ['dsha.cc','github.com','release-assets.githubusercontent.com','objects.githubusercontent.com']:self.assertEqual('false',rules[host])
        app=self.xml('app/src/main/AndroidManifest.xml').find('application')
        self.assertEqual('@xml/network_security_config',app.get(A+'networkSecurityConfig'));self.assertEqual('true',app.get(A+'usesCleartextTraffic'))
    def test_input_labels_reference_real_existing_fields(self):
        for path in ['activity_adb_pair.xml','fragment_config.xml']:
            root=self.xml('app/src/main/res/layout/'+path);ids={e.get(A+'id','').replace('@+id/','@id/') for e in root.iter()}
            labels=[e.get(A+'labelFor') for e in root.iter() if e.get(A+'labelFor')]
            self.assertGreaterEqual(len(labels),2)
            for target in labels:self.assertIn(target,ids)
    def test_static_welcome_rows_do_not_look_clickable_and_status_indicator_is_decorative(self):
        for path in ['welcome_page2.xml','welcome_page3.xml']:
            root=self.xml('app/src/main/res/layout/'+path)
            for row in root.iter():
                if row.get(A+'id','').startswith('@+id/welcome_item_'):
                    self.assertEqual('false',row.get(A+'clickable'));self.assertNotIn('selectableItemBackground',row.get(A+'background',''))
                    self.assertFalse(any(c.get(A+'src')=='@drawable/ic_ui2_chevron' for c in row.iter()))
        root=self.xml('app/src/main/res/layout/fragment_launch.xml');dot=next(e for e in root.iter() if e.get(A+'id')=='@+id/launch_run_dot');self.assertEqual('no',dot.get(A+'importantForAccessibility'))
    def test_intro_labels_have_both_languages_and_no_inline_english_captions(self):
        for language in ['values','values-en']:
            root=self.xml('app/src/main/res/'+language+'/audit154_ui.xml');self.assertEqual(4,len(root.findall('string')))
        for path in ['welcome_page1.xml','welcome_page2.xml','welcome_page3.xml','fragment_device_grants.xml']:
            for node in self.xml('app/src/main/res/layout/'+path).iter():
                self.assertNotIn(node.get(A+'text'),['DEEPSEEK HARNESS · ANDROID','01 / BUILT FOR YOUR FLOW','02 / YOUR DATA, YOUR CHOICE','Computer Use'])

if __name__=='__main__':unittest.main()

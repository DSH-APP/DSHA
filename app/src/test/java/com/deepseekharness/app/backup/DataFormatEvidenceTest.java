package com.deepseekharness.app.backup;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class DataFormatEvidenceTest {
    @Test public void observedRuntimeNeverInventsADataFormatEpoch()throws Exception{
        var descriptor=new RuntimeDescriptor(RuntimeDescriptorTest.value());var evidence=DataFormatEvidence.unknown(descriptor);
        assertEquals("unknown",evidence.get("status"));assertEquals("",evidence.get("formatEpoch"));assertEquals(descriptor.id(),evidence.get("observedRuntimeId"));
    }
    @Test public void runtimeVersionLabelAloneCannotAuthorizeDataDowngrade()throws Exception{
        var old=RuntimeDescriptorTest.value();old.put("inputs",Map.of("runtime","a".repeat(64)));RuntimeDescriptorTest.identify(old);
        var descriptor=new RuntimeDescriptor(old);assertFalse(descriptor.canReadDataWrittenBy(descriptor));
    }
    @Test public void identicalPayloadAndSchemaPatchesAllowExistingVerifiedRollback()throws Exception{
        var a=new RuntimeDescriptor(RuntimeDescriptorTest.value());var changed=RuntimeDescriptorTest.value();changed.put("inputs",RuntimeDescriptorTest.inputs('b'));RuntimeDescriptorTest.identify(changed);
        assertTrue(a.canReadDataWrittenBy(new RuntimeDescriptor(changed)));
        var inputs=new HashMap<>((Map<String,Object>)changed.get("inputs"));inputs.put("persona-compat-patch.json","5".repeat(64));changed.put("inputs",inputs);RuntimeDescriptorTest.identify(changed);
        assertFalse(a.canReadDataWrittenBy(new RuntimeDescriptor(changed)));
    }
    @Test public void absentAndSenderClaimedFormatsStayUnconfirmed(){
        assertEquals("DATA_FORMAT_UNCONFIRMED",DataFormatEvidence.restoreWarning(Map.of()));
        assertEquals("DATA_FORMAT_UNCONFIRMED",DataFormatEvidence.restoreWarning(Map.of("dataCompatibility",Map.of("schema",1L,"status","verified","formatEpoch","999"))));
        assertEquals("DATA_FORMAT_METADATA_UNSUPPORTED",DataFormatEvidence.restoreWarning(Map.of("dataCompatibility",Map.of("schema",99L))));
    }
}

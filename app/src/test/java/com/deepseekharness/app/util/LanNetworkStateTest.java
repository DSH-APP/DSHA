package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class LanNetworkStateTest {
  @Test
  public void firstObservationAndUnchangedNetworkDoNotRetireConnections() {
    var observation = new AtomicReference<Set<String>>(Set.of("wlan0:5:192.168.0.2"));
    var state = new LanNetworkState(observation::get);
    assertFalse(state.poll());
    assertFalse(state.poll());
    observation.set(Set.of("wlan0:5:192.168.0.3"));
    assertTrue(state.poll());
    assertFalse(state.poll());
  }

  @Test
  public void disconnectAndReconnectEachRetireOldConnections() {
    var observation = new AtomicReference<Set<String>>(Set.of("wlan0:5:192.168.0.2"));
    var state = new LanNetworkState(observation::get);
    assertFalse(state.poll());
    observation.set(Set.of());
    assertTrue(state.poll());
    assertFalse(state.poll());
    observation.set(Set.of("wlan0:7:192.168.0.2"));
    assertTrue("interface replacement matters even with the same address", state.poll());
  }

  @Test
  public void deniedInterfaceObservationPreservesTheLastKnownIdentity() {
    var observation = new AtomicReference<Set<String>>(Set.of("wlan0:5:192.168.0.2"));
    var state = new LanNetworkState(observation::get);
    assertFalse(state.poll());
    observation.set(null);
    assertFalse(state.poll());
    observation.set(Set.of("wlan0:5:192.168.0.2"));
    assertFalse(state.poll());
    observation.set(Set.of("eth0:6:10.0.0.2"));
    assertTrue(state.poll());
  }
}

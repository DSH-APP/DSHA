package com.deepseekharness.app.util;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceScriptProofTest {
  @Test
  public void wrapperComesFromExactSignedBodyAndIncludesFinalNewline() throws Exception {
    String source =
        "cat > /root/dsh-bin/adb-shell <<'EOF'\n#!/bin/bash\n# version=20\nexec python3 signed.py\nEOF\n";
    assertEquals(
        "#!/bin/bash\n# version=20\nexec python3 signed.py\n",
        new String(DeviceScriptProof.wrapper(source.getBytes())));
    assertThrows(IOException.class, () -> DeviceScriptProof.wrapper((source + source).getBytes()));
    assertThrows(IOException.class, () -> DeviceScriptProof.wrapper("# version=20\n".getBytes()));
  }
}

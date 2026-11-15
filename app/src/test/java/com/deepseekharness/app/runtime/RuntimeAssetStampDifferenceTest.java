package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import org.junit.Test;

/** Pure diagnostic regression; filesystem trust remains covered by RuntimeAssetStampAndroidTest. */
public class RuntimeAssetStampDifferenceTest {
  @Test
  public void reportsTheFirstChangedFileIncludingSameSizeNanosecondMetadataChanges() {
    assertEquals(
        0, RuntimeAssetStamp.firstDifference("1:2|10:11:12|20:21:22", "1:2|10:11:13|20:21:22"));
    assertEquals(
        1, RuntimeAssetStamp.firstDifference("1:2|10:11:12|20:21:22", "1:2|10:11:12|20:21:23"));
  }

  @Test
  public void separatesRootChangesUnavailableSnapshotsAndIdenticalSnapshots() {
    assertEquals(-1, RuntimeAssetStamp.firstDifference("1:2|10:11", "1:3|10:11"));
    assertEquals(-2, RuntimeAssetStamp.firstDifference(null, "1:2"));
    assertEquals(-2, RuntimeAssetStamp.firstDifference("1:2", null));
    assertEquals(-3, RuntimeAssetStamp.firstDifference("1:2|10:11", "1:2|10:11"));
  }

  @Test
  public void detectsManagedFileSetGrowthOrShrinkageWithoutReportingTheirValues() {
    assertEquals(1, RuntimeAssetStamp.firstDifference("1:2|10:11", "1:2|10:11|20:21"));
    assertEquals(1, RuntimeAssetStamp.firstDifference("1:2|10:11|20:21", "1:2|10:11"));
  }
}

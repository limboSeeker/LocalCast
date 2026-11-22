package org.webrtc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KeyFrameThrottleTest {
  private static final long MS = 1_000_000L;

  @Test
  public void firstRequestPassesAndCloseOnesWait() {
    KeyFrameThrottle t = new KeyFrameThrottle();
    assertTrue(t.shouldEncodeKeyFrame(true, 0, 1000));
    assertFalse(t.shouldEncodeKeyFrame(true, 300 * MS, 1000));
    assertFalse(t.shouldEncodeKeyFrame(true, 600 * MS, 1000));
  }

  @Test
  public void suppressedRequestIsServedAfterTheInterval() {
    KeyFrameThrottle t = new KeyFrameThrottle();
    assertTrue(t.shouldEncodeKeyFrame(true, 0, 1000));
    assertFalse(t.shouldEncodeKeyFrame(true, 200 * MS, 1000));
    // The browser does not ask again: the next frame after the interval is still a keyframe.
    assertFalse(t.shouldEncodeKeyFrame(false, 900 * MS, 1000));
    assertTrue(t.shouldEncodeKeyFrame(false, 1001 * MS, 1000));
    // Served once, then ordinary delta frames.
    assertFalse(t.shouldEncodeKeyFrame(false, 1100 * MS, 1000));
  }

  @Test
  public void noRequestNoKeyFrame() {
    KeyFrameThrottle t = new KeyFrameThrottle();
    assertFalse(t.shouldEncodeKeyFrame(false, 0, 1000));
    assertFalse(t.shouldEncodeKeyFrame(false, 5000 * MS, 1000));
  }

  @Test
  public void resetLetsTheNextRequestThrough() {
    KeyFrameThrottle t = new KeyFrameThrottle();
    assertTrue(t.shouldEncodeKeyFrame(true, 0, 1000));
    t.reset();
    assertTrue(t.shouldEncodeKeyFrame(true, 10 * MS, 1000));
  }

  @Test
  public void zeroIntervalDisablesThrottling() {
    KeyFrameThrottle t = new KeyFrameThrottle();
    assertTrue(t.shouldEncodeKeyFrame(true, 0, 0));
    assertTrue(t.shouldEncodeKeyFrame(true, 1, 0));
  }
}

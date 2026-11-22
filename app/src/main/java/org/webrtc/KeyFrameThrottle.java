package org.webrtc;

/**
 * Limits keyframes of one encoder to one per interval without losing a request.
 *
 * <p>A keyframe of a screen is 10–30x a delta frame: on a lossy Wi-Fi link each one caused more
 * loss and the next PLI (13 PLIs and 5 keyframes in 30 s of scrolling on a tablet). A request that
 * comes too soon is remembered and served by the first frame after the interval, so a decoder
 * that really needs a keyframe gets one even if its browser does not repeat the request.
 * Not thread-safe: used on the encoder's own thread. Unit-tested.
 */
final class KeyFrameThrottle {
  private boolean anyKeyFrame;
  private boolean pending;
  private long lastKeyFrameNs;

  /**
   * @param requested the frame was asked to be a keyframe
   * @return whether to encode this frame as a keyframe
   */
  boolean shouldEncodeKeyFrame(boolean requested, long nowNs, long minIntervalMs) {
    if (!requested && !pending) {
      return false;
    }
    long minNs = minIntervalMs * 1_000_000L;
    if (anyKeyFrame && minNs > 0 && nowNs - lastKeyFrameNs < minNs) {
      pending = true;
      return false;
    }
    anyKeyFrame = true;
    pending = false;
    lastKeyFrameNs = nowNs;
    return true;
  }

  /** A (re)initialised encoder starts with a keyframe of its own: let the next request through. */
  void reset() {
    anyKeyFrame = false;
    pending = false;
  }
}

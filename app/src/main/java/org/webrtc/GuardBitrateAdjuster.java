// SPDX-License-Identifier: Apache-2.0
package org.webrtc;

import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Bitrate adjuster that keeps hardware encoders close to their target, for any vendor.
 *
 * <p>{@link HardwareVideoEncoder} calls {@link #setTargets} on its encode thread and, after every
 * encoded frame, {@link #reportEncodedFrame} and {@link #getAdjustedBitrateBps()} on its output
 * thread; when the latter changes it sends it to MediaCodec ("video-bitrate"). After a static
 * screen, CBR hardware encoders spend their banked budget at once: on a Snapdragon the first ~300 ms
 * of motion came out at ~4× the target, which floods Wi-Fi.
 *
 * <ul>
 *   <li>When the output of the last {@link #WINDOW_MS} exceeds {@link #OVERSHOOT}× the target, the
 *       encoder target is scaled down in proportion (never below {@link #MIN_SCALE} or
 *       {@link #MIN_GUARD_BPS}). The window is then restarted, so frames encoded before the cut
 *       (and before MediaCodec applied it) do not push the scale down again.</li>
 *   <li>A single large frame that makes up most of the window (a key frame) is expected and
 *       ignored; only a sustained run of oversized frames is an overshoot.</li>
 *   <li>Without overshoot the scale recovers by {@link #RECOVER_STEP} every
 *       {@link #RECOVER_INTERVAL_MS}. Non-urgent MediaCodec updates are limited to 4/s.</li>
 * </ul>
 *
 * <p>With {@code frameratePinned} (Exynos) the bitrate sent to the codec follows libwebrtc's
 * {@link FramerateBitrateAdjuster}: those encoders allocate bits per frame at a fixed 30 fps, so
 * the codec gets {@code target × 30 / fps}. Overshoot is still measured against the real target.
 */
class GuardBitrateAdjuster implements BitrateAdjuster {
  private static final String TAG = "LocalCast/Encoder";
  static final long WINDOW_MS = 250;
  static final double OVERSHOOT = 1.5;
  static final double MIN_SCALE = 0.35;
  static final double RECOVER_STEP = 0.15;
  static final long RECOVER_INTERVAL_MS = 300;
  static final long MIN_UPDATE_INTERVAL_MS = 250;
  /** The guard never pushes the target below this (lower targets come from congestion control). */
  static final int MIN_GUARD_BPS = 500_000;
  /** A window dominated by one frame (share of bytes) is a key frame, not an overshoot. */
  static final double SINGLE_FRAME_SHARE = 0.6;
  private static final double PINNED_FPS = 30.0;

  private final boolean frameratePinned;
  private final boolean guardEnabled;
  private final String label;
  private final LongSupplier clockMs;
  private final ArrayDeque<long[]> window = new ArrayDeque<>(); // {timeMs, bytes}
  private long windowBytes;

  /** libwebrtc's target for this stream. */
  private int targetBps;
  /** What the codec is given before the guard (differs from targetBps only when pinned). */
  private int codecTargetBps;
  private double targetFps = PINNED_FPS;
  private double scale = 1.0;
  private int published;
  private long lastPublishMs;
  private long lastChangeMs;

  // Per-second diagnostics.
  private long statsStartMs;
  private double peakRatio;
  private int maxFrameBytes;
  private int frames;
  private int guardHits;
  private double minScale = 1.0;

  GuardBitrateAdjuster(boolean frameratePinned, boolean guardEnabled, String label) {
    this(frameratePinned, guardEnabled, label, SystemClock::elapsedRealtime);
  }

  GuardBitrateAdjuster(
      boolean frameratePinned, boolean guardEnabled, String label, LongSupplier clockMs) {
    this.frameratePinned = frameratePinned;
    this.guardEnabled = guardEnabled;
    this.label = label;
    this.clockMs = clockMs;
  }

  @Override
  public synchronized void setTargets(int targetBitrateBps, double targetFramerateFps) {
    double fps = targetFramerateFps > 0 ? targetFramerateFps : PINNED_FPS;
    targetBps = targetBitrateBps;
    if (frameratePinned) {
      codecTargetBps = (int) (targetBitrateBps * PINNED_FPS / fps);
      targetFps = PINNED_FPS;
    } else {
      codecTargetBps = targetBitrateBps;
      targetFps = fps;
    }
  }

  @Override
  public synchronized void reportEncodedFrame(int size) {
    long now = clockMs.getAsLong();
    window.addLast(new long[] {now, size});
    windowBytes += size;
    while (!window.isEmpty() && now - window.peekFirst()[0] > WINDOW_MS) {
      windowBytes -= window.pollFirst()[1];
    }
    double outBps = windowBytes * 8.0 * 1000.0 / WINDOW_MS;
    double ratio = targetBps > 0 ? outBps / targetBps : 0;
    long largest = 0;
    for (long[] f : window) largest = Math.max(largest, f[1]);
    boolean singleSpike = largest > SINGLE_FRAME_SHARE * windowBytes;

    if (guardEnabled) {
      if (targetBps > MIN_GUARD_BPS && ratio > OVERSHOOT && !singleSpike) {
        double floor = Math.max(MIN_SCALE, (double) MIN_GUARD_BPS / targetBps);
        double next = Math.max(floor, Math.min(scale, scale / ratio));
        if (next < scale) {
          scale = next;
          lastChangeMs = now;
          guardHits++;
          // Restart the window: the frames in it were encoded before this cut took effect.
          window.clear();
          windowBytes = 0;
        }
      } else if (scale < 1.0 && now - lastChangeMs >= RECOVER_INTERVAL_MS) {
        scale = Math.min(1.0, scale + RECOVER_STEP);
        lastChangeMs = now;
      }
    }
    logStats(now, ratio, size);
  }

  @Override
  public synchronized int getAdjustedBitrateBps() {
    int floor = Math.min(codecTargetBps, MIN_GUARD_BPS);
    int wanted = Math.max(floor, (int) (codecTargetBps * scale));
    if (wanted == published) return published;
    long now = clockMs.getAsLong();
    // A big drop goes out at once; everything else at most 4 times per second (each change is a
    // MediaCodec setParameters call).
    boolean urgent = published == 0 || wanted < published * 0.7;
    if (urgent || now - lastPublishMs >= MIN_UPDATE_INTERVAL_MS) {
      published = wanted;
      lastPublishMs = now;
    }
    return published;
  }

  @Override
  public synchronized double getAdjustedFramerateFps() {
    return targetFps;
  }

  synchronized double currentScale() {
    return scale;
  }

  private void logStats(long now, double ratio, int size) {
    if (statsStartMs == 0) statsStartMs = now;
    frames++;
    peakRatio = Math.max(peakRatio, ratio);
    maxFrameBytes = Math.max(maxFrameBytes, size);
    minScale = Math.min(minScale, scale);
    if (now - statsStartMs < 1000) return;
    // Only interesting seconds (motion or guard activity) to keep logcat readable.
    if (peakRatio > 1.2 || guardHits > 0 || minScale < 1.0) {
      android.util.Log.i(TAG, String.format(Locale.ROOT,
          "%s guard: frames=%d peakOut/target=%.2f maxFrame=%dKB hits=%d minScale=%.2f target=%dkbps",
          label, frames, peakRatio, maxFrameBytes / 1024, guardHits, minScale, targetBps / 1000));
    }
    statsStartMs = now;
    frames = 0;
    peakRatio = 0;
    maxFrameBytes = 0;
    guardHits = 0;
    minScale = scale;
  }
}

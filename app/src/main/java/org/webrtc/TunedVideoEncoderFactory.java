package org.webrtc;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;
import androidx.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Encoder factory tuned for low-latency screen casting.
 *
 * <p>Lives in {@code org.webrtc} to reach package-private libwebrtc classes ({@link HardwareVideoEncoder},
 * {@link MediaCodecWrapper}, {@link MediaCodecUtils}). It advertises exactly two codecs:
 * H.264 Constrained Baseline backed by the device's hardware AVC encoder (any vendor, selected by
 * {@link MediaCodecInfo#isHardwareAccelerated()}) and software VP8 as the fallback that libwebrtc
 * switches to automatically if the hardware encoder fails to initialise.
 *
 * <p>On top of the stock {@link HardwareVideoEncoder} it:
 * <ul>
 *   <li>adds real-time MediaCodec hints (priority, operating rate, no B-frames, 1-frame latency,
 *       Qualcomm low-latency extension) with a safe retry chain if a codec rejects them;</li>
 *   <li>turns the QP-based quality scaler off so resolution never "floats" down on static text.</li>
 * </ul>
 */
public class TunedVideoEncoderFactory implements VideoEncoderFactory {
  private static final String TAG = "LocalCast/Encoder";
  private static final String AVC = "video/avc";
  /** Periodic key frames are not needed: new viewers / losses request them via PLI/FIR. */
  private static final int KEY_FRAME_INTERVAL_SEC = 3600;

  /** Optional observer for diagnostics shown in the app. */
  public interface Listener {
    void onEncoderCreated(String codecName, boolean hardware);

    /**
     * @param otherEncodersActive true when other hardware encoder instances were running at the
     *     time — the failure is then most likely a resource limit, not a broken codec.
     */
    void onEncoderFailed(String codecName, String reason, boolean otherEncodersActive);
  }

  private final EglBase14.Context sharedContext;
  private final SoftwareVideoEncoderFactory software = new SoftwareVideoEncoderFactory();
  @Nullable private final MediaCodecInfo h264Codec;
  @Nullable private final Listener listener;
  private final boolean guardEnabled;
  private volatile boolean h264Disabled;
  /** Hardware encoder instances currently initialised or initialising (one per connected viewer). */
  private static final AtomicInteger liveHardwareEncoders = new AtomicInteger();
  private static final AtomicInteger encoderSerial = new AtomicInteger();

  public TunedVideoEncoderFactory(EglBase.Context sharedContext, @Nullable Listener listener) {
    this(sharedContext, listener, /* guardEnabled= */ true);
  }

  /**
   * @param guardEnabled use {@link GuardBitrateAdjuster}'s overshoot guard (on by default; the switch
   *     exists for A/B measurements).
   */
  public TunedVideoEncoderFactory(
      EglBase.Context sharedContext, @Nullable Listener listener, boolean guardEnabled) {
    if (!(sharedContext instanceof EglBase14.Context)) {
      throw new IllegalArgumentException("EGL14 context required");
    }
    this.sharedContext = (EglBase14.Context) sharedContext;
    this.listener = listener;
    this.guardEnabled = guardEnabled;
    this.h264Codec = findHardwareH264Encoder();
    android.util.Log.i(TAG, "Hardware H.264 encoder: " + (h264Codec == null ? "none" : h264Codec.getName()));
  }

  /** Name of the hardware AVC encoder that will be used, or null if the device has none. */
  @Nullable
  public String getHardwareH264Name() {
    return h264Codec == null ? null : h264Codec.getName();
  }

  /** Exclude H.264 from future negotiations (e.g. after the hardware encoder failed). */
  public void setH264Disabled(boolean disabled) {
    h264Disabled = disabled;
  }

  public boolean isH264Offered() {
    return h264Codec != null && !h264Disabled;
  }

  /** Hardware H.264 encoder instances that are running or initialising right now. */
  public static int getLiveHardwareEncoders() {
    return liveHardwareEncoders.get();
  }

  @Override
  public VideoCodecInfo[] getSupportedCodecs() {
    List<VideoCodecInfo> codecs = new ArrayList<>();
    if (isH264Offered()) {
      codecs.add(new VideoCodecInfo(VideoCodecMimeType.H264.name(),
          MediaCodecUtils.getCodecProperties(VideoCodecMimeType.H264, /* highProfile= */ false),
          new ArrayList<>()));
    }
    for (VideoCodecInfo info : software.getSupportedCodecs()) {
      if (VideoCodecMimeType.VP8.name().equalsIgnoreCase(info.name)) {
        codecs.add(info);
      }
    }
    return codecs.toArray(new VideoCodecInfo[0]);
  }

  @Nullable
  @Override
  public VideoEncoder createEncoder(VideoCodecInfo info) {
    if (VideoCodecMimeType.H264.name().equalsIgnoreCase(info.name)) {
      if (h264Codec == null) {
        return null;
      }
      if (!H264Utils.isSameH264Profile(
              info.params, MediaCodecUtils.getCodecProperties(VideoCodecMimeType.H264, false))) {
        Logging.w(TAG, "Unsupported H.264 profile requested: " + info.params);
        return null;
      }
      String name = h264Codec.getName();
      MediaCodecInfo.CodecCapabilities caps = h264Codec.getCapabilitiesForType(AVC);
      Integer surfaceColorFormat =
          MediaCodecUtils.selectColorFormat(MediaCodecUtils.TEXTURE_COLOR_FORMATS, caps);
      Integer yuvColorFormat =
          MediaCodecUtils.selectColorFormat(MediaCodecUtils.ENCODER_COLOR_FORMATS, caps);
      // Overshoot guard for every vendor; Exynos AVC encoders additionally ignore timestamps for
      // rate control (libwebrtc's FramerateBitrateAdjuster quirk, kept inside the guard).
      BitrateAdjuster adjuster = new GuardBitrateAdjuster(
          isExynos(name), guardEnabled, "h264#" + encoderSerial.incrementAndGet());
      HardwareVideoEncoder encoder = new HardwareVideoEncoder(new TunedCodecFactory(),
          name, VideoCodecMimeType.H264, surfaceColorFormat, yuvColorFormat, info.params,
          KEY_FRAME_INTERVAL_SEC, /* forceKeyFrameIntervalMs= */ 0, adjuster, sharedContext);
      if (listener != null) {
        listener.onEncoderCreated(name, true);
      }
      return new NoScalingEncoder(encoder, name, listener);
    }
    if (VideoCodecMimeType.VP8.name().equalsIgnoreCase(info.name)) {
      if (listener != null) {
        listener.onEncoderCreated("libvpx VP8", false);
      }
      return software.createEncoder(info);
    }
    return null;
  }

  private static boolean isExynos(String name) {
    return name.startsWith("OMX.Exynos.") || name.startsWith("c2.exynos.");
  }

  @Nullable
  private static MediaCodecInfo findHardwareH264Encoder() {
    MediaCodecInfo[] infos;
    try {
      infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
    } catch (RuntimeException e) {
      Logging.e(TAG, "MediaCodecList failed", e);
      return null;
    }
    for (MediaCodecInfo info : infos) {
      if (!info.isEncoder() || info.isAlias()) {
        continue;
      }
      String name = info.getName();
      if (name.contains(".secure") || !MediaCodecUtils.codecSupportsType(info, VideoCodecMimeType.H264)) {
        continue;
      }
      if (!info.isHardwareAccelerated() || info.isSoftwareOnly()) {
        continue;
      }
      try {
        MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(AVC);
        if (MediaCodecUtils.selectColorFormat(MediaCodecUtils.TEXTURE_COLOR_FORMATS, caps) == null) {
          continue; // Surface input is required for the zero-copy texture path.
        }
      } catch (IllegalArgumentException e) {
        continue;
      }
      return info;
    }
    return null;
  }

  /** Delegating encoder: forces the quality scaler off and reports init failures. */
  private static final class NoScalingEncoder implements VideoEncoder {
    private final VideoEncoder delegate;
    private final String codecName;
    @Nullable private final Listener listener;
    private boolean live;

    NoScalingEncoder(VideoEncoder delegate, String codecName, @Nullable Listener listener) {
      this.delegate = delegate;
      this.codecName = codecName;
      this.listener = listener;
    }

    @Override
    public VideoCodecStatus initEncode(Settings settings, Callback callback) {
      // Count this encoder before the MediaCodec init: viewers that initialise in parallel then see
      // each other, so one failure (e.g. out of hardware slots) never disables H.264 for everyone.
      if (!live) {
        live = true;
        liveHardwareEncoders.incrementAndGet();
      }
      VideoCodecStatus status = delegate.initEncode(settings, callback);
      if (status != VideoCodecStatus.OK) {
        live = false;
        int others = liveHardwareEncoders.decrementAndGet();
        if (listener != null) {
          listener.onEncoderFailed(codecName, "initEncode " + status, others > 0);
        }
      }
      return status;
    }

    @Override
    public VideoCodecStatus release() {
      if (live) {
        live = false;
        liveHardwareEncoders.decrementAndGet();
      }
      return delegate.release();
    }

    @Override
    public VideoCodecStatus encode(VideoFrame frame, EncodeInfo info) {
      return delegate.encode(frame, info);
    }

    @Override
    public VideoCodecStatus setRateAllocation(BitrateAllocation allocation, int framerate) {
      return delegate.setRateAllocation(allocation, framerate);
    }

    @Override
    public VideoCodecStatus setRates(RateControlParameters parameters) {
      return delegate.setRates(parameters);
    }

    @Override
    public ScalingSettings getScalingSettings() {
      return ScalingSettings.OFF;
    }

    @Override
    public ResolutionBitrateLimits[] getResolutionBitrateLimits() {
      return delegate.getResolutionBitrateLimits();
    }

    @Override
    public String getImplementationName() {
      return delegate.getImplementationName();
    }

    @Override
    public EncoderInfo getEncoderInfo() {
      return delegate.getEncoderInfo();
    }

    @Override
    public boolean isHardwareEncoder() {
      return true;
    }
  }

  /** Creates {@link TunedCodec}s. */
  private static final class TunedCodecFactory implements MediaCodecWrapperFactory {
    @Override
    public MediaCodecWrapper createByCodecName(String name) throws IOException {
      return new TunedCodec(MediaCodec.createByCodecName(name));
    }
  }

  /**
   * MediaCodec wrapper that adds low-latency hints in {@link #configure}. If the codec rejects the
   * tuned format it is reset and configured again with fewer hints, finally with the stock format.
   */
  private static final class TunedCodec implements MediaCodecWrapper {
    private static final String QTI_LOW_LATENCY = "vendor.qti-ext-enc-low-latency.enable";
    private final MediaCodec codec;

    TunedCodec(MediaCodec codec) {
      this.codec = codec;
    }

    @Override
    public void configure(MediaFormat format, Surface surface, MediaCrypto crypto, int flags) {
      MediaFormat full = withHints(format, /* vendorAndLatency= */ true);
      MediaFormat basic = withHints(format, /* vendorAndLatency= */ false);
      MediaFormat[] attempts = {full, basic, format};
      RuntimeException last = null;
      for (int i = 0; i < attempts.length; i++) {
        try {
          codec.configure(attempts[i], surface, crypto, flags);
          android.util.Log.i(TAG, codec.getName() + " configured (attempt " + (i + 1) + "): " + attempts[i]);
          return;
        } catch (IllegalArgumentException | IllegalStateException e) {
          last = e;
          android.util.Log.w(TAG, codec.getName() + " rejected format attempt " + (i + 1) + ": " + e);
          try {
            codec.reset();
          } catch (RuntimeException resetError) {
            Logging.e(TAG, "reset failed", resetError);
            break;
          }
        }
      }
      throw last != null ? last : new IllegalStateException("configure failed");
    }

    private MediaFormat withHints(MediaFormat source, boolean vendorAndLatency) {
      MediaFormat f = new MediaFormat(source);
      float fps = source.containsKey(MediaFormat.KEY_FRAME_RATE)
          ? source.getNumber(MediaFormat.KEY_FRAME_RATE, 30f).floatValue()
          : 30f;
      f.setInteger(MediaFormat.KEY_PRIORITY, 0); // 0 = real-time
      // A lone encoder is provisioned for >= 60 fps so each frame finishes as fast as possible.
      // With other sessions running, ask only for the real rate: over-provisioning one session can
      // eat the hardware budget the next viewer needs. (This encoder is already counted.)
      boolean othersRunning = liveHardwareEncoders.get() > 1;
      f.setFloat(MediaFormat.KEY_OPERATING_RATE, othersRunning ? fps : Math.max(fps, 60f));
      f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
      if (vendorAndLatency) {
        f.setInteger(MediaFormat.KEY_LATENCY, 1);
        // Vendor keys only when the codec declares them (API 31+); unknown keys can make
        // configure() fail on some HALs. API 30 cannot list them: keep the Qualcomm name check
        // there (a rejected format falls back to the next attempt).
        boolean qtiByName = Build.VERSION.SDK_INT < 31
            && (codec.getName().startsWith("c2.qti.") || codec.getName().startsWith("OMX.qcom."));
        if (qtiByName || supportsVendorParameter(QTI_LOW_LATENCY)) {
          f.setInteger(QTI_LOW_LATENCY, 1);
        }
      }
      return f;
    }

    private boolean supportsVendorParameter(String key) {
      if (Build.VERSION.SDK_INT < 31) return false;
      try {
        return codec.getSupportedVendorParameters().contains(key);
      } catch (RuntimeException e) {
        return false;
      }
    }

    @Override
    public void start() {
      codec.start();
    }

    @Override
    public void flush() {
      codec.flush();
    }

    @Override
    public void stop() {
      codec.stop();
    }

    @Override
    public void release() {
      codec.release();
    }

    @Override
    public int dequeueInputBuffer(long timeoutUs) {
      return codec.dequeueInputBuffer(timeoutUs);
    }

    @Override
    public void queueInputBuffer(int index, int offset, int size, long presentationTimeUs, int flags) {
      codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags);
    }

    @Override
    public int dequeueOutputBuffer(MediaCodec.BufferInfo info, long timeoutUs) {
      return codec.dequeueOutputBuffer(info, timeoutUs);
    }

    @Override
    public void releaseOutputBuffer(int index, boolean render) {
      codec.releaseOutputBuffer(index, render);
    }

    @Override
    public MediaFormat getInputFormat() {
      return codec.getInputFormat();
    }

    @Override
    public MediaFormat getOutputFormat() {
      return codec.getOutputFormat();
    }

    @Override
    public MediaFormat getOutputFormat(int index) {
      return codec.getOutputFormat(index);
    }

    @Override
    public ByteBuffer getInputBuffer(int index) {
      return codec.getInputBuffer(index);
    }

    @Override
    public ByteBuffer getOutputBuffer(int index) {
      return codec.getOutputBuffer(index);
    }

    @Override
    public Surface createInputSurface() {
      return codec.createInputSurface();
    }

    @Override
    public void setParameters(Bundle params) {
      codec.setParameters(params);
    }

    @Override
    public MediaCodecInfo getCodecInfo() {
      return codec.getCodecInfo();
    }
  }
}

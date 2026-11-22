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
  @Nullable private final MediaCodecInfo h265Codec;
  private volatile boolean h265Disabled;

  /**
   * Offer H.265 before H.264 (debug knob {@code hevc}). Browsers that decode it in hardware
   * (Chrome/Edge 136+, Safari 18+) pick it; all others answer with H.264 or VP8 as before.
   */
  public static volatile boolean hevcAllowed = true;
  @Nullable private final Listener listener;
  private final boolean guardEnabled;
  private volatile boolean h264Disabled;
  /** Hardware encoder instances currently initialised or initialising (one per connected viewer). */
  private static final AtomicInteger liveHardwareEncoders = new AtomicInteger();
  private static final AtomicInteger encoderSerial = new AtomicInteger();

  /**
   * Minimum interval between keyframes of one encoder ({@link KeyFrameThrottle}): a request that
   * comes sooner is served right after the interval. 0 = no throttling.
   */
  public static volatile int minKeyFrameIntervalMs = 1000;

  /**
   * Ask a lone hardware encoder to run at its maximum clock ({@code KEY_OPERATING_RATE =
   * Short.MAX_VALUE}) instead of the frame rate: measured encode time on a Snapdragon 7s Gen 3 was
   * 25–30 ms per frame at 60. A/B switch (debug knob {@code enc_max_rate}); see RtcEngine.
   */
  public static volatile boolean maxOperatingRate = false;

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
    this.h264Codec = findHardwareEncoder(VideoCodecMimeType.H264);
    this.h265Codec = findHardwareEncoder(VideoCodecMimeType.H265);
    android.util.Log.i(TAG, "Hardware H.264 encoder: " + (h264Codec == null ? "none" : h264Codec.getName())
        + ", H.265: " + (h265Codec == null ? "none" : h265Codec.getName()));
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

  /** Name of the hardware HEVC encoder, or null if the device has none. */
  @Nullable
  public String getHardwareH265Name() {
    return h265Codec == null ? null : h265Codec.getName();
  }

  /** Exclude H.265 from future negotiations (its hardware encoder failed). */
  public void setH265Disabled(boolean disabled) {
    h265Disabled = disabled;
  }

  public boolean isH265Offered() {
    return hevcAllowed && h265Codec != null && !h265Disabled && isH264Offered();
  }

  /** Hardware H.264 encoder instances that are running or initialising right now. */
  public static int getLiveHardwareEncoders() {
    return liveHardwareEncoders.get();
  }

  @Override
  public VideoCodecInfo[] getSupportedCodecs() {
    List<VideoCodecInfo> codecs = new ArrayList<>();
    if (isH265Offered()) {
      codecs.add(new VideoCodecInfo(VideoCodecMimeType.H265.name(),
          MediaCodecUtils.getCodecProperties(VideoCodecMimeType.H265, /* highProfile= */ false),
          new ArrayList<>()));
    }
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
    if (VideoCodecMimeType.H265.name().equalsIgnoreCase(info.name)) {
      if (h265Codec == null) {
        return null;
      }
      return createHardware(h265Codec, VideoCodecMimeType.H265, info, "h265#");
    }
    if (VideoCodecMimeType.H264.name().equalsIgnoreCase(info.name)) {
      if (h264Codec == null) {
        return null;
      }
      if (!H264Utils.isSameH264Profile(
              info.params, MediaCodecUtils.getCodecProperties(VideoCodecMimeType.H264, false))) {
        Logging.w(TAG, "Unsupported H.264 profile requested: " + info.params);
        return null;
      }
      return createHardware(h264Codec, VideoCodecMimeType.H264, info, "h264#");
    }
    if (VideoCodecMimeType.VP8.name().equalsIgnoreCase(info.name)) {
      if (listener != null) {
        listener.onEncoderCreated("libvpx VP8", false);
      }
      return software.createEncoder(info);
    }
    return null;
  }

  /** Hardware encoder for [type] behind the same guard, hints and keyframe throttle for every codec. */
  private VideoEncoder createHardware(
      MediaCodecInfo codec, VideoCodecMimeType type, VideoCodecInfo info, String label) {
    String name = codec.getName();
    MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType(type.mimeType());
    Integer surfaceColorFormat =
        MediaCodecUtils.selectColorFormat(MediaCodecUtils.TEXTURE_COLOR_FORMATS, caps);
    Integer yuvColorFormat =
        MediaCodecUtils.selectColorFormat(MediaCodecUtils.ENCODER_COLOR_FORMATS, caps);
    // Overshoot guard for every vendor; Exynos encoders additionally ignore timestamps for rate
    // control (libwebrtc's FramerateBitrateAdjuster quirk, kept inside the guard).
    BitrateAdjuster adjuster = new GuardBitrateAdjuster(
        isExynos(name), guardEnabled, label + encoderSerial.incrementAndGet());
    HardwareVideoEncoder encoder = new HardwareVideoEncoder(new TunedCodecFactory(),
        name, type, surfaceColorFormat, yuvColorFormat, info.params,
        KEY_FRAME_INTERVAL_SEC, /* forceKeyFrameIntervalMs= */ 0, adjuster, sharedContext);
    if (listener != null) {
      listener.onEncoderCreated(name, true);
    }
    return new NoScalingEncoder(encoder, name, listener);
  }

  private static boolean isExynos(String name) {
    return name.startsWith("OMX.Exynos.") || name.startsWith("c2.exynos.");
  }

  /** First hardware encoder for [type] with surface input (zero-copy texture path), or null. */
  @Nullable
  private static MediaCodecInfo findHardwareEncoder(VideoCodecMimeType type) {
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
      // ".secure" needs protected surfaces; ".cq"/".hdr" variants are for constant quality / HDR.
      if (name.contains(".secure") || name.endsWith(".cq") || name.endsWith(".hdr")
          || !MediaCodecUtils.codecSupportsType(info, type)) {
        continue;
      }
      if (!info.isHardwareAccelerated() || info.isSoftwareOnly()) {
        continue;
      }
      try {
        MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type.mimeType());
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
      keyFrames.reset();
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

    private final KeyFrameThrottle keyFrames = new KeyFrameThrottle();

    @Override
    public VideoCodecStatus encode(VideoFrame frame, EncodeInfo info) {
      boolean requested = info != null && requestsKeyFrame(info);
      boolean key = keyFrames.shouldEncodeKeyFrame(requested, System.nanoTime(), minKeyFrameIntervalMs);
      if (requested != key) {
        info = key ? KEY_ONLY : DELTA_ONLY;
      }
      VideoCodecStatus status = delegate.encode(frame, info);
      if (key && status != VideoCodecStatus.OK) {
        keyFrames.rejected();
      }
      return status;
    }

    private static final EncodeInfo DELTA_ONLY =
        new EncodeInfo(new EncodedImage.FrameType[] {EncodedImage.FrameType.VideoFrameDelta});
    private static final EncodeInfo KEY_ONLY =
        new EncodeInfo(new EncodedImage.FrameType[] {EncodedImage.FrameType.VideoFrameKey});

    private static boolean requestsKeyFrame(EncodeInfo info) {
      if (info.frameTypes == null) return false;
      for (EncodedImage.FrameType type : info.frameTypes) {
        if (type == EncodedImage.FrameType.VideoFrameKey) return true;
      }
      return false;
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
      f.setFloat(
          MediaFormat.KEY_OPERATING_RATE,
          othersRunning ? fps : maxOperatingRate ? (float) Short.MAX_VALUE : Math.max(fps, 60f));
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

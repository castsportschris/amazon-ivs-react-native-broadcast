package com.amazonivsreactnativebroadcast.IVSBroadcastCameraView;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.amazonaws.ivs.broadcast.AudioDevice;
import com.amazonaws.ivs.broadcast.SurfaceSource;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Manages a Camera2 capture pipeline that feeds frames to both the IVS Broadcast SDK
 * (for live streaming) and a local MediaCodec/MediaMuxer pipeline (for on-device MP4 recording).
 *
 * Parallel to the iOS IVSLocalRecordingService which uses AVCaptureSession + AVAssetWriter.
 *
 * Video path:
 *   Camera2 → multiple output surfaces:
 *     - TextureView (preview)
 *     - IVS SurfaceSource (live stream, when streaming)
 *     - MediaCodec H.264 encoder (local recording)
 *
 * Audio path:
 *   AudioRecord → PCM buffers →
 *     - IVS AudioDevice (live stream, when streaming)
 *     - MediaCodec AAC encoder → MediaMuxer (local recording)
 */
public class IVSLocalRecordingService {
  private static final String TAG = "IVSLocalRecording";
  private static final long DRAIN_TIMEOUT_US = 10_000; // 10ms

  // ── Callback interfaces ──────────────────────────────────────────────────
  interface RecordingSavedCallback {
    void onSaved(String uri);
  }

  interface ErrorCallback {
    void onError(String message);
  }

  // ── Callbacks ────────────────────────────────────────────────────────────
  RecordingSavedCallback onRecordingSaved;
  ErrorCallback onError;

  // ── IVS custom sources (set by session service before configure) ────────
  @Nullable SurfaceSource customImageSource;
  @Nullable AudioDevice customAudioSource;

  // ── Context ──────────────────────────────────────────────────────────────
  private Context context;

  // ── Camera2 ──────────────────────────────────────────────────────────────
  private CameraManager cameraManager;
  private CameraDevice cameraDevice;
  private CameraCaptureSession captureSession;
  private String cameraId;
  private HandlerThread cameraThread;
  private Handler cameraHandler;

  // ── Preview ──────────────────────────────────────────────────────────────
  private TextureView previewTextureView;
  private Surface previewSurface;
  // Remembered so we can re-apply the rotation transform after camera swaps / orientation changes
  private int lastViewWidth = 0;
  private int lastViewHeight = 0;

  // ── Video encoder ────────────────────────────────────────────────────────
  private MediaCodec videoEncoder;
  private Surface videoEncoderInputSurface;
  private MediaFormat videoOutputFormat;

  // ── Audio encoder ────────────────────────────────────────────────────────
  private MediaCodec audioEncoder;
  private MediaFormat audioOutputFormat;

  // ── Audio capture ────────────────────────────────────────────────────────
  private AudioRecord audioRecord;
  private Thread audioRecordThread;
  private volatile boolean isCapturingAudio = false;
  private int audioBufferSizeBytes;
  // Reusable direct ByteBuffer for feeding the IVS AudioDevice
  // (IVS requires allocateDirect + byte-count + PTS in microseconds)
  private ByteBuffer ivsAudioByteBuffer;

  // ── Muxer / recording state ──────────────────────────────────────────────
  private MediaMuxer mediaMuxer;
  private int videoTrackIndex = -1;
  private int audioTrackIndex = -1;
  private volatile boolean isMuxerStarted = false;
  private volatile boolean isRecording = false;
  private final Object muxerLock = new Object();
  // Single timestamp offset shared by audio + video tracks. Whichever track
  // produces output first establishes t=0; the other track is anchored to the
  // same reference so A/V sync is preserved.
  private long timestampOffset = -1;
  private String outputFilePath;

  // ── Encoder drain threads ────────────────────────────────────────────────
  private Thread videoDrainThread;
  private Thread audioDrainThread;
  private volatile boolean isDraining = false;

  // ── State ────────────────────────────────────────────────────────────────
  private volatile boolean isMuted = false;
  private boolean isConfigured = false;
  private String currentCameraFacing; // "front" or "back"

  // ── IVS thread marshalling ───────────────────────────────────────────────
  // The IVS BroadcastSession is created on the UI thread (via the View's
  // onAttachedToWindow → init() flow) and the SDK fires ERROR_WRONG_THREAD
  // (fatal) if any of its APIs — including custom-source appendBuffer — get
  // called from a different thread. Our audio capture runs on a background
  // thread, so we marshal each IVS appendBuffer call back to the main thread
  // through this handler.
  private final Handler ivsCallHandler = new Handler(Looper.getMainLooper());

  // ── Config ───────────────────────────────────────────────────────────────
  private int targetVideoWidth = 1280;
  private int targetVideoHeight = 720;
  private int videoWidth = 1280;
  private int videoHeight = 720;
  private int videoBitrate = 3_500_000;
  private static final int AUDIO_SAMPLE_RATE = 48_000;
  private static final int AUDIO_CHANNELS = 2;
  private static final int AUDIO_BITRATE = 96_000;
  private static final int VIDEO_FRAME_RATE = 30;
  private static final int VIDEO_I_FRAME_INTERVAL = 2;

  // ════════════════════════════════════════════════════════════════════════
  //  Public API
  // ════════════════════════════════════════════════════════════════════════

  /**
   * Set up the Camera2 pipeline and encoders. After calling this method,
   * retrieve the preview via {@link #getPreviewView()} and add it to the
   * view hierarchy. The camera will open once the TextureView's surface is
   * available.
   */
  public void configure(Context ctx, String cameraPosition, int width, int height, int bitrate) {
    if (isConfigured) {
      reportError("IVSLocalRecordingService already configured");
      return;
    }
    isConfigured = true;

    this.context = ctx;
    this.currentCameraFacing = cameraPosition;
    this.targetVideoWidth = width;
    this.targetVideoHeight = height;
    this.videoBitrate = bitrate;

    cameraManager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);

    cameraThread = new HandlerThread("IVSLocalRecordingCamera");
    cameraThread.start();
    cameraHandler = new Handler(cameraThread.getLooper());

    previewTextureView = new TextureView(ctx);
    previewTextureView.setSurfaceTextureListener(surfaceTextureListener);
  }

  /** Start writing encoded frames to a local MP4 file. */
  public void startRecording() {
    if (isRecording) return;

    if (videoOutputFormat == null || audioOutputFormat == null) {
      reportError("Encoders not ready — try again in a moment");
      return;
    }

    try {
      String fileName = "broadcast-" + UUID.randomUUID() + ".mp4";
      File outputFile = new File(context.getCacheDir(), fileName);
      if (outputFile.exists()) outputFile.delete();
      outputFilePath = outputFile.getAbsolutePath();

      synchronized (muxerLock) {
        mediaMuxer = new MediaMuxer(outputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // Rotation hint: tells the player (and MediaConvert via Rotate=AUTO)
        // how many degrees CW to rotate the encoded frames on playback.
        //
        // Camera2 outputs frames in sensor orientation. The sensor is
        // physically mounted at `sensorOrientation` degrees CW from the
        // device's natural (portrait) orientation. The display itself may
        // also be rotated. The rotation needed to make the video play
        // upright in the *current* display orientation is the difference:
        //
        //   hint = (sensorOrientation - displayRotation + 360) % 360
        //
        // Examples (back camera, sensor=90):
        //   - Portrait (display=0)    → hint=90   (rotate to portrait)
        //   - Landscape-L (display=90) → hint=0   (already landscape — no rotate)
        //   - Landscape-R (display=270) → hint=180 (flip)
        //
        // The previous unconditional `getSensorOrientation()` was always
        // writing 90° regardless of how the user was holding the device,
        // which caused MediaConvert (Rotate=AUTO) to rotate landscape
        // recordings into portrait dimensions on the HLS output.
        int sensorOrientation = getSensorOrientation();
        int displayRotation = getDisplayRotationDegrees();
        int orientationHint = (sensorOrientation - displayRotation + 360) % 360;
        mediaMuxer.setOrientationHint(orientationHint);
        Log.d(TAG, "Recording orientationHint=" + orientationHint
          + " (sensor=" + sensorOrientation + " display=" + displayRotation + ")");

        videoTrackIndex = mediaMuxer.addTrack(videoOutputFormat);
        audioTrackIndex = mediaMuxer.addTrack(audioOutputFormat);
        mediaMuxer.start();
        isMuxerStarted = true;

        timestampOffset = -1;
        isRecording = true;
      }
      Log.d(TAG, "Recording started → " + outputFilePath);
    } catch (IOException e) {
      reportError("Failed to start recording: " + e.getMessage());
    }
  }

  /** Stop recording, finalize the MP4, and save to the device gallery. */
  public void stopRecording() {
    if (!isRecording) return;

    synchronized (muxerLock) {
      isRecording = false;
    }

    // Allow drain threads to flush remaining buffers
    try { Thread.sleep(150); } catch (InterruptedException ignored) {}

    synchronized (muxerLock) {
      if (mediaMuxer != null) {
        try {
          if (isMuxerStarted) mediaMuxer.stop();
          mediaMuxer.release();
        } catch (Exception e) {
          Log.e(TAG, "Error stopping muxer", e);
        }
        mediaMuxer = null;
        isMuxerStarted = false;
      }
    }

    Log.d(TAG, "Recording stopped");

    if (outputFilePath != null) {
      final String path = outputFilePath;
      outputFilePath = null;
      new Thread(() -> saveToGallery(path), "GallerySaveThread").start();
    }
  }

  /** Switch between front and back camera. Safe to call while recording. */
  public void swapCamera() {
    currentCameraFacing = "front".equals(currentCameraFacing) ? "back" : "front";

    if (captureSession != null) {
      try { captureSession.stopRepeating(); } catch (Exception ignored) {}
      try { captureSession.close(); } catch (Exception ignored) {}
      captureSession = null;
    }
    if (cameraDevice != null) {
      cameraDevice.close();
      cameraDevice = null;
    }

    openCamera();
  }

  public void setMuted(boolean muted) {
    this.isMuted = muted;
  }

  public TextureView getPreviewView() {
    return previewTextureView;
  }

  /**
   * Stop feeding the IVS BroadcastSession (audio appendBuffer + image source).
   * Called when the broadcast is stopped so that the audio capture thread
   * (which keeps running until tearDown) doesn't post appendBuffer calls
   * against a stopped or about-to-be-released native AudioDevice.
   *
   * IVS's stopped/released state is not crash-safe under appendBuffer — the
   * source can be in an intermediate state where the JNI dispatches but the
   * native handle is invalid, producing SIGSEGV inside
   * Java_com_amazonaws_ivs_broadcast_AudioSource_appendBuffer.
   *
   * Idempotent: safe to call multiple times. Local audio capture and the
   * MediaCodec local-recording pipeline are unaffected.
   */
  public void disableIvsFeeds() {
    // Skip the IVS branch in the audio capture loop
    customAudioSource = null;
    customImageSource = null;
    // Drop any already-queued appendBuffer runnables so they can't fire
    // against the soon-to-be-stopped/released session
    ivsCallHandler.removeCallbacksAndMessages(null);
  }

  /** Release all resources. Stops recording if active. */
  public void tearDown() {
    if (isRecording) {
      stopRecording();
    }

    // Audio capture
    isCapturingAudio = false;
    joinThread(audioRecordThread, 1000);
    audioRecordThread = null;
    if (audioRecord != null) {
      try { audioRecord.stop(); } catch (Exception ignored) {}
      audioRecord.release();
      audioRecord = null;
    }

    // Drop any pending IVS appendBuffer runnables that were posted just
    // before the audio thread exited. The session service is about to
    // release the BroadcastSession (and the AudioDevice with it) — running
    // those leftover appendBuffer calls afterward would dispatch into a
    // released native object and crash. removeCallbacksAndMessages with a
    // null token clears the entire queue for this Handler.
    ivsCallHandler.removeCallbacksAndMessages(null);
    // Also drop our reference so anything that did slip through the queue
    // (impossible after the line above, but defensive) finds null instead
    // of a dangling source.
    customAudioSource = null;
    customImageSource = null;

    // Drain threads
    isDraining = false;
    joinThread(videoDrainThread, 1000);
    joinThread(audioDrainThread, 1000);
    videoDrainThread = null;
    audioDrainThread = null;

    // Camera
    if (captureSession != null) {
      try { captureSession.close(); } catch (Exception ignored) {}
      captureSession = null;
    }
    if (cameraDevice != null) {
      cameraDevice.close();
      cameraDevice = null;
    }

    // Encoders
    releaseEncoder(videoEncoder);
    videoEncoder = null;
    releaseEncoder(audioEncoder);
    audioEncoder = null;

    // Surfaces
    if (videoEncoderInputSurface != null) {
      videoEncoderInputSurface.release();
      videoEncoderInputSurface = null;
    }
    if (previewSurface != null) {
      previewSurface.release();
      previewSurface = null;
    }

    // Camera thread
    if (cameraThread != null) {
      cameraThread.quitSafely();
      joinThread(cameraThread, 500);
      cameraThread = null;
      cameraHandler = null;
    }

    previewTextureView = null;
    isConfigured = false;
  }

  // ════════════════════════════════════════════════════════════════════════
  //  TextureView lifecycle
  // ════════════════════════════════════════════════════════════════════════

  private final TextureView.SurfaceTextureListener surfaceTextureListener =
    new TextureView.SurfaceTextureListener() {
      @Override
      public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int w, int h) {
        lastViewWidth = w;
        lastViewHeight = h;
        onPreviewSurfaceReady(surface);
      }

      @Override
      public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int w, int h) {
        lastViewWidth = w;
        lastViewHeight = h;
        configurePreviewTransform(w, h);
      }

      @Override
      public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        return true;
      }

      @Override
      public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
    };

  private void onPreviewSurfaceReady(SurfaceTexture surfaceTexture) {
    try {
      cameraId = getCameraId(currentCameraFacing);
      if (cameraId == null) {
        reportError("No camera found for position: " + currentCameraFacing);
        return;
      }

      // Pick a supported output size closest to the target
      CameraCharacteristics chars = cameraManager.getCameraCharacteristics(cameraId);
      StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      if (map != null) {
        Size bestSize = chooseBestSize(map.getOutputSizes(SurfaceTexture.class), targetVideoWidth, targetVideoHeight);
        videoWidth = bestSize.getWidth();
        videoHeight = bestSize.getHeight();
      } else {
        videoWidth = targetVideoWidth;
        videoHeight = targetVideoHeight;
      }

      // Setup encoders at the resolved size
      setupVideoEncoder();
      setupAudioEncoder();
      setupAudioRecord();
      startDrainThreads();

      surfaceTexture.setDefaultBufferSize(videoWidth, videoHeight);
      previewSurface = new Surface(surfaceTexture);

      // Rotate the preview to match sensor + display orientation
      configurePreviewTransform(lastViewWidth, lastViewHeight);

      openCamera();
    } catch (Exception e) {
      reportError("Failed to set up camera pipeline: " + e.getMessage());
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  //  Camera2 lifecycle
  // ════════════════════════════════════════════════════════════════════════

  @SuppressLint("MissingPermission")
  private void openCamera() {
    try {
      if (cameraId == null) {
        cameraId = getCameraId(currentCameraFacing);
      }
      if (cameraId == null) {
        reportError("No camera found for position: " + currentCameraFacing);
        return;
      }
      cameraManager.openCamera(cameraId, cameraStateCallback, cameraHandler);
    } catch (CameraAccessException | SecurityException e) {
      reportError("Failed to open camera: " + e.getMessage());
    }
  }

  private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
    @Override
    public void onOpened(@NonNull CameraDevice camera) {
      cameraDevice = camera;
      // Re-apply preview transform on the main thread (sensor orientation may
      // have changed — e.g. front↔back camera swap)
      final int w = lastViewWidth;
      final int h = lastViewHeight;
      if (w > 0 && h > 0) {
        new Handler(Looper.getMainLooper()).post(() -> configurePreviewTransform(w, h));
      }
      createCaptureSession();
    }

    @Override
    public void onDisconnected(@NonNull CameraDevice camera) {
      camera.close();
      cameraDevice = null;
    }

    @Override
    public void onError(@NonNull CameraDevice camera, int error) {
      camera.close();
      cameraDevice = null;
      reportError("Camera device error: " + error);
    }
  };

  private void createCaptureSession() {
    if (cameraDevice == null || previewSurface == null) return;

    try {
      List<Surface> surfaces = new ArrayList<>();
      surfaces.add(previewSurface);

      if (customImageSource != null) {
        Surface ivsSurface = customImageSource.getInputSurface();
        if (ivsSurface != null) surfaces.add(ivsSurface);
      }

      if (videoEncoderInputSurface != null) {
        surfaces.add(videoEncoderInputSurface);
      }

      cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
          captureSession = session;
          startRepeatingRequest();
          startAudioCapture();
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
          reportError("Camera capture session configuration failed");
        }
      }, cameraHandler);
    } catch (CameraAccessException e) {
      reportError("Failed to create capture session: " + e.getMessage());
    }
  }

  private void startRepeatingRequest() {
    if (cameraDevice == null || captureSession == null) return;

    try {
      CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
      builder.addTarget(previewSurface);

      if (customImageSource != null) {
        Surface ivsSurface = customImageSource.getInputSurface();
        if (ivsSurface != null) builder.addTarget(ivsSurface);
      }
      if (videoEncoderInputSurface != null) {
        builder.addTarget(videoEncoderInputSurface);
      }

      builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);

      captureSession.setRepeatingRequest(builder.build(), null, cameraHandler);
    } catch (CameraAccessException e) {
      reportError("Failed to start camera capture: " + e.getMessage());
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  //  Encoder setup
  // ════════════════════════════════════════════════════════════════════════

  private void setupVideoEncoder() {
    try {
      MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight);
      format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
      format.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
      format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE);
      format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL);

      videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
      videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      videoEncoderInputSurface = videoEncoder.createInputSurface();
      videoEncoder.start();
    } catch (IOException e) {
      reportError("Failed to set up video encoder: " + e.getMessage());
    }
  }

  private void setupAudioEncoder() {
    try {
      MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS);
      format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
      format.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);

      audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
      audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      audioEncoder.start();
    } catch (IOException e) {
      reportError("Failed to set up audio encoder: " + e.getMessage());
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  //  Audio capture (AudioRecord → IVS + encoder)
  // ════════════════════════════════════════════════════════════════════════

  @SuppressLint("MissingPermission")
  private void setupAudioRecord() {
    int channelConfig = AUDIO_CHANNELS == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
    int minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
    audioBufferSizeBytes = Math.max(minBuf, 4096);

    audioRecord = new AudioRecord(
      MediaRecorder.AudioSource.MIC,
      AUDIO_SAMPLE_RATE,
      channelConfig,
      AudioFormat.ENCODING_PCM_16BIT,
      audioBufferSizeBytes * 2
    );

    // Pre-allocate a direct ByteBuffer for IVS AudioDevice.appendBuffer
    // (IVS requires a direct buffer)
    ivsAudioByteBuffer = ByteBuffer.allocateDirect(audioBufferSizeBytes).order(ByteOrder.nativeOrder());
  }

  private void startAudioCapture() {
    if (audioRecord == null || isCapturingAudio) return;

    isCapturingAudio = true;
    audioRecord.startRecording();

    audioRecordThread = new Thread(() -> {
      int shortBufLen = audioBufferSizeBytes / 2;
      short[] buffer = new short[shortBufLen];

      while (isCapturingAudio) {
        int shortsRead = audioRecord.read(buffer, 0, shortBufLen);
        if (shortsRead <= 0) continue;

        long timestampUs = System.nanoTime() / 1_000;

        // Route to IVS AudioDevice (live streaming)
        //
        // The IVS BroadcastSession is created on the UI thread; calling
        // appendBuffer directly from this background audio thread triggers
        // ERROR_WRONG_THREAD (fatal) and tears down the broadcast. Marshal
        // the call to the UI thread via ivsCallHandler.
        //
        // Signature: appendBuffer(ByteBuffer direct, long byteCount, long ptsMicroseconds)
        AudioDevice ivsSource = customAudioSource;
        if (ivsSource != null && !isMuted) {
          final int byteCount = shortsRead * 2; // 16-bit PCM = 2 bytes per sample
          // Allocate a fresh direct ByteBuffer per call. We can't reuse a
          // shared buffer because the post is async — the audio thread would
          // overwrite it before the UI thread consumes it.
          final ByteBuffer ivsBuf;
          try {
            ivsBuf = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder());
            ivsBuf.asShortBuffer().put(buffer, 0, shortsRead);
            ivsBuf.position(0);
            ivsBuf.limit(byteCount);
          } catch (Exception e) {
            Log.w(TAG, "Failed to allocate IVS audio buffer: " + e.getMessage());
            // Skip IVS feed for this chunk; still continue to encode locally below
            feedAudioEncoder(buffer, shortsRead, timestampUs);
            continue;
          }
          final long pts = timestampUs;
          final AudioDevice srcCaptured = ivsSource;
          ivsCallHandler.post(() -> {
            // customAudioSource may have been nulled in tearDown by the time
            // this runs; the captured `srcCaptured` reference is still safe to
            // use for this single call.
            try {
              srcCaptured.appendBuffer(ivsBuf, byteCount, pts);
            } catch (Exception e) {
              Log.w(TAG, "IVS appendBuffer error: " + e.getMessage());
            }
          });
        }

        // Route to audio encoder (local recording)
        feedAudioEncoder(buffer, shortsRead, timestampUs);
      }
    }, "IVSAudioCaptureThread");
    audioRecordThread.start();
  }

  /**
   * Queue PCM audio into the MediaCodec AAC encoder. The encoder's input buffers
   * are typically sized to exactly one AAC frame (~4096 bytes for stereo 16-bit),
   * but AudioRecord may deliver larger reads depending on the device's minimum
   * buffer size. We chunk the write across multiple encoder input buffers as
   * needed, advancing the PTS by the number of frames already consumed.
   */
  private void feedAudioEncoder(short[] samples, int count, long baseTimestampUs) {
    if (audioEncoder == null || count <= 0) return;

    int shortsRemaining = count;
    int shortOffset = 0;

    while (shortsRemaining > 0) {
      int inputIndex;
      try {
        inputIndex = audioEncoder.dequeueInputBuffer(10_000);
      } catch (Exception e) {
        return;
      }
      if (inputIndex < 0) {
        // Encoder too busy — drop the remainder rather than stall the audio thread
        return;
      }

      ByteBuffer inputBuffer;
      try {
        inputBuffer = audioEncoder.getInputBuffer(inputIndex);
      } catch (Exception e) {
        return;
      }
      if (inputBuffer == null) {
        try { audioEncoder.queueInputBuffer(inputIndex, 0, 0, 0, 0); } catch (Exception ignored) {}
        return;
      }

      inputBuffer.clear();
      inputBuffer.order(ByteOrder.nativeOrder());

      // Fit as many shorts as the encoder's input buffer can hold
      int maxShortsThisBuffer = inputBuffer.remaining() / 2;
      if (maxShortsThisBuffer <= 0) {
        try { audioEncoder.queueInputBuffer(inputIndex, 0, 0, 0, 0); } catch (Exception ignored) {}
        return;
      }
      int shortsToWrite = Math.min(shortsRemaining, maxShortsThisBuffer);
      int bytesToWrite = shortsToWrite * 2;

      inputBuffer.asShortBuffer().put(samples, shortOffset, shortsToWrite);

      // Shift PTS by how many audio frames we've already queued in this call
      long frameOffset = shortOffset / AUDIO_CHANNELS;
      long chunkTimestampUs = baseTimestampUs + (frameOffset * 1_000_000L / AUDIO_SAMPLE_RATE);

      try {
        audioEncoder.queueInputBuffer(inputIndex, 0, bytesToWrite, chunkTimestampUs, 0);
      } catch (Exception e) {
        Log.w(TAG, "Audio encoder queueInputBuffer error: " + e.getMessage());
        return;
      }

      shortOffset += shortsToWrite;
      shortsRemaining -= shortsToWrite;
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  //  Encoder drain threads
  // ════════════════════════════════════════════════════════════════════════

  private void startDrainThreads() {
    isDraining = true;

    videoDrainThread = new Thread(() -> drainLoop(videoEncoder, true), "IVSVideoDrainThread");
    videoDrainThread.start();

    audioDrainThread = new Thread(() -> drainLoop(audioEncoder, false), "IVSAudioDrainThread");
    audioDrainThread.start();
  }

  /**
   * Continuously dequeue encoded output from the given encoder.
   * When recording, writes the data to the MediaMuxer with adjusted timestamps.
   */
  private void drainLoop(MediaCodec encoder, boolean isVideo) {
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    while (isDraining) {
      if (encoder == null) break;

      int result;
      try {
        result = encoder.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US);
      } catch (Exception e) {
        break;
      }

      if (result == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        if (isVideo) {
          videoOutputFormat = encoder.getOutputFormat();
          Log.d(TAG, "Video output format: " + videoOutputFormat);
        } else {
          audioOutputFormat = encoder.getOutputFormat();
          Log.d(TAG, "Audio output format: " + audioOutputFormat);
        }
      } else if (result >= 0) {
        ByteBuffer outputBuffer = encoder.getOutputBuffer(result);
        if (outputBuffer != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
          synchronized (muxerLock) {
            if (isRecording && isMuxerStarted && mediaMuxer != null) {
              int trackIndex = isVideo ? videoTrackIndex : audioTrackIndex;
              long pts = info.presentationTimeUs;

              // Anchor both tracks to the same t=0 to preserve A/V sync
              if (timestampOffset < 0) timestampOffset = pts;
              info.presentationTimeUs = Math.max(0, pts - timestampOffset);

              try {
                mediaMuxer.writeSampleData(trackIndex, outputBuffer, info);
              } catch (Exception e) {
                Log.w(TAG, "Muxer write error: " + e.getMessage());
              }
            }
          }
        }
        try {
          encoder.releaseOutputBuffer(result, false);
        } catch (Exception ignored) {}
      }
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  //  Gallery save (ContentResolver + MediaStore)
  // ════════════════════════════════════════════════════════════════════════

  /**
   * Finalize the recording: copy a copy to the device gallery (best-effort)
   * and notify JS with a `file://` URI of the original temp file. Matches
   * the iOS contract — JS receives a file URI it can hand to Expo
   * FileSystem.uploadTaskStartAsync (which doesn't accept content:// URIs)
   * and is responsible for deleting the temp file after upload completes.
   */
  private void saveToGallery(String filePath) {
    File file = new File(filePath);
    if (!file.exists()) {
      reportError("Recording file not found");
      return;
    }

    // Best-effort: copy to MediaStore so it shows up in the user's gallery.
    // A failure here is non-fatal — the upload-able file:// URI is what JS needs.
    try {
      copyToMediaStore(file);
    } catch (Exception e) {
      Log.w(TAG, "Failed to copy recording to gallery (continuing): " + e.getMessage());
    }

    // Hand JS the file:// URI. The temp file is intentionally NOT deleted —
    // the JS layer needs it to upload, and should call FileSystem.deleteAsync
    // once the upload finishes.
    final String fileUri = Uri.fromFile(file).toString();
    long sizeBytes = file.length();
    Log.d(TAG, "Recording saved → " + fileUri + " (" + sizeBytes + " bytes, "
      + (sizeBytes / 1024 / 1024) + " MB)");

    if (sizeBytes == 0) {
      reportError("Recording file is empty (0 bytes) — the muxer may have failed");
      return;
    }

    new Handler(Looper.getMainLooper()).post(() -> {
      if (onRecordingSaved != null) {
        onRecordingSaved.onSaved(fileUri);
      }
    });
  }

  private void copyToMediaStore(File file) throws IOException {
    ContentValues values = new ContentValues();
    values.put(MediaStore.Video.Media.DISPLAY_NAME, file.getName());
    values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES);
      values.put(MediaStore.Video.Media.IS_PENDING, 1);
    }

    ContentResolver resolver = context.getContentResolver();
    Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

    if (uri == null) {
      throw new IOException("Failed to create MediaStore entry");
    }

    try (OutputStream os = resolver.openOutputStream(uri);
         FileInputStream fis = new FileInputStream(file)) {
      byte[] buf = new byte[8192];
      int len;
      while ((len = fis.read(buf)) != -1) {
        os.write(buf, 0, len);
      }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      values.clear();
      values.put(MediaStore.Video.Media.IS_PENDING, 0);
      resolver.update(uri, values, null, null);
    }

    Log.d(TAG, "Gallery copy → " + uri);
  }

  // ════════════════════════════════════════════════════════════════════════
  //  Camera helpers
  // ════════════════════════════════════════════════════════════════════════

  @Nullable
  private String getCameraId(String facing) {
    try {
      int target = "front".equals(facing)
        ? CameraCharacteristics.LENS_FACING_FRONT
        : CameraCharacteristics.LENS_FACING_BACK;

      for (String id : cameraManager.getCameraIdList()) {
        CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
        Integer lensFacing = c.get(CameraCharacteristics.LENS_FACING);
        if (lensFacing != null && lensFacing == target) return id;
      }
    } catch (CameraAccessException e) {
      reportError("Failed to enumerate cameras: " + e.getMessage());
    }
    return null;
  }

  /**
   * Rotate the TextureView's content so the camera frames appear upright.
   *
   * The sensor buffer has a fixed orientation relative to the device (e.g.
   * back camera = 90°, front = 270°), meaning raw pixel row 0 corresponds to
   * a direction that is `sensorOrientation` degrees CW from the device's
   * natural "up".
   *
   * When the activity's orientation matches what the user is seeing (via an
   * orientation lock like expo-screen-orientation, or because the system
   * auto-rotates the view), the TextureView's coordinate system already
   * aligns with the user's perceived orientation — so we only need to undo
   * the sensor's own rotation, which is exactly `sensorOrientation`. The
   * display rotation does NOT enter the calculation in that case (the view
   * is already in the user-perceived coordinate system).
   *
   * The earlier `(sensor − display)` formula is for portrait-locked activities
   * that need to counteract the user's physical device rotation; that's not
   * our situation.
   */
  private void configurePreviewTransform(int viewWidth, int viewHeight) {
    if (previewTextureView == null || viewWidth <= 0 || viewHeight <= 0) return;

    int sensorOrientation = getSensorOrientation();
    // Rotate counter-clockwise by sensorOrientation. The previous
    // `+sensorOrientation` (CW) was 180° off — for back camera we need to
    // rotate the buffer the *opposite* way to bring scene-up to view-top.
    int totalRotation = (360 - sensorOrientation) % 360;

    Log.d(TAG, "configurePreviewTransform: view=" + viewWidth + "x" + viewHeight
      + " video=" + videoWidth + "x" + videoHeight
      + " sensor=" + sensorOrientation
      + " display=" + getDisplayRotationDegrees()
      + " → rotate=" + totalRotation);

    Matrix matrix = new Matrix();
    float centerX = viewWidth * 0.5f;
    float centerY = viewHeight * 0.5f;

    if (totalRotation == 180) {
      matrix.postRotate(180f, centerX, centerY);
    } else if (totalRotation == 90 || totalRotation == 270) {
      // After a 90°/270° rotation the buffer's width and height are effectively
      // swapped in the view. Scale-to-fit so the rotated content fills the view,
      // then rotate.
      RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
      RectF bufferRect = new RectF(0, 0, videoHeight, videoWidth);
      bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
      matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
      float scale = Math.max(
        (float) viewHeight / videoHeight,
        (float) viewWidth / videoWidth
      );
      matrix.postScale(scale, scale, centerX, centerY);
      matrix.postRotate(totalRotation, centerX, centerY);
    }
    // totalRotation == 0: identity matrix, no transform needed

    previewTextureView.setTransform(matrix);
  }

  private int getDisplayRotationDegrees() {
    try {
      WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
      if (wm == null) return 0;
      int rotation = wm.getDefaultDisplay().getRotation();
      switch (rotation) {
        case Surface.ROTATION_0:   return 0;
        case Surface.ROTATION_90:  return 90;
        case Surface.ROTATION_180: return 180;
        case Surface.ROTATION_270: return 270;
      }
    } catch (Exception ignored) {}
    return 0;
  }

  private int getSensorOrientation() {
    try {
      if (cameraId == null) return 0;
      CameraCharacteristics c = cameraManager.getCameraCharacteristics(cameraId);
      Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
      return orientation != null ? orientation : 0;
    } catch (CameraAccessException e) {
      return 0;
    }
  }

  /**
   * Pick the supported output size closest in area to the target dimensions.
   * Prefers an exact match.
   */
  private Size chooseBestSize(Size[] choices, int targetW, int targetH) {
    if (choices == null || choices.length == 0) return new Size(targetW, targetH);

    int targetArea = targetW * targetH;
    Size best = choices[0];
    int bestDiff = Math.abs(best.getWidth() * best.getHeight() - targetArea);

    for (Size size : choices) {
      if (size.getWidth() == targetW && size.getHeight() == targetH) return size;
      int diff = Math.abs(size.getWidth() * size.getHeight() - targetArea);
      if (diff < bestDiff) {
        bestDiff = diff;
        best = size;
      }
    }
    return best;
  }

  // ════════════════════════════════════════════════════════════════════════
  //  Utilities
  // ════════════════════════════════════════════════════════════════════════

  private void reportError(String message) {
    Log.e(TAG, message);
    if (onError != null) {
      new Handler(Looper.getMainLooper()).post(() -> onError.onError(message));
    }
  }

  private static void joinThread(@Nullable Thread t, long timeoutMs) {
    if (t != null) {
      try { t.join(timeoutMs); } catch (InterruptedException ignored) {}
    }
  }

  private static void releaseEncoder(@Nullable MediaCodec codec) {
    if (codec != null) {
      try { codec.stop(); } catch (Exception ignored) {}
      try { codec.release(); } catch (Exception ignored) {}
    }
  }
}

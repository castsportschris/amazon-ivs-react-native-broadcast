package com.amazonivsreactnativebroadcast.IVSBroadcastCameraView;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.amazonaws.ivs.broadcast.AudioDevice;
import com.amazonaws.ivs.broadcast.SurfaceSource;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
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

  // ── Muxer / recording state ──────────────────────────────────────────────
  private MediaMuxer mediaMuxer;
  private int videoTrackIndex = -1;
  private int audioTrackIndex = -1;
  private volatile boolean isMuxerStarted = false;
  private volatile boolean isRecording = false;
  private final Object muxerLock = new Object();
  private long videoTimestampOffset = -1;
  private long audioTimestampOffset = -1;
  private String outputFilePath;

  // ── Encoder drain threads ────────────────────────────────────────────────
  private Thread videoDrainThread;
  private Thread audioDrainThread;
  private volatile boolean isDraining = false;

  // ── State ────────────────────────────────────────────────────────────────
  private volatile boolean isMuted = false;
  private boolean isConfigured = false;
  private String currentCameraFacing; // "front" or "back"

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
        mediaMuxer.setOrientationHint(getSensorOrientation());

        videoTrackIndex = mediaMuxer.addTrack(videoOutputFormat);
        audioTrackIndex = mediaMuxer.addTrack(audioOutputFormat);
        mediaMuxer.start();
        isMuxerStarted = true;

        videoTimestampOffset = -1;
        audioTimestampOffset = -1;
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
        onPreviewSurfaceReady(surface);
      }

      @Override
      public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int w, int h) {}

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
        if (customAudioSource != null && !isMuted) {
          short[] ivsBuffer = (shortsRead == shortBufLen) ? buffer : Arrays.copyOf(buffer, shortsRead);
          try {
            customAudioSource.appendBuffer(ivsBuffer);
          } catch (Exception e) {
            Log.w(TAG, "IVS appendBuffer error: " + e.getMessage());
          }
        }

        // Route to audio encoder (local recording)
        feedAudioEncoder(buffer, shortsRead, timestampUs);
      }
    }, "IVSAudioCaptureThread");
    audioRecordThread.start();
  }

  private void feedAudioEncoder(short[] samples, int count, long timestampUs) {
    if (audioEncoder == null) return;
    int inputIndex = audioEncoder.dequeueInputBuffer(0);
    if (inputIndex < 0) return;

    ByteBuffer inputBuffer = audioEncoder.getInputBuffer(inputIndex);
    if (inputBuffer == null) return;
    inputBuffer.clear();
    inputBuffer.asShortBuffer().put(samples, 0, count);
    audioEncoder.queueInputBuffer(inputIndex, 0, count * 2, timestampUs, 0);
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

              if (isVideo) {
                if (videoTimestampOffset < 0) videoTimestampOffset = pts;
                info.presentationTimeUs = pts - videoTimestampOffset;
              } else {
                if (audioTimestampOffset < 0) audioTimestampOffset = pts;
                info.presentationTimeUs = pts - audioTimestampOffset;
              }

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

  private void saveToGallery(String filePath) {
    File file = new File(filePath);
    if (!file.exists()) {
      reportError("Recording file not found");
      return;
    }

    try {
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
        reportError("Failed to create MediaStore entry");
        file.delete();
        return;
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

      file.delete();

      final String savedUri = uri.toString();
      Log.d(TAG, "Recording saved → " + savedUri);
      new Handler(Looper.getMainLooper()).post(() -> {
        if (onRecordingSaved != null) {
          onRecordingSaved.onSaved(savedUri);
        }
      });
    } catch (Exception e) {
      reportError("Failed to save recording to gallery: " + e.getMessage());
      file.delete();
    }
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

package com.amazonivsreactnativebroadcast.IVSBroadcastCameraView;

import android.view.View;

import com.amazonaws.ivs.broadcast.*;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.ThemedReactContext;

@FunctionalInterface
interface CameraPreviewHandler {
  void run(View cameraPreview);
}

@FunctionalInterface
interface RunnableCallback {
  void run(IVSBroadcastSessionService.Events event, @Nullable WritableMap eventPayload);
}

// Guide: https://docs.aws.amazon.com/ivs/latest/userguide//broadcast-android.html
public class IVSBroadcastSessionService {
  private ThemedReactContext mReactContext;

  private boolean isInitialMuted = false;
  private Device.Descriptor.Position initialCameraPosition = Device.Descriptor.Position.BACK;
  private BroadcastConfiguration.LogLevel initialSessionLogLevel = BroadcastConfiguration.LogLevel.ERROR;
  private boolean isCameraPreviewMirrored = false;
  private BroadcastConfiguration.AspectMode cameraPreviewAspectMode = BroadcastConfiguration.AspectMode.NONE;
  private ReadableMap customVideoConfig;
  private ReadableMap customAudioConfig;

  private Device.Descriptor attachedCameraDescriptor;
  private Device.Descriptor attachedMicrophoneDescriptor;

  private String sessionId;
  private BroadcastSession broadcastSession;
  private BroadcastConfiguration config = new BroadcastConfiguration();

  // ── Local recording support ──────────────────────────────────────────
  boolean isLocalRecordingEnabled = false;
  boolean isRecordOnlyMode = false;
  private IVSLocalRecordingService localRecordingService;
  private SurfaceSource customImageSource;
  private AudioDevice customAudioSource;

  private RunnableCallback broadcastEventHandler;
  private final BroadcastSession.Listener broadcastSessionListener = new BroadcastSession.Listener() {
    @Override
    public void onError(@NonNull BroadcastException exception) {
      int code = exception.getCode();
      String detail = exception.getDetail();
      String source = exception.getSource();
      boolean isFatal = exception.isFatal();
      String type = exception.getError().name();

      WritableMap eventPayload = Arguments.createMap();
      WritableMap broadcastException = Arguments.createMap();

      broadcastException.putInt("code", code);
      broadcastException.putString("detail", detail);
      broadcastException.putString("source", source);
      broadcastException.putBoolean("isFatal", isFatal);
      broadcastException.putString("type", type);
      broadcastException.putString("sessionId", sessionId);

      eventPayload.putMap("exception", broadcastException);

      broadcastEventHandler.run(Events.ON_ERROR, eventPayload);
    }

    @Override
    public void onStateChanged(@NonNull BroadcastSession.State state) {
      WritableMap eventPayload = Arguments.createMap();
      eventPayload.putString("stateStatus", state.toString());

      if (state == BroadcastSession.State.CONNECTED) {
        WritableMap metadata = Arguments.createMap();
        metadata.putString("sessionId", sessionId);
        eventPayload.putMap("metadata", metadata);
      }

      broadcastEventHandler.run(Events.ON_STATE_CHANGED, eventPayload);
    }

    @Override
    public void onAudioStats(double peak, double rms) {
      WritableMap eventPayload = Arguments.createMap();
      WritableMap audioStats = Arguments.createMap();

      audioStats.putDouble("peak", peak);
      audioStats.putDouble("rms", rms);

      eventPayload.putMap("audioStats", audioStats);

      broadcastEventHandler.run(Events.ON_AUDIO_STATS, eventPayload);
    }

    @Override
    public void onTransmissionStatsChanged(@NonNull TransmissionStats statistics) {
      WritableMap statisticsPayload = Arguments.createMap();
      statisticsPayload.putDouble("rtt", statistics.roundTripTime);
      statisticsPayload.putDouble("measuredBitrate", statistics.measuredBitrate);
      statisticsPayload.putDouble("recommendedBitrate", statistics.recommendedBitrate);
      statisticsPayload.putString("networkHealth", statistics.networkHealth.name());
      statisticsPayload.putString("broadcastQuality", statistics.broadcastQuality.name());

      WritableMap eventPayload = Arguments.createMap();
      eventPayload.putMap("statistics", statisticsPayload);

      broadcastEventHandler.run(Events.ON_TRANSMISSION_STATISTICS_CHANGED, eventPayload);
    }

    @Override
    public void onBroadcastQualityChanged(double quality) {
      WritableMap eventPayload = Arguments.createMap();
      eventPayload.putDouble("quality", quality);

      broadcastEventHandler.run(Events.ON_QUALITY_CHANGED, eventPayload);
    }

    @Override
    public void onNetworkHealthChanged(double health) {
      WritableMap eventPayload = Arguments.createMap();
      eventPayload.putDouble("networkHealth", health);

      broadcastEventHandler.run(Events.ON_NETWORK_HEALTH_CHANGED, eventPayload);
    }
  };

  private BroadcastConfiguration.LogLevel getLogLevel(String logLevelName) {
    switch (logLevelName) {
      case "debug": {
        return BroadcastConfiguration.LogLevel.DEBUG;
      }
      case "error": {
        return BroadcastConfiguration.LogLevel.ERROR;
      }
      case "info": {
        return BroadcastConfiguration.LogLevel.INFO;
      }
      case "warning": {
        return BroadcastConfiguration.LogLevel.WARNING;
      }
      default: {
        throw new RuntimeException("Does not support log level: " + logLevelName);
      }
    }
  }

  private BroadcastConfiguration.AspectMode getAspectMode(String aspectModeName) {
    switch (aspectModeName) {
      case "fit": {
        return BroadcastConfiguration.AspectMode.FIT;
      }
      case "fill": {
        return BroadcastConfiguration.AspectMode.FILL;
      }
      case "none": {
        return BroadcastConfiguration.AspectMode.NONE;
      }
      default: {
        throw new RuntimeException("Does not support aspect mode: " + aspectModeName);
      }
    }
  }

  private Device.Descriptor.Position getCameraPosition(String cameraPositionName) {
    switch (cameraPositionName) {
      case "front": {
        return Device.Descriptor.Position.FRONT;
      }
      case "back": {
        return Device.Descriptor.Position.BACK;
      }
      default: {
        throw new RuntimeException("Does not support camera position: " + cameraPositionName);
      }
    }
  }

  private BroadcastConfiguration getConfigurationPreset(String configurationPresetName) {
    switch (configurationPresetName) {
      case "standardPortrait": {
        return Presets.Configuration.STANDARD_PORTRAIT;
      }
      case "standardLandscape": {
        return Presets.Configuration.STANDARD_LANDSCAPE;
      }
      case "basicPortrait": {
        return Presets.Configuration.BASIC_PORTRAIT;
      }
      case "basicLandscape": {
        return Presets.Configuration.BASIC_LANDSCAPE;
      }
      default: {
        throw new RuntimeException("Does not support configuration preset: " + configurationPresetName);
      }
    }
  }

  private BroadcastConfiguration.AutomaticBitrateProfile getAutomaticBitrateProfile(String automaticBitrateProfileName) {
    switch (automaticBitrateProfileName) {
      case "conservative": {
        return BroadcastConfiguration.AutomaticBitrateProfile.CONSERVATIVE;
      }
      case "fastIncrease": {
        return BroadcastConfiguration.AutomaticBitrateProfile.FAST_INCREASE;
      }
      default: {
        throw new RuntimeException("Does not support automatic bitrate profile: " + automaticBitrateProfileName);
      }
    }
  }

  private ImagePreviewView getCameraPreview() {
    ImagePreviewView preview = broadcastSession.getPreviewView(cameraPreviewAspectMode);
    preview.setMirrored(isCameraPreviewMirrored);
    return preview;
  }

  private Device.Descriptor[] getInitialDeviceDescriptorList() {
    return initialCameraPosition == Device.Descriptor.Position.BACK
      ? Presets.Devices.BACK_CAMERA(mReactContext)
      : Presets.Devices.FRONT_CAMERA(mReactContext);
  }

  private void setCustomVideoConfig() {
    if (customVideoConfig != null) {
      config = config.changing($ -> {
        boolean isWidth = customVideoConfig.hasKey("width");
        boolean isHeight = customVideoConfig.hasKey("height");
        if (isWidth || isHeight) {
          if (isWidth && isHeight) {
            $.video.setSize(
              customVideoConfig.getInt("width"),
              customVideoConfig.getInt("height")
            );
          } else {
            throw new RuntimeException("The `width` and `height` are interrelated and thus can not be used separately.");
          }
        }

        if (customVideoConfig.hasKey("bitrate")) {
          $.video.setInitialBitrate(customVideoConfig.getInt("bitrate"));
        }
        if (customVideoConfig.hasKey("targetFrameRate")) {
          $.video.setTargetFramerate(customVideoConfig.getInt("targetFrameRate"));
        }
        if (customVideoConfig.hasKey("keyframeInterval")) {
          $.video.setKeyframeInterval(customVideoConfig.getInt("keyframeInterval"));
        }
        if (customVideoConfig.hasKey("isBFrames")) {
          $.video.setUseBFrames(customVideoConfig.getBoolean("isBFrames"));
        }
        if (customVideoConfig.hasKey("isAutoBitrate")) {
          $.video.setUseAutoBitrate(customVideoConfig.getBoolean("isAutoBitrate"));
        }
        if (customVideoConfig.hasKey("maxBitrate")) {
          $.video.setMaxBitrate(customVideoConfig.getInt("maxBitrate"));
        }
        if (customVideoConfig.hasKey("minBitrate")) {
          $.video.setMinBitrate(customVideoConfig.getInt("minBitrate"));
        }
        if (customVideoConfig.hasKey("autoBitrateProfile")) {
          String autoBitrateProfileName = customVideoConfig.getString("autoBitrateProfile");
          BroadcastConfiguration.AutomaticBitrateProfile autoBitrateProfile = getAutomaticBitrateProfile(autoBitrateProfileName);
          $.video.setAutoBitrateProfile(autoBitrateProfile);
        }

        return $;
      });
    }
  }

  private void setCustomAudioConfig() {
    if (customAudioConfig != null) {
      config = config.changing($ -> {
        if (customAudioConfig.hasKey("bitrate")) {
          $.audio.setBitrate(customAudioConfig.getInt("bitrate"));
        }
        if (customAudioConfig.hasKey("channels")) {
          $.audio.setChannels(customAudioConfig.getInt("channels"));
        }
        return $;
      });
    }
  }

  private void swapCameraAsync(CameraPreviewHandler callback) {
    broadcastSession.awaitDeviceChanges(() -> {
      for (Device.Descriptor deviceDescriptor : broadcastSession.listAvailableDevices(mReactContext)) {
        if (deviceDescriptor.type == Device.Descriptor.DeviceType.CAMERA && deviceDescriptor.position != attachedCameraDescriptor.position) {
          broadcastSession.exchangeDevices(attachedCameraDescriptor, deviceDescriptor, newCamera -> {
            attachedCameraDescriptor = newCamera.getDescriptor();
            callback.run(getCameraPreview());
          });
          break;
        }
      }
    });
  }

  private void muteAsync(boolean isMuted) {
    broadcastSession.awaitDeviceChanges(() -> {
      for (Device device : broadcastSession.listAttachedDevices()) {
        Device.Descriptor deviceDescriptor = device.getDescriptor();
        if (deviceDescriptor.type == Device.Descriptor.DeviceType.MICROPHONE && deviceDescriptor.urn.equals(attachedMicrophoneDescriptor.urn)) {
          Float gain = isMuted ? 0.0F : 1.0F;
          ((AudioDevice) device).setGain(gain);
          break;
        }
      }
    });
  }

  private void preInitialization() {
    setCustomVideoConfig();
    setCustomAudioConfig();
  }

  private void postInitialization() {
    if (broadcastSession != null) {
      broadcastSession.setLogLevel(initialSessionLogLevel);
    }
    if (isInitialMuted) {
      setIsMuted(true);
    }
  }

  private void saveInitialDevicesDescriptor(@NonNull Device.Descriptor[] deviceDescriptors) {
    for (Device.Descriptor deviceDescriptor : deviceDescriptors) {
      if (deviceDescriptor.type == Device.Descriptor.DeviceType.CAMERA) {
        attachedCameraDescriptor = deviceDescriptor;
      } else if (deviceDescriptor.type == Device.Descriptor.DeviceType.MICROPHONE) {
        attachedMicrophoneDescriptor = deviceDescriptor;
      }
    }
  }

  /**
   * Create the local recording service with callbacks wired to the
   * broadcast event handler.
   */
  private IVSLocalRecordingService createLocalRecordingService() {
    IVSLocalRecordingService service = new IVSLocalRecordingService();

    service.onRecordingSaved = (uri) -> {
      WritableMap payload = Arguments.createMap();
      payload.putString("uri", uri);
      broadcastEventHandler.run(Events.ON_LOCAL_RECORDING_SAVED, payload);
    };

    service.onError = (message) -> {
      WritableMap payload = Arguments.createMap();
      WritableMap exception = Arguments.createMap();
      exception.putString("detail", message);
      exception.putString("source", "LocalRecording");
      exception.putBoolean("isFatal", false);
      payload.putMap("exception", exception);
      broadcastEventHandler.run(Events.ON_ERROR, payload);
    };

    return service;
  }

  /**
   * Get the camera position string ("front" / "back") from the stored
   * {@link Device.Descriptor.Position}.
   */
  private String getCameraPositionString() {
    return initialCameraPosition == Device.Descriptor.Position.FRONT ? "front" : "back";
  }

  public enum Events {
    ON_ERROR("onError"),
    ON_STATE_CHANGED("onStateChanged"),
    ON_AUDIO_STATS("onAudioStats"),
    ON_TRANSMISSION_STATISTICS_CHANGED("onTransmissionStatisticsChanged"),
    ON_LOCAL_RECORDING_SAVED("onLocalRecordingSaved"),
    @Deprecated
    ON_QUALITY_CHANGED("onQualityChanged"),
    @Deprecated
    ON_NETWORK_HEALTH_CHANGED("onNetworkHealthChanged");

    private String title;

    Events(String title) {
      this.title = title;
    }

    @Override
    public String toString() {
      return title;
    }
  }

  public IVSBroadcastSessionService(ThemedReactContext reactContext) {
    mReactContext = reactContext;
  }

  // ════════════════════════════════════════════════════════════════════════
  //  Initialization — three modes
  // ════════════════════════════════════════════════════════════════════════

  public void init() {
    if (isInitialized()) {
      throw new RuntimeException("Broadcast session has been already initialized.");
    }

    preInitialization();

    BroadcastConfiguration.Vec2 videoSize = config.video.getSize();
    int videoWidth = (int) videoSize.x;
    int videoHeight = (int) videoSize.y;
    int videoBitrate = config.video.getInitialBitrate();

    if (isRecordOnlyMode) {
      // ── Record-only: no IVS session, just local camera + recording ──
      localRecordingService = createLocalRecordingService();
      localRecordingService.configure(
        mReactContext, getCameraPositionString(), videoWidth, videoHeight, videoBitrate
      );

    } else if (isLocalRecordingEnabled) {
      // ── Live + record: IVS session with custom sources + local recording ──

      // Configure a mixer slot for custom sources
      BroadcastConfiguration.Mixer.Slot slot = BroadcastConfiguration.Mixer.Slot.with($ -> {
        $.setSize(new BroadcastConfiguration.Vec2(videoWidth, videoHeight));
        $.setPosition(new BroadcastConfiguration.Vec2(0, 0));
        $.setName("custom-slot");
        return $;
      });

      config = config.changing($ -> {
        $.mixer.slots = new BroadcastConfiguration.Mixer.Slot[]{ slot };
        return $;
      });

      // Create session without built-in devices
      broadcastSession = new BroadcastSession(
        mReactContext,
        broadcastSessionListener,
        config,
        null // no built-in devices
      );

      // Create custom image source and bind to mixer
      customImageSource = broadcastSession.createImageInputSource(
        new BroadcastConfiguration.Vec2(videoWidth, videoHeight)
      );
      broadcastSession.getMixer().bind(customImageSource, "custom-slot");

      // Create custom audio source and bind to mixer
      customAudioSource = broadcastSession.createAudioInputSource(
        2, 48000, AudioDevice.Format.INT16
      );
      broadcastSession.getMixer().bind(customAudioSource, "custom-slot");

      // Create local recording service with IVS custom sources
      localRecordingService = createLocalRecordingService();
      localRecordingService.customImageSource = customImageSource;
      localRecordingService.customAudioSource = customAudioSource;
      localRecordingService.configure(
        mReactContext, getCameraPositionString(), videoWidth, videoHeight, videoBitrate
      );

    } else {
      // ── Standard: IVS-managed camera, no local recording ──
      broadcastSession = new BroadcastSession(
        mReactContext,
        broadcastSessionListener,
        config,
        getInitialDeviceDescriptorList()
      );

      saveInitialDevicesDescriptor(getInitialDeviceDescriptorList());
    }

    postInitialization();
  }

  public void deinit() {
    if (localRecordingService != null) {
      localRecordingService.tearDown();
      localRecordingService = null;
    }
    if (broadcastSession != null) {
      broadcastSession.release();
      broadcastSession = null;
    }
    customImageSource = null;
    customAudioSource = null;
  }

  public boolean isInitialized() {
    if (isRecordOnlyMode) {
      return localRecordingService != null;
    }
    return broadcastSession != null;
  }

  public boolean isReady() {
    if (isRecordOnlyMode) {
      return localRecordingService != null;
    }
    return broadcastSession != null && broadcastSession.isReady();
  }

  // ════════════════════════════════════════════════════════════════════════
  //  Broadcast + recording control
  // ════════════════════════════════════════════════════════════════════════

  public void start(@Nullable String ivsRTMPSUrl, @Nullable String ivsStreamKey) {
    broadcastSession.start(ivsRTMPSUrl, ivsStreamKey);
    sessionId = broadcastSession.getSessionId();

    // In live+record mode, start local recording alongside the broadcast
    if (isLocalRecordingEnabled && localRecordingService != null) {
      localRecordingService.startRecording();
    }
  }

  public void stop() {
    // Stop local recording first so the file is finalized before broadcast stops
    if (isLocalRecordingEnabled && localRecordingService != null) {
      localRecordingService.stopRecording();
    }
    broadcastSession.stop();
  }

  /** Start recording independently (record-only mode). */
  public void startRecording() {
    if (localRecordingService != null) {
      localRecordingService.startRecording();
    }
  }

  /** Stop recording independently (record-only mode). */
  public void stopRecording() {
    if (localRecordingService != null) {
      localRecordingService.stopRecording();
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  //  Camera preview
  // ════════════════════════════════════════════════════════════════════════

  @Deprecated
  public void swapCamera(CameraPreviewHandler callback) {
    swapCameraAsync(callback);
  }

  public void getCameraPreviewAsync(CameraPreviewHandler callback) {
    if (isRecordOnlyMode || isLocalRecordingEnabled) {
      if (localRecordingService != null) {
        View preview = localRecordingService.getPreviewView();
        if (preview != null) {
          callback.run(preview);
        }
      }
    } else {
      broadcastSession.awaitDeviceChanges(() -> {
        callback.run(getCameraPreview());
      });
    }
  }

  public void setCameraPosition(String cameraPositionName, CameraPreviewHandler callback) {
    if (isInitialized()) {
      if (isLocalRecordingEnabled || isRecordOnlyMode) {
        if (localRecordingService != null) {
          localRecordingService.swapCamera();
          View preview = localRecordingService.getPreviewView();
          if (preview != null) {
            callback.run(preview);
          }
        }
      } else {
        swapCameraAsync(callback);
      }
    } else {
      initialCameraPosition = getCameraPosition(cameraPositionName);
    }
  }

  public void setCameraPreviewAspectMode(String cameraPreviewAspectModeName, CameraPreviewHandler callback) {
    cameraPreviewAspectMode = getAspectMode(cameraPreviewAspectModeName);
    if (isInitialized()) {
      getCameraPreviewAsync(callback);
    }
  }

  public void setIsCameraPreviewMirrored(boolean isPreviewMirrored, CameraPreviewHandler callback) {
    isCameraPreviewMirrored = isPreviewMirrored;
    if (isInitialized()) {
      if (isLocalRecordingEnabled || isRecordOnlyMode) {
        if (localRecordingService != null) {
          View preview = localRecordingService.getPreviewView();
          if (preview != null) {
            preview.setScaleX(isPreviewMirrored ? -1.0f : 1.0f);
            callback.run(preview);
          }
        }
      } else {
        getCameraPreviewAsync(callback);
      }
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  //  Mute
  // ════════════════════════════════════════════════════════════════════════

  public void setIsMuted(boolean isMuted) {
    if (isInitialized()) {
      if (isLocalRecordingEnabled || isRecordOnlyMode) {
        if (localRecordingService != null) {
          localRecordingService.setMuted(isMuted);
        }
      } else {
        muteAsync(isMuted);
      }
    } else {
      isInitialMuted = isMuted;
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  //  Configuration setters
  // ════════════════════════════════════════════════════════════════════════

  public void setIsLocalRecordingEnabled(boolean enabled) {
    isLocalRecordingEnabled = enabled;
  }

  public void setIsRecordOnlyMode(boolean enabled) {
    isRecordOnlyMode = enabled;
  }

  public void setSessionLogLevel(String sessionLogLevelName) {
    BroadcastConfiguration.LogLevel sessionLogLevel = getLogLevel(sessionLogLevelName);
    if (isInitialized() && broadcastSession != null) {
      broadcastSession.setLogLevel(sessionLogLevel);
    } else {
      initialSessionLogLevel = sessionLogLevel;
    }
  }

  public void setLogLevel(String logLevel) {
    config = config.changing($ -> {
      $.logLevel = getLogLevel(logLevel);
      return $;
    });
  }

  public void setConfigurationPreset(String configurationPreset) {
    config = getConfigurationPreset(configurationPreset);
  }

  public void setVideoConfig(ReadableMap videoConfig) {
    customVideoConfig = videoConfig;
  }

  public void setAudioConfig(ReadableMap audioConfig) {
    customAudioConfig = audioConfig;
  }

  public void setEventHandler(RunnableCallback handler) {
    broadcastEventHandler = handler;
  }
}

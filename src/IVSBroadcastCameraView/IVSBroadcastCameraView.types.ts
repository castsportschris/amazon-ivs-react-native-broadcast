import type { Component, ComponentType } from 'react';
import type { NativeSyntheticEvent, ViewStyle, StyleProp } from 'react-native';

export type ExtractComponentProps<T> = T extends
  | ComponentType<infer P>
  | Component<infer P>
  ? P
  : never;

export enum Command {
  Start = 'START',
  Stop = 'STOP',
  StartRecording = 'START_RECORDING',
  StopRecording = 'STOP_RECORDING',
  /**
   * @deprecated in favor of {@link CameraPosition}
   */
  SwapCamera = 'SWAP_CAMERA',
}

export enum StateStatusEnum {
  INVALID = 0,
  DISCONNECTED = 1,
  CONNECTING = 2,
  CONNECTED = 3,
  ERROR = 4,
}

export enum NetworkHealthEnum {
  EXCELLENT = 0,
  HIGH = 1,
  MEDIUM = 2,
  LOW = 3,
  BAD = 4,
}

export enum BroadcastQualityEnum {
  NEAR_MAXIMUM = 0,
  HIGH = 1,
  MEDIUM = 2,
  LOW = 3,
  NEAR_MINIMUM = 4,
}

export type StateStatusUnion = keyof typeof StateStatusEnum;

export type BroadcastQuality = keyof typeof BroadcastQualityEnum;

export type NetworkHealth = keyof typeof NetworkHealthEnum;

export type LogLevel = 'debug' | 'error' | 'info' | 'warning';

export type CameraPosition = 'front' | 'back';

export type CameraPreviewAspectMode = 'fit' | 'fill' | 'none';

type AudioChannel = 1 | 2;

type AudioQuality = 'minimum' | 'low' | 'medium' | 'high' | 'maximum';

type KeyframeInterval = 1 | 2 | 3 | 4 | 5;

type AudioSessionStrategy =
  | 'recordOnly'
  | 'playAndRecord'
  | 'playAndRecordDefaultToSpeaker'
  | 'noAction';

type ConfigurationPreset =
  | 'standardPortrait'
  | 'standardLandscape'
  | 'basicPortrait'
  | 'basicLandscape';

type AutomaticBitrateProfile = 'conservative' | 'fastIncrease';

interface IEventHandler<T extends Record<string, unknown>> {
  (event: NativeSyntheticEvent<T>): void;
}

interface IBaseTransmissionStatistics {
  readonly rtt: number;
  readonly recommendedBitrate: number;
  readonly measuredBitrate: number;
}

interface INativeTransmissionStatistics extends IBaseTransmissionStatistics {
  readonly networkHealth: number | NetworkHealth;
  readonly broadcastQuality: number | BroadcastQuality;
}

export interface ITransmissionStatistics extends IBaseTransmissionStatistics {
  readonly networkHealth: NetworkHealth;
  readonly broadcastQuality: BroadcastQuality;
}

export interface IBroadcastSessionError {
  readonly code: string;
  readonly type: string;
  readonly source: string;
  readonly detail: string;
  readonly isFatal: boolean;
  readonly sessionId: string;
}

export interface IAudioStats {
  readonly peak: number;
  readonly rms: number;
}

interface IVideoConfig {
  readonly width?: number;
  readonly height?: number;
  readonly bitrate?: number;
  readonly targetFrameRate?: number;
  readonly keyframeInterval?: KeyframeInterval;
  readonly isBFrames?: boolean;
  readonly isAutoBitrate?: boolean;
  readonly autoBitrateProfile?: AutomaticBitrateProfile;
  readonly maxBitrate?: number;
  readonly minBitrate?: number;
}

interface IAudioConfig {
  readonly bitrate?: number;
  readonly channels?: AudioChannel;
  readonly audioSessionStrategy?: AudioSessionStrategy;
  readonly quality?: AudioQuality;
}

interface IConnectedStateMetadata {
  readonly sessionId: string;
}

export type StateChangedMetadata = IConnectedStateMetadata;

export interface INativeEventHandlers {
  onError: IEventHandler<Readonly<{ message: string }>>;
  onBroadcastError: IEventHandler<
    Readonly<{
      exception: {
        readonly code?: number;
        readonly type?: string;
        readonly source?: string;
        readonly detail?: string;
        readonly isFatal?: boolean;
        readonly sessionId?: string;
      };
    }>
  >;
  onIsBroadcastReady: IEventHandler<Readonly<{ isReady: boolean }>>;
  onBroadcastAudioStats: IEventHandler<Readonly<{ audioStats: IAudioStats }>>;
  onBroadcastStateChanged: IEventHandler<
    Readonly<{
      stateStatus: StateStatusUnion | number;
      metadata?: StateChangedMetadata;
    }>
  >;
  /**
   * @deprecated in favor of onTransmissionStatisticsChanged
   */
  onBroadcastQualityChanged: IEventHandler<Readonly<{ quality: number }>>;
  /**
   * @deprecated in favor of onTransmissionStatisticsChanged
   */
  onNetworkHealthChanged: IEventHandler<Readonly<{ networkHealth: number }>>;
  onTransmissionStatisticsChanged: IEventHandler<
    Readonly<{ statistics: INativeTransmissionStatistics }>
  >;
  onLocalRecordingSaved: IEventHandler<Readonly<{ uri: string }>>;
  onAudioSessionInterrupted(): void;
  onAudioSessionResumed(): void;
  onMediaServicesWereLost(): void;
  onMediaServicesWereReset(): void;
}

export interface IIVSBroadcastCameraNativeViewProps
  extends IBaseProps,
    INativeEventHandlers {
  readonly style?: StyleProp<ViewStyle>;
  readonly testID?: string;
}

interface IBaseProps {
  readonly rtmpsUrl?: string;
  readonly streamKey?: string;
  readonly configurationPreset?: ConfigurationPreset;
  readonly videoConfig?: IVideoConfig;
  readonly audioConfig?: IAudioConfig;
  readonly logLevel?: LogLevel;
  readonly sessionLogLevel?: LogLevel;
  readonly cameraPreviewAspectMode?: CameraPreviewAspectMode;
  readonly isCameraPreviewMirrored?: boolean;
  readonly cameraPosition?: CameraPosition;
  readonly isMuted?: boolean;
  /**
   * When true, the SDK uses a custom camera pipeline instead of the IVS
   * SDK's built-in camera management, enabling simultaneous local recording
   * to the device alongside live streaming. The local MP4 is saved to the
   * device's gallery when the broadcast stops.
   *
   * On iOS: AVCaptureSession + AVAssetWriter.
   * On Android: Camera2 + MediaCodec/MediaMuxer.
   *
   * Default: false (standard IVS-managed camera, no local recording).
   */
  readonly isLocalRecordingEnabled?: boolean;
  /**
   * When true, the component sets up only the local recording service for
   * camera preview and on-device recording — no IVS broadcast session is
   * created. Use the `startRecording()` / `stopRecording()` imperative
   * methods to control recording. The saved MP4 triggers `onLocalRecordingSaved`.
   *
   * Default: false.
   */
  readonly isRecordOnlyMode?: boolean;
  /**
   * When true, the camera captures at 4K and an on-device CoreML model
   * detects the ball each frame. A 1920x1080 crop window slides smoothly
   * across the 4K source frame to follow the ball, and that cropped frame
   * is what gets streamed to IVS and/or written to the local file.
   *
   * Works in all three modes (live, record-only, live+record). Combine with
   * `autoTrackingModelName` to specify which CoreML model to use.
   *
   * Forces the custom-capture path even in live-only mode (the IVS SDK's
   * built-in camera doesn't expose CMSampleBuffers we can run ML on).
   *
   * Requires a device that supports 4K capture. Callers should disable this
   * gracefully on unsupported devices.
   *
   * Default: false.
   */
  readonly autoTrackingEnabled?: boolean;
  /**
   * Filename (without extension) of the CoreML object-detection model bundled
   * with the app. Required when `autoTrackingEnabled` is true. The model must
   * detect the COCO "sports ball" class (label ID 37 in 91-class COCO).
   *
   * Cast Sports ships with `"BallDetector"` — a CoreML conversion of
   * TorchVision's SSDLite320-MobileNetV3-Large (BSD license). Built from
   * `packages/amazon-ivs-react-native-broadcast/scripts/convert_ball_detector.py`.
   */
  readonly autoTrackingModelName?: string;
}

export interface IEventHandlers {
  onError?(errorMessage: string): void;
  onBroadcastError?(error: IBroadcastSessionError): void;
  onIsBroadcastReady?(isReady: boolean): void;
  onBroadcastAudioStats?(audioStats: IAudioStats): void;
  onBroadcastStateChanged?(
    stateStatus: StateStatusUnion,
    metadata?: StateChangedMetadata
  ): void;
  /**
   * @deprecated in favor of onTransmissionStatisticsChanged
   */
  onBroadcastQualityChanged?(quality: number): void;
  /**
   * @deprecated in favor of onTransmissionStatisticsChanged
   */
  onNetworkHealthChanged?(networkHealth: number): void;
  onTransmissionStatisticsChanged?(
    transmissionStatistics: ITransmissionStatistics
  ): void;
  onAudioSessionInterrupted?(): void;
  onAudioSessionResumed?(): void;
  onMediaServicesWereLost?(): void;
  onMediaServicesWereReset?(): void;
  /**
   * Called when the local recording has been saved after a broadcast ends
   * (or after `stopRecording()` in record-only mode). On both platforms a
   * copy is also placed in the device gallery as a side effect.
   *
   * Only fires when `isLocalRecordingEnabled` or `isRecordOnlyMode` is true.
   *
   * @param uri - A `file://` URI pointing to the recorded MP4 in the app's
   *   cache. Suitable for upload via Expo `FileSystem.uploadTaskStartAsync`.
   *   The native side does NOT auto-clean this file — call
   *   `FileSystem.deleteAsync(uri)` once you're done with it (e.g. after the
   *   upload finishes) to avoid leaking storage over time.
   */
  onLocalRecordingSaved?(event: { uri: string }): void;
}

export interface IIVSBroadcastCameraViewProps
  extends IBaseProps,
    IEventHandlers {
  readonly style?: StyleProp<ViewStyle>;
  readonly testID?: string;
}

type StartMethodOptions = Pick<IBaseProps, 'rtmpsUrl' | 'streamKey'>;

export interface IIVSBroadcastCameraView {
  start(options?: StartMethodOptions): void;
  stop(): void;
  /**
   * Start local-only recording (record-only mode or manual recording control).
   * Begins writing an MP4 to the device; triggers `onLocalRecordingSaved` when
   * `stopRecording()` is called.
   */
  startRecording(): void;
  /**
   * Stop local-only recording. Finalizes the MP4, saves to the device gallery,
   * and fires `onLocalRecordingSaved` with the file URI.
   */
  stopRecording(): void;
  /**
   * @deprecated in favor of {@link CameraPosition}
   */
  swapCamera(): void;
}

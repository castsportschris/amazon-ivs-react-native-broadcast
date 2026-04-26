import AVFoundation

/// Auto-tracking variant of IVSLocalRecordingService.
///
/// Captures at 4K, runs ball detection on each frame in a background queue,
/// and crops a 1920x1080 window from the 4K source based on the smoothed pan
/// position. The cropped frame replaces the raw frame in the existing fan-out
/// pipeline (IVS custom source for RTMP, AVAssetWriter for local file).
///
/// All three broadcast modes from the parent class are supported:
///   - Live only:        cropped frame → IVS custom source only
///   - Record only:      cropped frame → AVAssetWriter only
///   - Live + record:    cropped frame → both, in parallel
///
/// The base class's existing serial processingQueue handles fan-out unchanged;
/// this subclass only inserts a transform step at the top of captureOutput.
final class IVSAutoTrackingRecordingService: IVSLocalRecordingService {

    private let tracker: BallZoneTracker
    private let cropper: FrameCropper
    private var debugFrameCount = 0

    /// Initialize with the CoreML model name (without extension).
    /// The model file must be in the app bundle as `<name>.mlpackage`,
    /// `<name>.mlmodelc`, or `<name>.mlmodel`.
    init(modelName: String) {
        self.tracker = BallZoneTracker(modelName: modelName)
        self.cropper = FrameCropper(outputWidth: 1920, outputHeight: 1080)
        super.init()

        // Drive the base class to capture at 4K. The output (writer + IVS
        // custom source) stays at 1920x1080 — only the source frame is wider.
        self.preferredSessionPreset = .hd4K3840x2160
    }

    // MARK: - Frame processing hook

    override func processVideoFrame(_ sampleBuffer: CMSampleBuffer) -> CMSampleBuffer? {
        // Submit the source frame to the tracker (non-blocking; runs on its
        // own queue with backpressure).
        tracker.ingest(sampleBuffer)

        // Crop the source frame to a 1080p window at the current pan position.
        // The tracker keeps panPosition smoothed; capture queue never blocks.
        let pan = tracker.currentPanPosition
        let cropped = cropper.crop(sampleBuffer, panPosition: pan)

        if debugFrameCount % 300 == 0 {
            print("[AutoTracking] frame=\(debugFrameCount) pan=\(String(format: "%.2f", pan)) modelReady=\(tracker.isReady)")
        }
        debugFrameCount += 1

        return cropped
    }
}

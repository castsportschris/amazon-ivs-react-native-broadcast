import AVFoundation
import AmazonIVSBroadcast
import Photos

/// Manages a custom AVCaptureSession that feeds frames to both the IVS Broadcast
/// SDK (for live streaming) and a local AVAssetWriter (for on-device recording).
///
/// When enabled, this replaces the IVS SDK's built-in camera management. The same
/// CMSampleBuffers flow to two destinations in parallel:
///   1. IVSCustomImageSource / IVSCustomAudioSource → RTMP stream
///   2. AVAssetWriter → local MP4 file on device
///
/// After recording stops, the MP4 is saved to the user's Photos library.
class IVSLocalRecordingService: NSObject {

    // MARK: - Public callbacks

    /// Called after the recording is finalized and saved to Photos.
    /// The URL points to the temp file (caller can use it for upload before it's cleaned up).
    var onRecordingSaved: ((_ fileURL: URL) -> Void)?

    /// Called if any error occurs during capture or recording.
    var onError: ((_ error: Error) -> Void)?

    // MARK: - Public read-only

    /// The preview layer bound to our AVCaptureSession. Add this to your view
    /// hierarchy to show the camera feed. Replaces IVSImagePreviewView when
    /// local recording is enabled.
    private(set) var previewLayer: AVCaptureVideoPreviewLayer?

    // MARK: - IVS custom sources (set by the session service after creating them)

    var customImageSource: (any IVSCustomImageSource)?
    var customAudioSource: (any IVSCustomAudioSource)?

    // MARK: - Private state

    private var captureSession: AVCaptureSession?
    private var currentCameraInput: AVCaptureDeviceInput?
    private var audioInput: AVCaptureDeviceInput?
    private var videoDataOutput: AVCaptureVideoDataOutput?
    private var audioDataOutput: AVCaptureAudioDataOutput?

    private var assetWriter: AVAssetWriter?
    private var videoWriterInput: AVAssetWriterInput?
    private var audioWriterInput: AVAssetWriterInput?
    private var isRecording = false
    private var sessionAtSourceTime = false
    private var outputFileURL: URL?

    private var isMuted = false
    private var frameCount = 0

    /// Serial queue for all sample buffer processing. Both AVCaptureOutput
    /// delegates and AVAssetWriter appends run on this queue to avoid races.
    private let processingQueue = DispatchQueue(label: "com.castsports.localrecording.processing")

    /// Capture-session preset used by configure(). Subclasses may override
    /// this in init to drive a different source resolution (e.g., 4K for
    /// auto-tracking). Falls back to .high if the requested preset isn't
    /// supported on the current device.
    var preferredSessionPreset: AVCaptureSession.Preset = .high

    // MARK: - Frame processing hook

    /// Override point for subclasses to transform each video frame before it
    /// fans out to the IVS custom source and AVAssetWriter. Audio frames are
    /// not routed through this hook.
    ///
    /// Default implementation passes the buffer through unchanged.
    /// Returning nil drops the frame entirely (no fan-out).
    func processVideoFrame(_ sampleBuffer: CMSampleBuffer) -> CMSampleBuffer? {
        return sampleBuffer
    }

    // MARK: - Configuration

    /// Set up the AVCaptureSession with the specified camera and resolution.
    /// Call this before startRecording().
    func configure(cameraPosition: AVCaptureDevice.Position, videoWidth: Int, videoHeight: Int, videoBitrate: Int) {
        let session = AVCaptureSession()
        session.beginConfiguration()

        // Use the preferred preset (subclasses may request 4K). Fall back to
        // .high if the device doesn't support the requested preset.
        if session.canSetSessionPreset(preferredSessionPreset) {
            session.sessionPreset = preferredSessionPreset
        } else if session.canSetSessionPreset(.high) {
            session.sessionPreset = .high
        }

        // Camera input
        if let camera = bestCamera(for: cameraPosition) {
            do {
                let input = try AVCaptureDeviceInput(device: camera)
                if session.canAddInput(input) {
                    session.addInput(input)
                    currentCameraInput = input
                }
            } catch {
                onError?(error)
            }
        }

        // Microphone input
        if let mic = AVCaptureDevice.default(for: .audio) {
            do {
                let input = try AVCaptureDeviceInput(device: mic)
                if session.canAddInput(input) {
                    session.addInput(input)
                    audioInput = input
                }
            } catch {
                onError?(error)
            }
        }

        // Video data output — delivers CMSampleBuffers on our processing queue.
        // BGRA is the standard pixel format for IVS custom image sources per
        // Amazon's documentation and sample code.
        let videoOutput = AVCaptureVideoDataOutput()
        videoOutput.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]
        videoOutput.setSampleBufferDelegate(self, queue: processingQueue)
        videoOutput.alwaysDiscardsLateVideoFrames = true
        if session.canAddOutput(videoOutput) {
            session.addOutput(videoOutput)
            videoDataOutput = videoOutput
        }

        // Audio data output
        let audioOutput = AVCaptureAudioDataOutput()
        audioOutput.setSampleBufferDelegate(self, queue: processingQueue)
        if session.canAddOutput(audioOutput) {
            session.addOutput(audioOutput)
            audioDataOutput = audioOutput
        }

        session.commitConfiguration()

        captureSession = session

        // Create preview layer so callers can display the camera feed
        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        if let connection = preview.connection, connection.isVideoOrientationSupported {
            connection.videoOrientation = .landscapeRight
        }
        self.previewLayer = preview

        // Start the capture session (camera starts producing frames)
        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
        }

        // Store config for AVAssetWriter setup
        self.videoWidth = videoWidth
        self.videoHeight = videoHeight
        self.videoBitrate = videoBitrate
    }

    private var videoWidth: Int = 1920
    private var videoHeight: Int = 1080
    private var videoBitrate: Int = 5_000_000

    // MARK: - Recording control

    func startRecording() {
        processingQueue.async { [weak self] in
            guard let self = self else { return }

            let fileName = "broadcast-\(UUID().uuidString).mp4"
            let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)

            // Clean up any leftover file at this path
            try? FileManager.default.removeItem(at: fileURL)

            do {
                let writer = try AVAssetWriter(outputURL: fileURL, fileType: .mp4)

                // Video input — H.264 matching the broadcast resolution/bitrate.
                // Width/height are in landscape orientation since the video
                // connection is set to .landscapeRight.
                let videoSettings: [String: Any] = [
                    AVVideoCodecKey: AVVideoCodecType.h264,
                    AVVideoWidthKey: self.videoWidth,
                    AVVideoHeightKey: self.videoHeight,
                    AVVideoCompressionPropertiesKey: [
                        AVVideoAverageBitRateKey: self.videoBitrate,
                        AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
                    ],
                ]
                let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
                videoInput.expectsMediaDataInRealTime = true

                if writer.canAdd(videoInput) {
                    writer.add(videoInput)
                }

                // Audio input — AAC stereo
                let audioSettings: [String: Any] = [
                    AVFormatIDKey: kAudioFormatMPEG4AAC,
                    AVNumberOfChannelsKey: 2,
                    AVSampleRateKey: 48000.0,
                    AVEncoderBitRateKey: 96000,
                ]
                let audioInput = AVAssetWriterInput(mediaType: .audio, outputSettings: audioSettings)
                audioInput.expectsMediaDataInRealTime = true

                if writer.canAdd(audioInput) {
                    writer.add(audioInput)
                }

                writer.startWriting()

                self.assetWriter = writer
                self.videoWriterInput = videoInput
                self.audioWriterInput = audioInput
                self.outputFileURL = fileURL
                self.sessionAtSourceTime = false
                self.isRecording = true

                print("[IVSLocalRecording] Started recording to \(fileURL.lastPathComponent)")
            } catch {
                print("[IVSLocalRecording] Failed to create AVAssetWriter: \(error)")
                self.onError?(error)
            }
        }
    }

    func stopRecording() {
        processingQueue.async { [weak self] in
            guard let self = self, self.isRecording else { return }

            self.isRecording = false

            self.videoWriterInput?.markAsFinished()
            self.audioWriterInput?.markAsFinished()

            self.assetWriter?.finishWriting { [weak self] in
                guard let self = self else { return }

                if let error = self.assetWriter?.error {
                    print("[IVSLocalRecording] AVAssetWriter error: \(error)")
                    self.onError?(error)
                    return
                }

                guard let fileURL = self.outputFileURL else { return }
                print("[IVSLocalRecording] Finished writing \(fileURL.lastPathComponent)")

                // Save to Photos library
                self.saveToPhotos(fileURL: fileURL)
            }
        }
    }

    /// Stop the capture session entirely. Call when the view is being torn down.
    func tearDown() {
        captureSession?.stopRunning()
        captureSession = nil
        previewLayer = nil
    }

    // MARK: - Camera swap

    func swapCamera() {
        guard let session = captureSession, let currentInput = currentCameraInput else { return }

        let currentPosition = currentInput.device.position
        let newPosition: AVCaptureDevice.Position = currentPosition == .back ? .front : .back

        guard let newCamera = bestCamera(for: newPosition) else { return }

        session.beginConfiguration()

        session.removeInput(currentInput)

        do {
            let newInput = try AVCaptureDeviceInput(device: newCamera)
            if session.canAddInput(newInput) {
                session.addInput(newInput)
                currentCameraInput = newInput
            }
        } catch {
            // Re-add the old input if the new one fails
            if session.canAddInput(currentInput) {
                session.addInput(currentInput)
            }
            onError?(error)
        }

        session.commitConfiguration()
    }

    // MARK: - Mute

    func setMuted(_ muted: Bool) {
        self.isMuted = muted
    }

    // MARK: - Private helpers

    private func bestCamera(for position: AVCaptureDevice.Position) -> AVCaptureDevice? {
        // Prefer wide-angle camera (the standard one)
        if let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position) {
            return device
        }
        return AVCaptureDevice.default(for: .video)
    }

    private func saveToPhotos(fileURL: URL) {
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                print("[IVSLocalRecording] Photo library access denied")
                return
            }

            PHPhotoLibrary.shared().performChanges {
                PHAssetCreationRequest.forAsset().addResource(with: .video, fileURL: fileURL, options: nil)
            } completionHandler: { [weak self] success, error in
                if success {
                    print("[IVSLocalRecording] Saved to Photos library")
                    DispatchQueue.main.async {
                        self?.onRecordingSaved?(fileURL)
                    }
                } else if let error = error {
                    print("[IVSLocalRecording] Failed to save to Photos: \(error)")
                    DispatchQueue.main.async {
                        self?.onError?(error)
                    }
                }

                // Clean up temp file
                try? FileManager.default.removeItem(at: fileURL)
            }
        }
    }
}

// MARK: - AVCaptureVideoDataOutputSampleBufferDelegate + AVCaptureAudioDataOutputSampleBufferDelegate

extension IVSLocalRecordingService: AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate {

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        // For video frames, run the transform hook once and use the result for
        // both fan-out destinations. The default hook is pass-through, so this
        // is byte-identical to the prior behavior unless a subclass overrides.
        // Audio frames are not routed through the hook.
        let videoBuffer: CMSampleBuffer?
        if output is AVCaptureVideoDataOutput {
            if connection.isVideoOrientationSupported {
                connection.videoOrientation = .landscapeRight
            }
            guard let processed = processVideoFrame(sampleBuffer) else { return }
            videoBuffer = processed
        } else {
            videoBuffer = nil
        }

        // Route to IVS custom source (for live streaming)
        if let video = videoBuffer {
            if frameCount % 300 == 0 {
                let fmt = CMSampleBufferGetFormatDescription(video)
                let dims = fmt.map { CMVideoFormatDescriptionGetDimensions($0) }
                print("[IVSLocalRecording] Video frame #\(frameCount), dims=\(String(describing: dims)), imgSource=\(customImageSource != nil ? "set" : "nil")")
            }
            frameCount += 1
            customImageSource?.onSampleBuffer(video)
        } else if output is AVCaptureAudioDataOutput {
            if !isMuted {
                customAudioSource?.onSampleBuffer(sampleBuffer)
            }
        }

        // Route to AVAssetWriter (for local recording)
        guard isRecording, let writer = assetWriter, writer.status == .writing else { return }

        let bufferForWriterTiming = videoBuffer ?? sampleBuffer

        // Start the writer session on the first sample buffer's timestamp
        if !sessionAtSourceTime {
            let startTime = CMSampleBufferGetPresentationTimeStamp(bufferForWriterTiming)
            writer.startSession(atSourceTime: startTime)
            sessionAtSourceTime = true
        }

        if output is AVCaptureVideoDataOutput, let video = videoBuffer {
            if let input = videoWriterInput, input.isReadyForMoreMediaData {
                input.append(video)
            }
        } else if output is AVCaptureAudioDataOutput {
            if !isMuted, let input = audioWriterInput, input.isReadyForMoreMediaData {
                input.append(sampleBuffer)
            }
        }
    }
}

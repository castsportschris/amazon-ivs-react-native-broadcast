import AVFoundation
import CoreML
import Vision

/// Detects a sports ball in incoming camera frames using a CoreML object
/// detection model wrapped in Vision, then maps its horizontal position to a
/// smoothed pan position (0.0 - 1.0) suitable for driving FrameCropper.
///
/// Inference runs on a dedicated background queue with single-frame
/// backpressure (drops frames if inference can't keep up). The capture queue
/// reads `currentPanPosition` atomically each frame — never blocked on ML.
///
/// When no ball is detected, the tracker holds the current pan position for
/// `occlusionHoldFrames` (~1 second at 30fps) before slowly drifting back
/// toward center. This handles brief occlusion (a player blocking the ball)
/// without jarring jumps.
final class BallZoneTracker {

    enum Zone {
        case left, middle, right
    }

    // MARK: - Public state

    /// Current smoothed pan position. Safe to read from any thread.
    /// 0.0 = full left, 0.5 = center, 1.0 = full right.
    var currentPanPosition: CGFloat {
        panLock.lock()
        defer { panLock.unlock() }
        return _currentPanPosition
    }

    /// Whether a model was loaded successfully. If false, currentPanPosition
    /// stays at 0.5 (center) and ingest() is a no-op.
    var isReady: Bool { coreMLModel != nil }

    // MARK: - Tunables (Phase 3 will replace these with a Kalman filter)

    /// Minimum confidence (0–1) required to accept a ball detection.
    var minConfidence: Float = 0.35

    /// How aggressively the pan position eases toward the new target each
    /// detection. Higher = snappier, lower = smoother. 0.0–1.0.
    var smoothingFactor: CGFloat = 0.15

    /// Frames of "no detection" before we let the pan drift back toward center.
    var occlusionHoldFrames: Int = 30

    // MARK: - Private state

    private let coreMLModel: VNCoreMLModel?
    private let inferenceQueue = DispatchQueue(
        label: "com.castsports.balltracker.inference",
        qos: .userInitiated
    )

    private let stateLock = NSLock()
    private var inflightCount = 0
    private let maxInflight = 1

    private let panLock = NSLock()
    private var _currentPanPosition: CGFloat = 0.5

    private var recentZones: [Zone] = []
    private let smoothingWindow = 5

    private var framesSinceLastDetection = 0

    // MARK: - Init

    /// Loads the CoreML model from the bundle. Pass the model's filename
    /// without extension (e.g. "EfficientDetLite0").
    init(modelName: String) {
        self.coreMLModel = Self.loadModel(named: modelName)
        if coreMLModel == nil {
            print("[BallZoneTracker] Could not load model '\(modelName)' — tracker disabled, pan stays centered.")
        }
    }

    // MARK: - Public API

    /// Submit a frame for inference. Returns immediately. If inference is
    /// already running for a previous frame, this frame is dropped.
    func ingest(_ sampleBuffer: CMSampleBuffer) {
        guard let model = coreMLModel else { return }

        let canEnqueue: Bool = {
            stateLock.lock()
            defer { stateLock.unlock() }
            if inflightCount < maxInflight {
                inflightCount += 1
                return true
            }
            return false
        }()
        guard canEnqueue else { return }

        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            decrementInflight()
            return
        }

        inferenceQueue.async { [weak self] in
            self?.runInference(pixelBuffer: pixelBuffer, model: model)
        }
    }

    // MARK: - Inference

    /// COCO label ID for "sports ball" in the 91-class TorchVision/COCO label
    /// map (1-indexed, with non-contiguous IDs). Used to filter detections
    /// from BallDetector.mlpackage (SSDLite320-MobileNetV3, BSD license).
    /// One model handles all sports — soccer ball, basketball, football, etc.
    /// all map to this single class.
    private let sportsBallLabelId = 37

    /// Native input size of the detection model. Used to normalize the model's
    /// pixel-coordinate box outputs into the [0, 1] range FrameCropper expects.
    private let modelInputSize: CGFloat = 320

    private func runInference(pixelBuffer: CVPixelBuffer, model: VNCoreMLModel) {
        defer { decrementInflight() }

        let request = VNCoreMLRequest(model: model)
        request.imageCropAndScaleOption = .scaleFill

        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, options: [:])
        do {
            try handler.perform([request])
        } catch {
            handleNoDetection()
            return
        }

        // BallDetector.mlpackage returns raw post-NMS arrays. We also handle
        // VNRecognizedObjectObservation as a fallback in case a future model
        // ships with Vision-compatible metadata.
        if let observations = request.results as? [VNRecognizedObjectObservation],
           !observations.isEmpty {
            handleVisionObservations(observations)
            return
        }

        if let observations = request.results as? [VNCoreMLFeatureValueObservation] {
            handleRawMultiArrays(observations)
            return
        }

        handleNoDetection()
    }

    private func handleVisionObservations(_ observations: [VNRecognizedObjectObservation]) {
        let ball = observations
            .filter { obs in
                obs.confidence >= self.minConfidence &&
                    obs.labels.contains { label in
                        let id = label.identifier.lowercased()
                        return id.contains("ball") || id.contains("sports ball")
                    }
            }
            .max(by: { $0.confidence < $1.confidence })

        if let ball = ball {
            handleDetection(normalizedX: ball.boundingBox.midX)
        } else {
            handleNoDetection()
        }
    }

    /// Parse post-NMS detection arrays from BallDetector.mlpackage
    /// (TorchVision SSDLite320-MobileNetV3, BSD license).
    /// Expected outputs:
    ///   - boxes:  [N, 4] in pixel coords [xmin, ymin, xmax, ymax] over 0–320
    ///   - scores: [N]    confidence in 0–1, sorted descending
    ///   - labels: [N]    COCO label IDs (1-indexed, sports ball = 37)
    private func handleRawMultiArrays(_ observations: [VNCoreMLFeatureValueObservation]) {
        var outputsByName: [String: MLMultiArray] = [:]
        for obs in observations {
            if let array = obs.featureValue.multiArrayValue {
                outputsByName[obs.featureName] = array
            }
        }

        guard
            let boxes = outputsByName["boxes"],
            let scores = outputsByName["scores"],
            let labels = outputsByName["labels"]
        else {
            handleNoDetection()
            return
        }

        // Runtime shape reflects actual detection count after NMS.
        let detectionCount = boxes.shape.first?.intValue ?? 0
        guard detectionCount > 0 else {
            handleNoDetection()
            return
        }

        var bestX: CGFloat?
        var bestScore: Float = minConfidence

        for i in 0..<detectionCount {
            let labelId = Int(truncating: labels[[NSNumber(value: i)] as [NSNumber]])
            guard labelId == sportsBallLabelId else { continue }

            let score = Float(truncating: scores[[NSNumber(value: i)] as [NSNumber]])
            guard score > bestScore else { continue }

            // boxes layout: [xmin, ymin, xmax, ymax] in pixel coords over modelInputSize.
            let xmin = CGFloat(truncating: boxes[[NSNumber(value: i), 0] as [NSNumber]])
            let xmax = CGFloat(truncating: boxes[[NSNumber(value: i), 2] as [NSNumber]])
            let centerXPixels = (xmin + xmax) / 2.0
            let centerXNormalized = centerXPixels / modelInputSize
            bestX = centerXNormalized
            bestScore = score
        }

        if let x = bestX {
            handleDetection(normalizedX: x)
        } else {
            handleNoDetection()
        }
    }

    private func handleDetection(normalizedX: CGFloat) {
        framesSinceLastDetection = 0

        let zone = zoneForX(normalizedX)
        recentZones.append(zone)
        if recentZones.count > smoothingWindow {
            recentZones.removeFirst()
        }

        let votedZone = majorityZone(recentZones)
        let targetPan = panForZone(votedZone)

        panLock.lock()
        _currentPanPosition += (targetPan - _currentPanPosition) * smoothingFactor
        panLock.unlock()
    }

    private func handleNoDetection() {
        framesSinceLastDetection += 1
        guard framesSinceLastDetection > occlusionHoldFrames else { return }

        // After hold expires, drift slowly toward center (0.5)
        let driftFactor: CGFloat = 0.02
        panLock.lock()
        _currentPanPosition += (0.5 - _currentPanPosition) * driftFactor
        panLock.unlock()
    }

    // MARK: - Helpers

    private func zoneForX(_ x: CGFloat) -> Zone {
        if x < 1.0 / 3.0 { return .left }
        if x < 2.0 / 3.0 { return .middle }
        return .right
    }

    private func panForZone(_ zone: Zone) -> CGFloat {
        switch zone {
        case .left: return 0.0
        case .middle: return 0.5
        case .right: return 1.0
        }
    }

    private func majorityZone(_ zones: [Zone]) -> Zone {
        var counts: [Zone: Int] = [.left: 0, .middle: 0, .right: 0]
        for z in zones { counts[z, default: 0] += 1 }
        return counts.max(by: { $0.value < $1.value })?.key ?? .middle
    }

    private func decrementInflight() {
        stateLock.lock()
        inflightCount -= 1
        stateLock.unlock()
    }

    private static func loadModel(named name: String) -> VNCoreMLModel? {
        // Try .mlpackage compiled bundle first (.mlmodelc), then .mlmodel
        let candidates = [
            Bundle.main.url(forResource: name, withExtension: "mlmodelc"),
            Bundle.main.url(forResource: name, withExtension: "mlpackage"),
            Bundle.main.url(forResource: name, withExtension: "mlmodel"),
        ]
        guard let modelURL = candidates.compactMap({ $0 }).first else {
            return nil
        }

        do {
            let mlModel = try MLModel(contentsOf: modelURL)
            return try VNCoreMLModel(for: mlModel)
        } catch {
            print("[BallZoneTracker] Failed to load model at \(modelURL): \(error)")
            return nil
        }
    }
}

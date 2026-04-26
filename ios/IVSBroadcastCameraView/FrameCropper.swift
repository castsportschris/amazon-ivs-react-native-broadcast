import AVFoundation
import CoreImage
import Metal

/// Crops a 4K CMSampleBuffer down to a 1080p region based on a horizontal pan
/// position. The vertical center stays fixed; only the horizontal crop window
/// slides across the source frame.
///
/// Pan position is a normalized scalar from 0.0 to 1.0:
///   - 0.0  → leftmost zone (crop window flush against left edge)
///   - 0.5  → middle zone (crop window centered)
///   - 1.0  → rightmost zone (crop window flush against right edge)
///
/// Output buffers come from a CVPixelBufferPool to avoid per-frame allocation.
/// CIContext is Metal-backed for GPU acceleration. Both source and output
/// buffers use the same pixel format (BGRA, matching IVS custom source spec).
final class FrameCropper {

    private let outputWidth: Int
    private let outputHeight: Int
    private let ciContext: CIContext
    private var pixelBufferPool: CVPixelBufferPool?
    private var sourcePixelFormat: OSType = kCVPixelFormatType_32BGRA

    init(outputWidth: Int = 1920, outputHeight: Int = 1080) {
        self.outputWidth = outputWidth
        self.outputHeight = outputHeight

        if let device = MTLCreateSystemDefaultDevice() {
            self.ciContext = CIContext(mtlDevice: device, options: [
                .workingColorSpace: NSNull(),
                .outputColorSpace: NSNull(),
            ])
        } else {
            self.ciContext = CIContext()
        }
    }

    /// Crop the source buffer at the given pan position.
    /// Returns nil if the source is malformed or the buffer pool can't allocate.
    func crop(_ sampleBuffer: CMSampleBuffer, panPosition: CGFloat) -> CMSampleBuffer? {
        guard let sourcePixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            return nil
        }

        let sourceWidth = CVPixelBufferGetWidth(sourcePixelBuffer)
        let sourceHeight = CVPixelBufferGetHeight(sourcePixelBuffer)

        // If the source is already at or below output size, pass it through
        // unchanged — nothing to crop.
        if sourceWidth <= outputWidth && sourceHeight <= outputHeight {
            return sampleBuffer
        }

        if pixelBufferPool == nil {
            createPool(from: sourcePixelBuffer)
        }
        guard let pool = pixelBufferPool else { return nil }

        let clampedPan = max(0.0, min(1.0, panPosition))
        let maxCropX = max(0, sourceWidth - outputWidth)
        let cropX = Int(clampedPan * CGFloat(maxCropX))
        let cropY = max(0, (sourceHeight - outputHeight) / 2)

        var outputBuffer: CVPixelBuffer?
        let status = CVPixelBufferPoolCreatePixelBuffer(nil, pool, &outputBuffer)
        guard status == kCVReturnSuccess, let outBuf = outputBuffer else {
            return nil
        }

        let sourceImage = CIImage(cvPixelBuffer: sourcePixelBuffer)
        let cropRect = CGRect(x: cropX, y: cropY, width: outputWidth, height: outputHeight)
        let croppedImage = sourceImage
            .cropped(to: cropRect)
            .transformed(by: CGAffineTransform(translationX: -CGFloat(cropX), y: -CGFloat(cropY)))

        ciContext.render(croppedImage, to: outBuf)

        return wrapInSampleBuffer(outBuf, source: sampleBuffer)
    }

    private func createPool(from sourcePixelBuffer: CVPixelBuffer) {
        sourcePixelFormat = CVPixelBufferGetPixelFormatType(sourcePixelBuffer)

        let poolAttributes: [String: Any] = [
            kCVPixelBufferPoolMinimumBufferCountKey as String: 3,
        ]
        let pixelBufferAttributes: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: sourcePixelFormat,
            kCVPixelBufferWidthKey as String: outputWidth,
            kCVPixelBufferHeightKey as String: outputHeight,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:],
        ]

        var pool: CVPixelBufferPool?
        CVPixelBufferPoolCreate(
            kCFAllocatorDefault,
            poolAttributes as CFDictionary,
            pixelBufferAttributes as CFDictionary,
            &pool
        )
        self.pixelBufferPool = pool
    }

    private func wrapInSampleBuffer(_ pixelBuffer: CVPixelBuffer, source: CMSampleBuffer) -> CMSampleBuffer? {
        var formatDescription: CMFormatDescription?
        CMVideoFormatDescriptionCreateForImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            formatDescriptionOut: &formatDescription
        )
        guard let format = formatDescription else { return nil }

        var timingInfo = CMSampleTimingInfo()
        CMSampleBufferGetSampleTimingInfo(source, at: 0, timingInfoOut: &timingInfo)

        var newSampleBuffer: CMSampleBuffer?
        let status = CMSampleBufferCreateForImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            dataReady: true,
            makeDataReadyCallback: nil,
            refcon: nil,
            formatDescription: format,
            sampleTiming: &timingInfo,
            sampleBufferOut: &newSampleBuffer
        )

        return status == noErr ? newSampleBuffer : nil
    }
}

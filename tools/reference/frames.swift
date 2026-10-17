import Foundation
import AVFoundation
import ImageIO
import UniformTypeIdentifiers
// usage: frames <video> <outdir> <step_seconds> <maxWidth>
let a = CommandLine.arguments
let url = URL(fileURLWithPath: a[1]), out = a[2], step = Double(a[3])!, maxW = Double(a[4])!
try? FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)
let sem = DispatchSemaphore(value: 0)
Task {
    let asset = AVURLAsset(url: url)
    let dur = try! await asset.load(.duration).seconds
    let g = AVAssetImageGenerator(asset: asset)
    g.appliesPreferredTrackTransform = true
    g.maximumSize = CGSize(width: maxW, height: maxW)
    g.requestedTimeToleranceBefore = .zero; g.requestedTimeToleranceAfter = .zero
    var t = step / 2, i = 0
    while t < dur - 0.05 {
        let img = try! await g.image(at: CMTime(seconds: t, preferredTimescale: 6000)).image
        let path = String(format: "%@/f%03d_%07.3f.png", out, i, t)
        let d = CGImageDestinationCreateWithURL(URL(fileURLWithPath: path) as CFURL, UTType.png.identifier as CFString, 1, nil)!
        CGImageDestinationAddImage(d, img, nil); CGImageDestinationFinalize(d)
        t += step; i += 1
    }
    print("\(url.lastPathComponent): \(i) frames, \(String(format: "%.1f", dur))s")
    sem.signal()
}
sem.wait()

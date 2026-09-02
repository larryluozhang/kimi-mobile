import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

// 生成 Kimi Mobile 图标：蓝紫渐变底 + 白色气泡 K（1024x1024，无圆角无 alpha，iOS 自动裁圆角）
let size = 1024
let cs = CGColorSpaceCreateDeviceRGB()
let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size * 4,
                    space: cs, bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue)!

func rgba(_ hex: UInt32) -> CGColor {
    let r = CGFloat((hex >> 16) & 0xFF) / 255.0
    let g = CGFloat((hex >> 8) & 0xFF) / 255.0
    let b = CGFloat(hex & 0xFF) / 255.0
    return CGColor(colorSpace: cs, components: [r, g, b, 1.0])!
}

// 对角渐变：左上 brand_blue -> 右下 brand_purple
let grad = CGGradient(colorsSpace: cs, colors: [rgba(0x3D7BFA), rgba(0x8B5CF6)] as CFArray, locations: [0, 1])!
ctx.drawLinearGradient(grad, start: CGPoint(x: 0, y: CGFloat(size)), end: CGPoint(x: CGFloat(size), y: 0), options: [])

// 白色气泡（圆角矩形 + 小尾巴）
let bubble = CGRect(x: 212, y: 262, width: 600, height: 480)
let path = CGMutablePath()
path.addRoundedRect(in: bubble, cornerWidth: 130, cornerHeight: 130)
// 尾巴
path.move(to: CGPoint(x: 392, y: 262))
path.addLine(to: CGPoint(x: 332, y: 152))
path.addLine(to: CGPoint(x: 512, y: 262))
path.closeSubpath()
ctx.setFillColor(CGColor(colorSpace: cs, components: [1, 1, 1, 1])!)
ctx.addPath(path)
ctx.fillPath()

// K 字：用品牌蓝
ctx.setFillColor(rgba(0x4E63E8))
let cx = bubble.midX, cy = bubble.midY
let kW: CGFloat = 240, kH: CGFloat = 280, thick: CGFloat = 56
// 竖笔
ctx.fill(CGRect(x: cx - kW/2, y: cy - kH/2, width: thick, height: kH))
// 上斜笔
ctx.saveGState()
ctx.translateBy(x: cx - kW/2 + thick - 6, y: cy)
ctx.rotate(by: CGFloat.pi / 4.6)
ctx.fill(CGRect(x: 0, y: -thick/2, width: kW * 0.78, height: thick))
ctx.restoreGState()
// 下斜笔
ctx.saveGState()
ctx.translateBy(x: cx - kW/2 + thick - 6, y: cy)
ctx.rotate(by: -CGFloat.pi / 4.6)
ctx.fill(CGRect(x: 0, y: -thick/2, width: kW * 0.78, height: thick))
ctx.restoreGState()

let img = ctx.makeImage()!
let outArg = CommandLine.arguments.dropFirst().first(where: { !$0.hasPrefix("-") && $0.hasSuffix(".png") })!
let url = URL(fileURLWithPath: outArg)
let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(dest, img, nil)
CGImageDestinationFinalize(dest)
print("wrote \(url.path)")

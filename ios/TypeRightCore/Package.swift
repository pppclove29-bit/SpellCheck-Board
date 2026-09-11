// swift-tools-version:5.9
import PackageDescription

// Foundation-only core shared by the host app and the keyboard extension.
// Everything here builds and tests with Command Line Tools (`swift test`), no Xcode/UIKit needed.
let package = Package(
    name: "TypeRightCore",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "TypeRightCore", targets: ["TypeRightCore"]),
    ],
    targets: [
        .target(name: "TypeRightCore"),
        .testTarget(name: "TypeRightCoreTests", dependencies: ["TypeRightCore"]),
    ]
)

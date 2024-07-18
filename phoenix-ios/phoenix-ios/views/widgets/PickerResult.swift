import Foundation
import UIKit
import AVFoundation

fileprivate let filename = "PickerResult"
#if DEBUG && true
fileprivate var log = LoggerFactory.shared.logger(filename, .trace)
#else
fileprivate var log = LoggerFactory.shared.logger(filename, .warning)
#endif

class PickerResult: Equatable {
	
	let image: UIImage
	let file: FileCopyResult?
	
	init(image: UIImage, file: FileCopyResult?) {
		self.image = image
		self.file = file
	}
	
	func downscale(maxWidth: CGFloat = 1_000, maxHeight: CGFloat = 1_000) async -> PickerResult {
		
		guard image.size.width > maxWidth, image.size.height > maxHeight else {
			log.debug("PickerResult.downscale: image too small - downsize not required")
			return self
		}
		
		let targetSize = CGSize(width: maxWidth, height: maxHeight)
		guard let scaledImage = image.preparingThumbnail(of: targetSize) else {
			log.error("PickerResult.downscale: image.preparingThumbnail returned nil")
			return self
		}
		
		return PickerResult(image: scaledImage, file: self.file)
	}
	
	func compress() async -> Data? {
		
		if let existingFile = file {
			return PickerResult.compressWithHeic(
				image: image,
				compressionQualities: [1.0, 0.98, 0.94, 0.9],
				targetFileSize: existingFile.size
			)
			
		} else {
			return PickerResult.compressWithHeic(image: image, compressionQuality: 0.98)
		}
	}
	
	private static func compressWithHeic(
		image: UIImage,
		compressionQualities: [CGFloat],
		targetFileSize: Int
	) -> Data? {
		log.trace("compressWithHeic(:::): \(compressionQualities)")
		
		for compressionQuality in compressionQualities {
			if let data = compressWithHeic(image: image, compressionQuality: compressionQuality) {
				let compressedFileSize = data.count
				if compressedFileSize < targetFileSize {
					log.debug("compressedFileSize(\(compressedFileSize)) < targetFileSize(\(targetFileSize))")
					return data
				} else {
					log.debug("compressedFileSize(\(compressedFileSize)) >= targetFileSize(\(targetFileSize))")
				}
			}
		}
		
		return nil
	}
	
	private static func compressWithHeic(
		image: UIImage,
		compressionQuality: CGFloat
	) -> Data? {
		log.trace("compressWithHeic(::) \(compressionQuality)")
		
		let data = NSMutableData()
		guard let imageDestination =
			CGImageDestinationCreateWithData(
				data, AVFileType.heic as CFString, 1, nil
			) else {
				log.error("PickerResult.compressWithHeic: heic not supported")
				return nil
		}
		
		guard let cgImage = image.cgImage else {
			log.error("PickerResult.compressWithHeic: cgImage missing")
			return nil
		}

		let orientation: CGImagePropertyOrientation = image.imageOrientation.cgImageOrientation
		log.debug("uiImageOrientation = \(image.imageOrientation.rawValue)")
		log.debug("cgImageOrientation = \(orientation)")
		
//		let tiffOptions: NSDictionary = [
//			kCGImagePropertyTIFFOrientation as String: NSNumber(value: orientation.rawValue)
//		]
		let options: NSDictionary = [
			kCGImageDestinationLossyCompressionQuality as String: NSNumber(value: compressionQuality),
			kCGImageDestinationOrientation as String: NSNumber(value: orientation.rawValue) // doesn't work ?!?
//			kCGImagePropertyTIFFDictionary as String: tiffOptions
		]
//		CGImageDestinationSetProperties(imageDestination, options)
		
		CGImageDestinationAddImage(imageDestination, cgImage, options)
		guard CGImageDestinationFinalize(imageDestination) else {
			log.error("PickerResult.compressWithHeic: could not finalize")
			return nil
		}
		
		return data as Data
	}
	
	static func == (lhs: PickerResult, rhs: PickerResult) -> Bool {
		if lhs.image != rhs.image {
			return false
		}
		if lhs.file != rhs.file {
			return false
		}
		return true
	}
}

class FileCopyResult: Equatable {
	
	let url: URL
	let size: Int // in bytes
	
	init(url: URL, size: Int) {
		self.url = url
		self.size = size
	}
	
	deinit {
		let fileUrl = url
		DispatchQueue.global(qos: .background).async {
			do {
				if FileManager.default.isDeletableFile(atPath: fileUrl.path) {
					try FileManager.default.removeItem(at: fileUrl)
					log.info("FileCopyResult.cleanup: deleted temp file")
				} else {
					log.debug("FileCopyResult.cleanup: unowned temp file")
				}
			} catch {
				log.error("FileCopyResult.cleanup: cannot delete file: \(error)")
			}
		}
	}
	
	static func == (lhs: FileCopyResult, rhs: FileCopyResult) -> Bool {
		if lhs.url != rhs.url {
			return false
		}
		if lhs.size != rhs.size {
			return false
		}
		return true
	}
}

extension UIImage.Orientation {
	
	// The rawValues for UIImageOrientation do NOT match CGImagePropertyOrientation :(
	// https://developer.apple.com/documentation/imageio/cgimagepropertyorientation
	//
	var cgImageOrientation: CGImagePropertyOrientation {
		switch self {
			case .up            : return CGImagePropertyOrientation.up
			case .upMirrored    : return CGImagePropertyOrientation.upMirrored
			case .down          : return CGImagePropertyOrientation.down
			case .downMirrored  : return CGImagePropertyOrientation.downMirrored
			case .left          : return CGImagePropertyOrientation.left
			case .leftMirrored  : return CGImagePropertyOrientation.leftMirrored
			case .right         : return CGImagePropertyOrientation.right
			case .rightMirrored : return CGImagePropertyOrientation.rightMirrored
			@unknown default    : fatalError("Unknown UIImage.Orientation")
		}
	}
}

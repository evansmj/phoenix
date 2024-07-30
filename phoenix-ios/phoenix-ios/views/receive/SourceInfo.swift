import Foundation

enum SourceType {
	case text
	case image
}

struct SourceInfo {
	let type: SourceType
	let name: String
	let callback: () -> Void
}

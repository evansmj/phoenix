import SwiftUI

/// Sheet content with buttons:
///
/// Copy: Lightning invoice   (text)
/// Copy: QR code            (image)
///
struct CopyShareOptionsSheet: View {
	
	enum ActionType {
		case copy
		case share
	}
	
	let type: ActionType
	let sources: [SourceInfo]
	
	@EnvironmentObject var smartModalState: SmartModalState
	
	@ViewBuilder
	var body: some View {
		
		VStack(alignment: HorizontalAlignment.leading, spacing: 10) {
			
			ForEach(sources.indices, id: \.self) { idx in
				let source = sources[idx]
				
				Button {
					smartModalState.close {
						source.callback()
					}
				} label: {
					HStack(alignment: VerticalAlignment.firstTextBaseline, spacing: 4) {
						Image(systemName: "square.on.square")
							.imageScale(.medium)
						
						switch type {
						case .copy:
							Text("Copy: \(source.name)")
						case .share:
							Text("Share: \(source.name)")
						}
						
						Spacer()
						Group {
							switch source.type {
							case .text:
								Text("(text)")
							case .image:
								Text("(image)")
							}
						}
						.font(.footnote)
						.foregroundColor(.secondary)
					}
					.padding([.top, .bottom], 8)
					.padding([.leading, .trailing], 16)
					.contentShape(Rectangle()) // make Spacer area tappable
				}
				.buttonStyle(
					ScaleButtonStyle(
						cornerRadius: 16,
						borderStroke: Color.appAccent
					)
				)
			} // </ForEach>
		} // </VStack>
		.padding(.all)
	}
}

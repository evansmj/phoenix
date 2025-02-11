import SwiftUI

fileprivate let filename = "DisablingTorSheet"
#if DEBUG && true
fileprivate var log = LoggerFactory.shared.logger(filename, .trace)
#else
fileprivate var log = LoggerFactory.shared.logger(filename, .warning)
#endif

struct DisablingTorSheet: View {
	
	let didCancel: () -> Void
	let didConfirm: () -> Void
	
	@EnvironmentObject var smartModalState: SmartModalState
	
	@ViewBuilder
	var body: some View {
		
		VStack(alignment: HorizontalAlignment.leading, spacing: 0) {
			header()
			content()
		}
	}
	
	@ViewBuilder
	func header() -> some View {
		
		HStack(alignment: VerticalAlignment.center, spacing: 0) {
			Text("Disabling Tor")
				.font(.title3)
				.accessibilityAddTraits(.isHeader)
				.accessibilitySortPriority(100)
			
			Spacer()
		}
		.padding(.horizontal)
		.padding(.vertical, 8)
		.background(
			Color(UIColor.secondarySystemBackground)
				.cornerRadius(15, corners: [.topLeft, .topRight])
		)
		.padding(.bottom, 4)
	}
	
	@ViewBuilder
	func content() -> some View {
		
		VStack(alignment: HorizontalAlignment.leading, spacing: 15) {
			content_message()
			content_buttons()
		}
		.padding(.all)
	}
	
	@ViewBuilder
	func content_message() -> some View {
		
		VStack(alignment: HorizontalAlignment.leading, spacing: 15) {
			
			Text("If you disable Tor, your IP address may be revealed to various service providers.")
			Text("Are you sure you want to proceed ?")
		}
	}
	
	@ViewBuilder
	func content_buttons() -> some View {
		
		HStack(alignment: VerticalAlignment.center, spacing: 25) {
			Spacer()
			
			Button {
				cancelButtonTapped()
			} label: {
				Text("Cancel")
			}
			
			Button {
				confirmButtonTapped()
			} label: {
				Text("Confirm")
			}
		}
		.font(.title3)
	}
	
	// --------------------------------------------------
	// MARK: Actions
	// --------------------------------------------------
	
	func cancelButtonTapped() {
		log.trace("cancelButtonTapped()")
		
		smartModalState.close(animationCompletion: {
			didCancel()
		})
	}
	
	func confirmButtonTapped() {
		log.trace("confirmButtonTapped()")
		
		smartModalState.close(animationCompletion: {
			didConfirm()
		})
	}
}

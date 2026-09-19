import SwiftUI
import WidgetKit

/// docs/04_IOS_IMPLEMENTATION.md §13. One widget in v1: the active parking.
@main
struct ParkingKokWidgetBundle: WidgetBundle {
    var body: some Widget {
        ActiveParkingWidget()
    }
}

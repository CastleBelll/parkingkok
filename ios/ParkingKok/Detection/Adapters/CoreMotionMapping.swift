import CoreMotion

/// Core Motion → domain mapping, kept at the adapter boundary
/// (docs/16_CODING_STANDARDS.md §1).
extension MotionConfidence {
    init(_ confidence: CMMotionActivityConfidence) {
        switch confidence {
        case .low: self = .low
        case .medium: self = .medium
        case .high: self = .high
        @unknown default: self = .low
        }
    }
}

extension MotionAuthorization {
    init(_ status: CMAuthorizationStatus) {
        switch status {
        case .notDetermined: self = .notDetermined
        case .restricted: self = .restricted
        case .denied: self = .denied
        case .authorized: self = .authorized
        @unknown default: self = .notDetermined
        }
    }
}

extension MotionSample {
    /// Copies every flag across independently.
    ///
    /// `CMMotionActivity` can report `automotive` and `stationary` together, so this must
    /// stay a field-by-field copy and never a "pick the dominant one" reduction
    /// (docs/04_IOS_IMPLEMENTATION.md §5).
    init(_ activity: CMMotionActivity) {
        self.init(
            timestamp: activity.startDate,
            automotive: activity.automotive,
            walking: activity.walking,
            stationary: activity.stationary,
            running: activity.running,
            confidence: MotionConfidence(activity.confidence)
        )
    }
}

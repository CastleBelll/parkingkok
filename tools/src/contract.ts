/**
 * The vocabulary both platforms have to agree on, transcribed from
 * docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md.
 *
 * Everything here is a contract quote, not a tool-local invention. When the contract
 * changes, this file changes first and the parsers follow.
 */

/** Contract §3. States. */
export const DETECTION_STATES = [
  'IDLE',
  'DRIVING_CANDIDATE',
  'DRIVING',
  'PARKING_CANDIDATE',
  'PARKED',
  'DEPARTURE_CANDIDATE',
] as const;
export type DetectionState = (typeof DETECTION_STATES)[number];

/** Contract §5. Confidence is a bucket across the boundary, never a raw score. */
export const CONFIDENCE_BUCKETS = ['low', 'medium', 'high'] as const;
export type ConfidenceBucket = (typeof CONFIDENCE_BUCKETS)[number];

/** Contract §4. Stable reason codes shared by analytics and fixtures. */
export const REASON_CODES = [
  'recent_vehicle_activity',
  'vehicle_duration_met',
  'vehicle_distance_met',
  'vehicle_exit_detected',
  'walking_after_vehicle',
  'stationary_after_vehicle',
  'location_stopped',
  'location_quality_degraded',
  'reliable_location_captured',
  'car_projection_disconnected',
  'candidate_timeout',
] as const;
export type ReasonCode = (typeof REASON_CODES)[number];

/**
 * The event vocabulary, in the snake_case spelling §8 and §9 already use.
 *
 * §8 and §9 only spell out `vehicle_enter`, `vehicle_exit`, `walking_enter`, `location`
 * and `location_quality_degraded`. The rest are the remaining §2 normalized events in the
 * same spelling convention, kept here so a recorder emitting them converts instead of
 * failing. Both platforms must agree on this list — it is the one place to change it.
 *
 * `stationary_enter`/`stationary_exit` keep the enter/exit symmetry the rest of the motion
 * vocabulary has. §2 only names `MotionBecameStationary`, but docs/04_ANDROID §2 wants
 * STILL ENTER/EXIT as supporting evidence and Android observes both on real devices; iOS
 * derives the exit from the Core Motion `stationary` flag going false. The missing half is
 * a gap in §2, not a signal that does not exist.
 */
export const EVENT_TYPES = [
  'vehicle_enter',
  'vehicle_exit',
  'walking_enter',
  'stationary_enter',
  'stationary_exit',
  'location',
  'location_quality_degraded',
  'car_projection_connected',
  'car_projection_disconnected',
  'timer_tick',
  'user_confirmed_parking',
  'user_rejected_parking',
] as const;
export type EventType = (typeof EVENT_TYPES)[number];

/** Contract §9. What a human can label a recorded session as. */
export const LABEL_MODES = [
  'car',
  'bus',
  'subway',
  'taxi',
  'walk',
  'still',
  'unknown',
] as const;
export type LabelMode = (typeof LABEL_MODES)[number];

/**
 * Location accuracy buckets carried by `location_quality_degraded`.
 *
 * `good` <= 20m, `fair` <= 35m, `poor` > 35m.
 *
 * These thresholds are deliberately their own constants and must **not** be wired to the
 * reliable-location threshold in `detector.reliableAccuracyMeters` (docs/03 §10), even
 * though 35m happens to match it today. That value is remote-config tunable; binding the
 * buckets to it would retroactively change what a year-old trace means. A recording format
 * has to stay comparable over time.
 *
 * A negative accuracy has no bucket. Contract §5 calls it invalid and the platform adapters
 * already drop it, so one reaching a trace is an adapter defect — it must surface, not be
 * swallowed into `poor`.
 */
export const LOCATION_QUALITY_BUCKETS = ['good', 'fair', 'poor'] as const;
export type LocationQualityBucket = (typeof LOCATION_QUALITY_BUCKETS)[number];

export const LOCATION_QUALITY_GOOD_MAX_METERS = 20;
export const LOCATION_QUALITY_FAIR_MAX_METERS = 35;

export const PLATFORMS = ['ios', 'android'] as const;
export type Platform = (typeof PLATFORMS)[number];

/** The only trace schema version this converter understands (contract §9). */
export const SUPPORTED_TRACE_SCHEMA_VERSION = 1;

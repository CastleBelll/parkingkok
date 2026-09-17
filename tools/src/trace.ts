/**
 * Parser for the on-device trace format — docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9.
 *
 * A trace is the raw recording: absolute timestamps, session metadata, and the label a
 * human attached afterwards. It is never a parity fixture; `convert.ts` turns it into one.
 */

import {
  CONFIDENCE_BUCKETS,
  EVENT_TYPES,
  LABEL_MODES,
  LOCATION_QUALITY_BUCKETS,
  PLATFORMS,
  SUPPORTED_TRACE_SCHEMA_VERSION,
  type ConfidenceBucket,
  type EventType,
  type LabelMode,
  type LocationQualityBucket,
  type Platform,
} from './contract';
import { assertNoCoordinate } from './privacy';
import {
  allowOnlyKeys,
  asArray,
  asObject,
  fail,
  optionalEnum,
  optionalNumber,
  optionalString,
  requireEnum,
  requireNullableBoolean,
  requireNumber,
  requireString,
  ValidationError,
} from './schema';

/**
 * A whole day of recording must not become one unreviewable fixture. Traces longer than
 * this are a recorder or labelling mistake; the fix is to split the session, not to raise
 * the cap.
 */
export const MAX_TRACE_EVENTS = 5_000;

export interface TraceLabel {
  readonly mode: LabelMode;
  /** `null` means the labeller did not say whether the session ended in a parking. */
  readonly parked: boolean | null;
  readonly note?: string | undefined;
}

export interface TraceEvent {
  readonly type: EventType;
  readonly atMillis: number;
  readonly confidence?: ConfidenceBucket | undefined;
  readonly accuracy?: number | undefined;
  readonly speed?: number | undefined;
  readonly distanceFromPreviousM?: number | undefined;
  readonly fromBucket?: LocationQualityBucket | undefined;
  readonly toBucket?: LocationQualityBucket | undefined;
  readonly platform?: Platform | undefined;
  readonly floor?: string | undefined;
}

/** Contract §9 "gap 계측". Counts and a duration — nothing here can hold a place. */
export interface TraceGapStats {
  readonly maxGapMillis: number;
  readonly gapsOver10MinCount: number;
  readonly gapsOver20MinCount: number;
}

/**
 * Contract §9 "사람이 세션을 나눈다". A parent id and the instant it was cut at —
 * nothing here can hold a place either.
 */
export interface TraceSplitProvenance {
  readonly parentSessionId: string;
  readonly atMillis: number;
}

export interface Trace {
  readonly schemaVersion: number;
  readonly sessionId: string;
  readonly platform: Platform;
  readonly deviceModel: string;
  readonly osVersion: string;
  readonly appVersion: string;
  readonly startedAt: number;
  readonly endedAt: number;
  readonly label: TraceLabel;
  readonly events: readonly TraceEvent[];
  readonly gapStats?: TraceGapStats | undefined;
  readonly splitFrom?: TraceSplitProvenance | undefined;
}

const TRACE_KEYS = [
  'schemaVersion',
  'sessionId',
  'platform',
  'deviceModel',
  'osVersion',
  'appVersion',
  'startedAt',
  'endedAt',
  'label',
  'events',
  // Widening the whitelist is the only sanctioned way to add a session key, and each
  // addition has to argue it cannot carry a coordinate. Both of these are contract §9
  // fields: gapStats is three counts and a duration, splitFrom is a uuid and an instant.
  'gapStats',
  'splitFrom',
] as const;

const GAP_STATS_KEYS = ['maxGapMillis', 'gapsOver10MinCount', 'gapsOver20MinCount'] as const;
const SPLIT_FROM_KEYS = ['parentSessionId', 'atMillis'] as const;

const LABEL_KEYS = ['mode', 'parked', 'note'] as const;

/**
 * The extra keys each event type may carry, beyond `type` and `atMillis`.
 *
 * This table is the event schema. Anything absent from it is rejected, which is what keeps
 * a coordinate from riding along on a `location` event.
 */
const EVENT_EXTRA_KEYS: Record<EventType, readonly string[]> = {
  vehicle_enter: ['confidence'],
  vehicle_exit: ['confidence'],
  walking_enter: ['confidence'],
  stationary_enter: ['confidence'],
  stationary_exit: ['confidence'],
  location: ['accuracy', 'speed', 'distanceFromPreviousM'],
  location_quality_degraded: ['fromBucket', 'toBucket'],
  car_projection_connected: ['platform'],
  car_projection_disconnected: ['platform'],
  timer_tick: [],
  user_confirmed_parking: ['floor'],
  user_rejected_parking: [],
};

function parseLabel(value: unknown, path: string): TraceLabel {
  const object = asObject(value, path);
  allowOnlyKeys(object, path, LABEL_KEYS);
  return {
    mode: requireEnum(object, path, 'mode', LABEL_MODES),
    parked: requireNullableBoolean(object, path, 'parked'),
    note: optionalString(object, path, 'note'),
  };
}

/**
 * Contract §5 calls a negative accuracy invalid, and the platform adapters already drop it.
 * One reaching a trace means the adapter let it through, so it fails loudly here instead of
 * being rounded into a quality bucket.
 */
function parseAccuracy(object: Record<string, unknown>, path: string): number {
  const accuracy = requireNumber(object, path, 'accuracy');
  if (accuracy < 0) {
    fail(
      `${path}.accuracy`,
      `${String(accuracy)} is invalid (contract §5). A negative accuracy is an adapter defect; ` +
        'it must not be recorded or bucketed',
    );
  }
  return accuracy;
}

function parseEvent(value: unknown, path: string): TraceEvent {
  const object = asObject(value, path);
  const type = requireEnum(object, path, 'type', EVENT_TYPES);
  allowOnlyKeys(object, path, ['type', 'atMillis', ...EVENT_EXTRA_KEYS[type]]);

  const event: TraceEvent = {
    type,
    atMillis: requireNumber(object, path, 'atMillis', { integer: true, min: 0 }),
    confidence: optionalEnum(object, path, 'confidence', CONFIDENCE_BUCKETS),
    accuracy: type === 'location' ? parseAccuracy(object, path) : undefined,
    speed: optionalNumber(object, path, 'speed', { min: 0 }),
    distanceFromPreviousM: optionalNumber(object, path, 'distanceFromPreviousM', { min: 0 }),
    fromBucket: type === 'location_quality_degraded'
      ? requireEnum(object, path, 'fromBucket', LOCATION_QUALITY_BUCKETS)
      : undefined,
    toBucket: type === 'location_quality_degraded'
      ? requireEnum(object, path, 'toBucket', LOCATION_QUALITY_BUCKETS)
      : undefined,
    platform: type === 'car_projection_connected' || type === 'car_projection_disconnected'
      ? requireEnum(object, path, 'platform', PLATFORMS)
      : undefined,
    floor: optionalString(object, path, 'floor'),
  };
  return event;
}

/**
 * The ordering rule, on its own so `repair.ts` can hold its own output to it.
 *
 * A trace that fails this is a recorder defect or a clock adjustment mid-session, and
 * saying so is the whole value of the check — `trace2fixture convert --repair` is the
 * deliberate, reported exception, never a default.
 */
export function assertOrderedByTime(events: readonly TraceEvent[], path: string): void {
  events.forEach((event, index) => {
    const previous = events[index - 1];
    if (previous !== undefined && event.atMillis < previous.atMillis) {
      fail(
        `${path}[${String(index)}].atMillis`,
        `events must be ordered by time, but ${String(event.atMillis)} follows ` +
          `${String(previous.atMillis)}. Out-of-order timestamps mean a recorder bug or a ` +
          'clock adjustment mid-session; fix the recording rather than sorting it here. ' +
          'A recording that cannot be made again can be rescued with `convert --repair`, ' +
          'which reports every event it changes',
      );
    }
  });
}

function parseEvents(value: unknown, path: string, options: ParseTraceOptions): TraceEvent[] {
  const raw = asArray(value, path);
  if (raw.length === 0) {
    fail(path, 'a trace with no events cannot be converted; there is nothing to time-base against');
  }
  if (raw.length > MAX_TRACE_EVENTS) {
    fail(
      path,
      `${String(raw.length)} events exceeds the ${String(MAX_TRACE_EVENTS)}-event cap; ` +
        'split the recording into per-trip sessions rather than raising the cap',
    );
  }

  const events = raw.map((entry, index) => parseEvent(entry, `${path}[${String(index)}]`));
  if (options.allowOutOfOrder !== true) assertOrderedByTime(events, path);
  return events;
}

export interface ParseTraceOptions {
  /**
   * Let events run backwards, for a caller that is about to hand them to `repairTrace`.
   * Off by default, and every other check still applies: this loosens the one rule the
   * repair exists to restore, not the schema.
   */
  readonly allowOutOfOrder?: boolean;
}

/** Parses and validates an already-decoded trace document. */
export function parseTrace(value: unknown, path = 'trace', options: ParseTraceOptions = {}): Trace {
  const object = asObject(value, path);
  allowOnlyKeys(object, path, TRACE_KEYS);

  const schemaVersion = requireNumber(object, path, 'schemaVersion', { integer: true, min: 1 });
  if (schemaVersion !== SUPPORTED_TRACE_SCHEMA_VERSION) {
    fail(
      `${path}.schemaVersion`,
      `this converter understands version ${String(SUPPORTED_TRACE_SCHEMA_VERSION)}, got ${String(schemaVersion)}`,
    );
  }

  const startedAt = requireNumber(object, path, 'startedAt', { integer: true, min: 0 });
  const endedAt = requireNumber(object, path, 'endedAt', { integer: true, min: 0 });
  if (endedAt < startedAt) {
    fail(`${path}.endedAt`, `must not precede startedAt (${String(startedAt)})`);
  }

  return {
    schemaVersion,
    sessionId: requireString(object, path, 'sessionId'),
    platform: requireEnum(object, path, 'platform', PLATFORMS),
    deviceModel: requireString(object, path, 'deviceModel'),
    osVersion: requireString(object, path, 'osVersion'),
    appVersion: requireString(object, path, 'appVersion'),
    startedAt,
    endedAt,
    label: parseLabel(object['label'], `${path}.label`),
    events: parseEvents(object['events'], `${path}.events`, options),
    gapStats: parseGapStats(object['gapStats'], `${path}.gapStats`),
    splitFrom: parseSplitFrom(object['splitFrom'], `${path}.splitFrom`),
  };
}

/**
 * Both of these are optional: a session recorded before the fields existed is still a
 * valid trace, and an unsplit session has no parent. Absent stays absent — a default
 * would invent provenance that was never recorded.
 */
function parseGapStats(value: unknown, path: string): TraceGapStats | undefined {
  if (value === undefined) return undefined;
  const object = asObject(value, path);
  allowOnlyKeys(object, path, GAP_STATS_KEYS);
  return {
    maxGapMillis: requireNumber(object, path, 'maxGapMillis', { integer: true, min: 0 }),
    gapsOver10MinCount: requireNumber(object, path, 'gapsOver10MinCount', { integer: true, min: 0 }),
    gapsOver20MinCount: requireNumber(object, path, 'gapsOver20MinCount', { integer: true, min: 0 }),
  };
}

function parseSplitFrom(value: unknown, path: string): TraceSplitProvenance | undefined {
  if (value === undefined) return undefined;
  const object = asObject(value, path);
  allowOnlyKeys(object, path, SPLIT_FROM_KEYS);
  return {
    parentSessionId: requireString(object, path, 'parentSessionId'),
    atMillis: requireNumber(object, path, 'atMillis', { integer: true, min: 0 }),
  };
}

/**
 * Parses trace JSON text.
 *
 * The coordinate scan runs on the raw text first, so a banned field is reported as the
 * privacy violation it is rather than as a generic unknown key.
 */
export function parseTraceText(
  text: string,
  path = 'trace',
  options: ParseTraceOptions = {},
): Trace {
  assertNoCoordinate(text, path);
  let decoded: unknown;
  try {
    decoded = JSON.parse(text);
  } catch (error) {
    throw new ValidationError(
      `${path}: not valid JSON (${error instanceof Error ? error.message : String(error)})`,
    );
  }
  return parseTrace(decoded, path, options);
}

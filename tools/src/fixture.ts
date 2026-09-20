/**
 * Parser for the parity fixture format — docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §8.
 *
 * A fixture is the cross-platform contract: relative seconds and `expected`, nothing else.
 * Recording metadata stays in the trace (§9) so it cannot pollute the parity contract.
 *
 * Two shapes pass through here:
 *  - **confirmed** — `expected` filled in by a human. Only these are real parity fixtures.
 *  - **draft** — what the converter emits: `expected: null` plus a `_todo` block. A draft
 *    is deliberately *not* a valid fixture, so the human review step cannot be skipped by
 *    accident; the validator's default mode rejects it.
 */

import {
  CONFIDENCE_BUCKETS,
  DETECTION_STATES,
  EVENT_TYPES,
  LABEL_MODES,
  LOCATION_QUALITY_BUCKETS,
  PLATFORMS,
  REASON_CODES,
  type ConfidenceBucket,
  type DetectionState,
  type EventType,
  type LabelMode,
  type LocationQualityBucket,
  type Platform,
  type ReasonCode,
} from './contract';
import { assertNoCoordinate } from './privacy';
import {
  allowOnlyKeys,
  asArray,
  asObject,
  fail,
  optionalBoolean,
  optionalEnum,
  optionalNumber,
  optionalString,
  optionalStringArray,
  requireBoolean,
  requireEnum,
  requireNullableBoolean,
  requireNumber,
  requireString,
  ValidationError,
} from './schema';

export const TODO_STATUS = 'needs_human_review';

export interface FixtureEvent {
  readonly type: EventType;
  /** Seconds since the first event of the session (contract §9, Conversion). */
  readonly t: number;
  readonly confidence?: ConfidenceBucket | undefined;
  readonly accuracy?: number | undefined;
  readonly speed?: number | undefined;
  readonly distanceFromPreviousM?: number | undefined;
  readonly fromBucket?: LocationQualityBucket | undefined;
  readonly toBucket?: LocationQualityBucket | undefined;
  readonly platform?: Platform | undefined;
  readonly floor?: string | undefined;
}

export interface FixtureExpectation {
  readonly candidate: boolean;
  readonly confidence?: ConfidenceBucket | undefined;
  readonly finalState: DetectionState;
  readonly requiredReasons?: readonly ReasonCode[] | undefined;
}

/** Provenance a reviewer needs, minus anything that could carry a place or free text. */
export interface TodoSource {
  readonly sessionId: string;
  readonly platform: Platform;
  readonly labelMode: LabelMode;
  readonly labelParked: boolean | null;
  readonly durationSeconds: number;
  readonly eventCount: number;
}

/**
 * What the label alone can suggest — never more than that.
 *
 * Deliberately narrower than {@link FixtureExpectation}: `confidence` and `requiredReasons`
 * are judgements about what the engine should have concluded, and no label implies them.
 * Every field is optional, because a label often justifies `candidate` without justifying
 * a final state.
 */
export interface ProposedExpectation {
  readonly candidate?: boolean | undefined;
  readonly finalState?: DetectionState | undefined;
}

/**
 * What `convert --repair` changed, so a rescued recording can never be mistaken for one
 * the device wrote.
 *
 * Indices into the *source trace's* `events`, not this fixture's: the point is to send a
 * reviewer back to the original file to see what was there. Present only when the repair
 * actually changed something — a `--repair` run with nothing to repair produces the same
 * fixture as a plain convert, and saying so in the file would be noise, not provenance.
 */
export interface FixtureRepair {
  /** Events dropped because an identical one was already recorded. */
  readonly droppedReplayIndices: readonly number[];
  /** Events that ran backwards and were moved into time order. */
  readonly reorderedIndices: readonly number[];
}

export interface FixtureTodo {
  readonly status: typeof TODO_STATUS;
  /** A suggestion derived from the label. `null` when the label does not justify one. */
  readonly proposedExpected: ProposedExpectation | null;
  readonly rationale: string;
  readonly source: TodoSource;
  readonly repair?: FixtureRepair | undefined;
}

export interface ConfirmedFixture {
  readonly kind: 'confirmed';
  readonly name: string;
  readonly initialState: DetectionState;
  readonly events: readonly FixtureEvent[];
  readonly expected: FixtureExpectation;
}

export interface DraftFixture {
  readonly kind: 'draft';
  readonly name: string;
  readonly initialState: DetectionState;
  readonly events: readonly FixtureEvent[];
  readonly todo: FixtureTodo;
}

export type FixtureDocument = ConfirmedFixture | DraftFixture;

const FIXTURE_KEYS = ['name', 'initialState', 'events', 'expected', '_todo'] as const;
const EXPECTED_KEYS = ['candidate', 'confidence', 'finalState', 'requiredReasons'] as const;
const PROPOSED_KEYS = ['candidate', 'finalState'] as const;
const TODO_KEYS = ['status', 'proposedExpected', 'rationale', 'source', 'repair'] as const;
const REPAIR_KEYS = ['droppedReplayIndices', 'reorderedIndices'] as const;
const SOURCE_KEYS = [
  'sessionId',
  'platform',
  'labelMode',
  'labelParked',
  'durationSeconds',
  'eventCount',
] as const;

/**
 * Extra keys per event type. `fromBucket`/`toBucket` are optional here but required in a
 * trace: `platform-tests/tunnel_no_parking.json` predates the trace format and omits them.
 */
const EVENT_EXTRA_KEYS: Record<EventType, readonly string[]> = {
  vehicle_enter: ['confidence'],
  vehicle_exit: ['confidence'],
  walking_enter: ['confidence'],
  stationary_enter: ['confidence'],
  stationary_exit: ['confidence'],
  location: ['accuracy', 'speed', 'distanceFromPreviousM'],
  location_quality_degraded: ['fromBucket', 'toBucket'],
  projection_connected: ['platform'],
  projection_disconnected: ['platform'],
  bluetooth_car_connected: [],
  bluetooth_car_disconnected: [],
  timer_tick: [],
  user_confirmed: ['floor'],
  user_rejected: [],
};

function parseEvent(value: unknown, path: string): FixtureEvent {
  const object = asObject(value, path);
  const type = requireEnum(object, path, 'type', EVENT_TYPES);
  allowOnlyKeys(object, path, ['type', 't', ...EVENT_EXTRA_KEYS[type]]);

  return {
    type,
    t: requireNumber(object, path, 't', { min: 0 }),
    confidence: optionalEnum(object, path, 'confidence', CONFIDENCE_BUCKETS),
    accuracy: type === 'location'
      ? requireNumber(object, path, 'accuracy', { min: 0 })
      : undefined,
    speed: optionalNumber(object, path, 'speed', { min: 0 }),
    distanceFromPreviousM: optionalNumber(object, path, 'distanceFromPreviousM', { min: 0 }),
    fromBucket: optionalEnum(object, path, 'fromBucket', LOCATION_QUALITY_BUCKETS),
    toBucket: optionalEnum(object, path, 'toBucket', LOCATION_QUALITY_BUCKETS),
    platform: optionalEnum(object, path, 'platform', PLATFORMS),
    floor: optionalString(object, path, 'floor'),
  };
}

function parseEvents(value: unknown, path: string): FixtureEvent[] {
  const raw = asArray(value, path);
  if (raw.length === 0) fail(path, 'a fixture must drive the engine with at least one event');

  const events = raw.map((entry, index) => parseEvent(entry, `${path}[${String(index)}]`));
  events.forEach((event, index) => {
    const previous = events[index - 1];
    if (previous !== undefined && event.t < previous.t) {
      fail(
        `${path}[${String(index)}].t`,
        `events must be ordered by time, but ${String(event.t)} follows ${String(previous.t)}`,
      );
    }
  });
  return events;
}

function parseExpectation(value: unknown, path: string): FixtureExpectation {
  const object = asObject(value, path);
  allowOnlyKeys(object, path, EXPECTED_KEYS);
  const reasons = optionalStringArray(object, path, 'requiredReasons');
  reasons?.forEach((reason, index) => {
    if (!(REASON_CODES as readonly string[]).includes(reason)) {
      fail(
        `${path}.requiredReasons[${String(index)}]`,
        `"${reason}" is not a contract §4 reason code. Platform SDK enum values must never ` +
          'be used as product reason codes',
      );
    }
  });

  return {
    candidate: requireBoolean(object, path, 'candidate'),
    confidence: optionalEnum(object, path, 'confidence', CONFIDENCE_BUCKETS),
    finalState: requireEnum(object, path, 'finalState', DETECTION_STATES),
    requiredReasons: reasons as readonly ReasonCode[] | undefined,
  };
}

function parseProposedExpectation(value: unknown, path: string): ProposedExpectation {
  const object = asObject(value, path);
  allowOnlyKeys(object, path, PROPOSED_KEYS);
  return {
    candidate: optionalBoolean(object, path, 'candidate'),
    finalState: optionalEnum(object, path, 'finalState', DETECTION_STATES),
  };
}

function parseSource(value: unknown, path: string): TodoSource {
  const object = asObject(value, path);
  allowOnlyKeys(object, path, SOURCE_KEYS);
  return {
    sessionId: requireString(object, path, 'sessionId'),
    platform: requireEnum(object, path, 'platform', PLATFORMS),
    labelMode: requireEnum(object, path, 'labelMode', LABEL_MODES),
    labelParked: requireNullableBoolean(object, path, 'labelParked'),
    durationSeconds: requireNumber(object, path, 'durationSeconds', { min: 0 }),
    eventCount: requireNumber(object, path, 'eventCount', { integer: true, min: 1 }),
  };
}

function parseIndexList(
  object: Record<string, unknown>,
  path: string,
  key: string,
): readonly number[] {
  const raw = asArray(object[key], `${path}.${key}`);
  return raw.map((entry, index) => {
    const where = `${path}.${key}[${String(index)}]`;
    if (typeof entry !== 'number' || !Number.isInteger(entry) || entry < 0) {
      fail(where, `expected a non-negative integer trace event index, got ${JSON.stringify(entry)}`);
    }
    return entry;
  });
}

function parseRepair(value: unknown, path: string): FixtureRepair {
  const object = asObject(value, path);
  allowOnlyKeys(object, path, REPAIR_KEYS);
  const repair: FixtureRepair = {
    droppedReplayIndices: parseIndexList(object, path, 'droppedReplayIndices'),
    reorderedIndices: parseIndexList(object, path, 'reorderedIndices'),
  };
  if (repair.droppedReplayIndices.length === 0 && repair.reorderedIndices.length === 0) {
    fail(path, 'records a repair that changed nothing; omit the block instead');
  }
  return repair;
}

function parseTodo(value: unknown, path: string): FixtureTodo {
  const object = asObject(value, path);
  allowOnlyKeys(object, path, TODO_KEYS);
  const status = requireString(object, path, 'status');
  if (status !== TODO_STATUS) fail(`${path}.status`, `expected "${TODO_STATUS}", got "${status}"`);

  return {
    status: TODO_STATUS,
    proposedExpected:
      object['proposedExpected'] === null
        ? null
        : parseProposedExpectation(object['proposedExpected'], `${path}.proposedExpected`),
    rationale: requireString(object, path, 'rationale'),
    source: parseSource(object['source'], `${path}.source`),
    repair: 'repair' in object ? parseRepair(object['repair'], `${path}.repair`) : undefined,
  };
}

export interface ParseFixtureOptions {
  /** Accept converter drafts (`expected: null` + `_todo`). Off by default: drafts are not fixtures. */
  readonly allowDraft?: boolean;
}

/** Parses and validates an already-decoded fixture document. */
export function parseFixture(
  value: unknown,
  path = 'fixture',
  options: ParseFixtureOptions = {},
): FixtureDocument {
  const object = asObject(value, path);
  allowOnlyKeys(object, path, FIXTURE_KEYS);

  const name = requireString(object, path, 'name');
  const initialState = requireEnum(object, path, 'initialState', DETECTION_STATES);
  const events = parseEvents(object['events'], `${path}.events`);
  const isDraft = object['expected'] === null || object['expected'] === undefined;

  if (!isDraft) {
    if ('_todo' in object) {
      fail(
        `${path}._todo`,
        'a fixture with a filled-in `expected` must not keep `_todo`. Delete the block once ' +
          'the expectation has been reviewed',
      );
    }
    return {
      kind: 'confirmed',
      name,
      initialState,
      events,
      expected: parseExpectation(object['expected'], `${path}.expected`),
    };
  }

  if (options.allowDraft !== true) {
    fail(
      `${path}.expected`,
      'is missing. A converted trace is a draft, not a parity fixture: a recording is not ' +
        'an answer key. Review the `_todo` proposal, write `expected`, delete `_todo`',
    );
  }
  if (!('_todo' in object)) {
    fail(`${path}._todo`, 'a draft with no `expected` must carry a `_todo` block explaining why');
  }

  return { kind: 'draft', name, initialState, events, todo: parseTodo(object['_todo'], `${path}._todo`) };
}

/**
 * Parses fixture JSON text.
 *
 * The coordinate scan runs on the raw text first, so a banned field is reported as the
 * privacy violation it is rather than as a generic unknown key.
 */
export function parseFixtureText(
  text: string,
  path = 'fixture',
  options: ParseFixtureOptions = {},
): FixtureDocument {
  assertNoCoordinate(text, path);
  let decoded: unknown;
  try {
    decoded = JSON.parse(text);
  } catch (error) {
    throw new ValidationError(
      `${path}: not valid JSON (${error instanceof Error ? error.message : String(error)})`,
    );
  }
  return parseFixture(decoded, path, options);
}

/** Field order for a serialized event, so generated fixtures diff cleanly. */
const EVENT_FIELD_ORDER = [
  'type',
  't',
  'accuracy',
  'speed',
  'distanceFromPreviousM',
  'confidence',
  'fromBucket',
  'toBucket',
  'platform',
  'floor',
] as const;

function formatEvent(event: FixtureEvent): string {
  const record = event as unknown as Record<string, unknown>;
  const fields = EVENT_FIELD_ORDER.filter((key) => record[key] !== undefined).map(
    (key) => `${JSON.stringify(key)}:${JSON.stringify(record[key])}`,
  );
  return `{${fields.join(',')}}`;
}

function indentBlock(json: string, spaces: number): string {
  const pad = ' '.repeat(spaces);
  return json
    .split('\n')
    .map((line, index) => (index === 0 ? line : `${pad}${line}`))
    .join('\n');
}

/**
 * Renders a fixture document the way the hand-written fixtures in `platform-tests/` are
 * written: one event per line, so a diff shows which event changed rather than which
 * field of which event.
 */
export function formatFixture(document: FixtureDocument): string {
  const events = document.events.map((event) => `    ${formatEvent(event)}`).join(',\n');
  const tail =
    document.kind === 'confirmed'
      ? `  "expected": ${indentBlock(JSON.stringify(document.expected, null, 2), 2)}`
      : `  "expected": null,\n  "_todo": ${indentBlock(JSON.stringify(document.todo, null, 2), 2)}`;

  return (
    `{\n` +
    `  "name": ${JSON.stringify(document.name)},\n` +
    `  "initialState": ${JSON.stringify(document.initialState)},\n` +
    `  "events": [\n${events}\n  ],\n` +
    `${tail}\n}\n`
  );
}

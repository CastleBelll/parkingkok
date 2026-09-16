/**
 * trace (contract §9) → parity fixture draft (contract §8).
 *
 * One direction only. Recording metadata must not pollute the parity contract, and
 * recording straight into fixture form would throw away the absolute timestamps and the
 * label that make a trace reviewable.
 *
 * The converter is pure: no clock, no filesystem, no sleeping. Time comes from the trace.
 */

import type { DetectionState, LabelMode } from './contract';
import {
  formatFixture,
  TODO_STATUS,
  type DraftFixture,
  type FixtureEvent,
  type FixtureTodo,
  type ProposedExpectation,
} from './fixture';
import { assertNoCoordinate } from './privacy';
import { fail } from './schema';
import type { Trace, TraceEvent, TraceLabel } from './trace';

const MILLIS_PER_SECOND = 1000;

/**
 * A trace does not record the engine's state at session start — §9 has no field for it and
 * this converter must not add one. `IDLE` is the low-power resting state, so it is the
 * right default; `--initial-state` overrides it and the `_todo` says it was an assumption.
 */
export const DEFAULT_INITIAL_STATE: DetectionState = 'IDLE';

export interface ConvertOptions {
  /** Fixture name. Falls back to the trace's session id. */
  readonly name?: string | undefined;
  readonly initialState?: DetectionState | undefined;
}

/**
 * Converts an absolute timestamp to seconds relative to the session's first event.
 *
 * Rounds to the nearest second, half away from zero, because engine thresholds are
 * second-scale and §8 fixtures are written in whole seconds. Rounding is monotonic, so an
 * ordered trace stays ordered.
 */
export function toRelativeSeconds(atMillis: number, baseMillis: number): number {
  return Math.round((atMillis - baseMillis) / MILLIS_PER_SECOND);
}

function toFixtureEvent(event: TraceEvent, baseMillis: number): FixtureEvent {
  return {
    type: event.type,
    t: toRelativeSeconds(event.atMillis, baseMillis),
    confidence: event.confidence,
    accuracy: event.accuracy,
    speed: event.speed,
    distanceFromPreviousM: event.distanceFromPreviousM,
    fromBucket: event.fromBucket,
    toBucket: event.toBucket,
    platform: event.platform,
    floor: event.floor,
  };
}

interface Suggestion {
  readonly proposal: ProposedExpectation | null;
  readonly rationale: string;
}

/** Negative-case modes: you can ride one without ever parking a car (§17 #4, #5, #6). */
const NOT_A_CAR_TRIP: readonly LabelMode[] = ['bus', 'subway'];
const NOT_A_VEHICLE_AT_ALL: readonly LabelMode[] = ['walk', 'still'];

/**
 * Turns a human label into a *suggestion*, never into an answer.
 *
 * Contract §9 is explicit: a recording is not an answer key. What the engine should have
 * done is a judgement, so this only proposes the part a label genuinely implies —
 * typically `candidate` — and leaves confidence and reason codes to the reviewer.
 */
export function suggestExpectation(label: TraceLabel): Suggestion {
  const { mode, parked } = label;

  if (mode === 'unknown') {
    return {
      proposal: null,
      rationale:
        'The session is unlabelled, so nothing can be proposed. Label the recording in the ' +
        'app, or decide the expectation from the events alone.',
    };
  }

  if (NOT_A_VEHICLE_AT_ALL.includes(mode) && parked !== true) {
    return {
      proposal: { candidate: false, finalState: 'IDLE' },
      rationale:
        `The label says "${mode}" — no vehicle session happened, so contract §6 cannot be ` +
        'satisfied and the engine should have stayed idle. Confirm the events really contain ' +
        'no vehicle activity before accepting this.',
    };
  }

  if (NOT_A_CAR_TRIP.includes(mode) && parked !== true) {
    return {
      proposal: { candidate: false },
      rationale:
        `The label says "${mode}", a required negative case ` +
        '(docs/05_PARKING_DETECTION_ENGINE.md §17 #5, #6): the rider parked no car, so no ' +
        'candidate should have been created. The final state is a judgement — a bus trip can ' +
        'legitimately leave the engine in a driving state — so decide it from the events.',
    };
  }

  if (mode === 'taxi') {
    return {
      proposal: null,
      rationale:
        'The label says "taxi". docs/05_PARKING_DETECTION_ENGINE.md §2 and §17 #4 call this ' +
        'the known limitation: public activity APIs cannot tell a taxi from the user\'s own ' +
        'car, so both a candidate and no candidate are defensible. Decide deliberately which ' +
        'behaviour this fixture pins down, and say so in the fixture name.',
    };
  }

  if (mode === 'car' && parked === true) {
    return {
      proposal: { candidate: true, finalState: 'CANDIDATE_PENDING' },
      rationale:
        'The label says a car trip that ended in a parking, so a candidate is the expected ' +
        'outcome. Check the events actually carry all three of contract §6 — a meaningful ' +
        'vehicle session, an end to it, and a confirmation signal — before accepting this; ' +
        'GPS degradation alone does not satisfy the rule. Confidence and requiredReasons are ' +
        'yours to decide.',
    };
  }

  if (mode === 'car' && parked === false) {
    return {
      proposal: { candidate: false },
      rationale:
        'The label says a car trip that did not end in a parking. No candidate should have ' +
        'been created; the final state depends on where the recording stops (still driving, ' +
        'a fuel stop, a red light), so decide it from the events.',
    };
  }

  if (parked === true) {
    return {
      proposal: null,
      rationale:
        `The label contradicts itself: mode "${mode}" with parked=true. Re-label the session ` +
        'before deriving an expectation from it.',
    };
  }

  return {
    proposal: null,
    rationale:
      `The label leaves parked unset for mode "${mode}", which is the half that decides the ` +
      'expectation. Re-label the session or decide from the events.',
  };
}

function buildTodo(trace: Trace, assumedInitialState: boolean): FixtureTodo {
  const suggestion = suggestExpectation(trace.label);
  const assumption = assumedInitialState
    ? ` A trace carries no engine state, so initialState was assumed to be ` +
      `${DEFAULT_INITIAL_STATE}; correct it if the recording started mid-trip.`
    : '';

  return {
    status: TODO_STATUS,
    proposedExpected: suggestion.proposal,
    rationale: `${suggestion.rationale}${assumption}`,
    source: {
      sessionId: trace.sessionId,
      platform: trace.platform,
      labelMode: trace.label.mode,
      labelParked: trace.label.parked,
      durationSeconds: toRelativeSeconds(trace.endedAt, trace.startedAt),
      eventCount: trace.events.length,
    },
  };
}

/**
 * Converts a parsed trace into a draft fixture.
 *
 * The result is a draft on purpose: `expected` is left unset and the `_todo` block carries
 * the proposal and its reasoning. `label.note` is *not* carried over — it is free text a
 * human typed on a phone, which is exactly where a pasted coordinate would hide, and §8
 * has no field for it.
 */
export function convertTrace(trace: Trace, options: ConvertOptions = {}): DraftFixture {
  const first = trace.events[0];
  if (first === undefined) fail('trace.events', 'a trace with no events cannot be converted');

  const name = options.name ?? trace.sessionId;
  if (name.length === 0) fail('name', 'a fixture needs a non-empty name');

  return {
    kind: 'draft',
    name,
    initialState: options.initialState ?? DEFAULT_INITIAL_STATE,
    events: trace.events.map((event) => toFixtureEvent(event, first.atMillis)),
    todo: buildTodo(trace, options.initialState === undefined),
  };
}

/**
 * Converts a trace and renders it as fixture JSON text.
 *
 * The rendered bytes are scanned for coordinates before they are returned. Checking the
 * encoded output rather than the field list is the same technique the diagnostics export
 * tests use on both platforms, and it is the check that would still catch a coordinate
 * that arrived through a field nobody thought to look at.
 */
export function convertTraceToJson(trace: Trace, options: ConvertOptions = {}): string {
  const json = formatFixture(convertTrace(trace, options));
  assertNoCoordinate(json, 'converted fixture');
  return json;
}

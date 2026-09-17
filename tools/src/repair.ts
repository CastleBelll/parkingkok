/**
 * Opt-in recovery for a trace whose recorder replayed events it had already written.
 *
 * ### Why this exists at all
 * A trace is a recording, and a recording is not something you get to do again. The
 * September 2026 subway trace is 2h39m of underground field data with a defect in the
 * middle of it: Core Location re-delivered two cached fixes when significant-change
 * monitoring was re-registered, and the recorder of the day wrote them a second time, one
 * of them 128 s behind the events already in the file. The recorder has since grown a
 * watermark, but that only fixes recordings made from now on. This is the rescue for the
 * ones already on disk.
 *
 * ### Why it is opt-in, and stays opt-in
 * `parseTrace` rejects an out-of-order trace by default and must keep doing so: silently
 * straightening a recording is how a recorder defect stops being noticed. Repair runs only
 * behind an explicit flag, reports every event it touched, and writes what it did into the
 * fixture it produces, so nothing it changed can be mistaken for what the device recorded.
 *
 * ### What counts as the same observation
 * Two events are one observation delivered twice when every field the *device observed*
 * matches — the instant, the type, the accuracy, the speed, the confidence, the buckets.
 *
 * `distanceFromPreviousM` is deliberately not in that list on the replay's side. It is not
 * observed; the recorder derives it from the previous coordinate, which is held in memory
 * only and never persisted (contract §9 bans coordinates outright). A re-delivered fix
 * therefore arrives with no anchor and reports no distance where the original reported
 * one — which is exactly what the subway trace shows. A replay may lose the distance; it
 * may never *disagree* about one, and a candidate carrying a different value is not a
 * replay.
 *
 * ### What it will and will not do
 * An event is only repaired when it collides with time already recorded — either running
 * backwards, or landing on an instant a same-type event already holds. Nothing else is the
 * repair's business: a trace legitimately carries several events at one instant, because a
 * `walking_enter` and a `stationary_exit` come out of one motion sample. A colliding event
 * has to be explained as one of exactly two things:
 *
 *  - **a replay** — the same observation as one already kept. Dropped.
 *  - **a late delivery** — an observation that collides with nothing. Moved into time
 *    order, which is where it belonged.
 *
 * Anything else is refused. A same-type event at the same instant whose observed fields
 * disagree is neither: two contradictory accounts of one observation are an unknown
 * situation, and guessing which one the device meant is exactly the kind of quiet repair
 * this module exists not to do.
 */

import type { EventType } from './contract';
import { fail } from './schema';
import { assertOrderedByTime, type Trace, type TraceEvent } from './trace';

/** One event dropped because the file already held that observation. */
export interface DroppedReplay {
  /** Index into the source trace's `events`. */
  readonly index: number;
  readonly type: EventType;
  readonly atMillis: number;
  /** Index of the event it repeated, which was kept instead. */
  readonly duplicateOfIndex: number;
}

/** One event moved back into time order. */
export interface MovedEvent {
  readonly index: number;
  readonly type: EventType;
  readonly atMillis: number;
  /** The timestamp it was recorded after, and therefore ran backwards from. */
  readonly recordedAfterMillis: number;
}

export interface TraceRepair {
  readonly droppedReplays: readonly DroppedReplay[];
  readonly movedEvents: readonly MovedEvent[];
}

export interface RepairedTrace {
  readonly trace: Trace;
  readonly repair: TraceRepair;
}

/** Whether the repair changed anything at all. */
export function isRepaired(repair: TraceRepair): boolean {
  return repair.droppedReplays.length > 0 || repair.movedEvents.length > 0;
}

/**
 * Every field the device observed, in a fixed order, as one string.
 *
 * Listed field by field rather than `JSON.stringify(event)` so that key order in the
 * source file cannot make one observation look like two — and so that adding a field to
 * `TraceEvent` without deciding whether it is observed or derived is a type error rather
 * than a duplicate silently slipping through.
 */
function observed(event: TraceEvent): string {
  const fields: Record<Exclude<keyof TraceEvent, 'distanceFromPreviousM'>, unknown> = {
    type: event.type,
    atMillis: event.atMillis,
    confidence: event.confidence,
    accuracy: event.accuracy,
    speed: event.speed,
    fromBucket: event.fromBucket,
    toBucket: event.toBucket,
    platform: event.platform,
    floor: event.floor,
  };
  return JSON.stringify(fields);
}

/** See the module note: a replay may lose the derived distance, never disagree about it. */
function isReplayOf(original: TraceEvent, candidate: TraceEvent): boolean {
  if (observed(original) !== observed(candidate)) return false;
  return (
    candidate.distanceFromPreviousM === undefined ||
    candidate.distanceFromPreviousM === original.distanceFromPreviousM
  );
}

function describe(event: TraceEvent, index: number): string {
  return `events[${String(index)}] (${event.type} @${String(event.atMillis)})`;
}

interface Kept {
  readonly event: TraceEvent;
  readonly index: number;
}

/**
 * Drops replayed events and restores time order, reporting everything it touched.
 *
 * Idempotent: repairing an already-repaired trace changes nothing and reports nothing.
 *
 * @throws ValidationError when a colliding event is neither a replay nor a late delivery —
 * see the module note.
 */
export function repairTrace(trace: Trace): RepairedTrace {
  const kept: Kept[] = [];
  const droppedReplays: DroppedReplay[] = [];
  const movedEvents: MovedEvent[] = [];
  let newestKeptMillis = Number.NEGATIVE_INFINITY;

  trace.events.forEach((event, index) => {
    const isBehind = event.atMillis < newestKeptMillis;
    // A same-type event at an instant already recorded is the one collision that cannot be
    // two separate observations, whether it arrived backwards or not.
    const twin = kept.find(
      (candidate) =>
        candidate.event.atMillis === event.atMillis && candidate.event.type === event.type,
    );

    if (twin !== undefined) {
      if (isReplayOf(twin.event, event)) {
        droppedReplays.push({
          index,
          type: event.type,
          atMillis: event.atMillis,
          duplicateOfIndex: twin.index,
        });
        return;
      }
      fail(
        `trace.events[${String(index)}]`,
        `${describe(event, index)} lands on an instant ${describe(twin.event, twin.index)} ` +
          'already holds, and the two disagree about what was observed. That is an unknown ' +
          'situation, not a duplicate, and repair will not guess which one the device meant',
      );
    }

    if (isBehind) {
      movedEvents.push({
        index,
        type: event.type,
        atMillis: event.atMillis,
        recordedAfterMillis: newestKeptMillis,
      });
    } else {
      newestKeptMillis = event.atMillis;
    }
    kept.push({ event, index });
  });

  // Stable, so events sharing an instant keep the order the device recorded them in —
  // `walking_enter` before `stationary_exit`, a `location` before the degradation it implies.
  const events = kept
    .slice()
    .sort((left, right) => left.event.atMillis - right.event.atMillis)
    .map((entry) => entry.event);

  // The repair's own contract, not a defence against the input: if a pass ever leaves the
  // stream unordered, that is a bug here and it must not reach a fixture.
  assertOrderedByTime(events, 'repaired trace.events');

  return { trace: { ...trace, events }, repair: { droppedReplays, movedEvents } };
}

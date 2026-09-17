import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { convertTrace } from './convert';
import { isRepaired, repairTrace } from './repair';
import { at, trace } from './testing';
import { parseTrace, parseTraceText, type Trace, type TraceEvent } from './trace';

/** The subway trace's defect in miniature: a fix, a later fix, then both again. */
function replayed(): Trace {
  const events: TraceEvent[] = [
    { type: 'vehicle_enter', atMillis: at(0), confidence: 'high' },
    { type: 'location', atMillis: at(30), accuracy: 1000 },
    { type: 'location', atMillis: at(60), accuracy: 47.9, distanceFromPreviousM: 928 },
    // Core Location handing back its cache, oldest first.
    { type: 'location', atMillis: at(30), accuracy: 1000 },
    { type: 'location', atMillis: at(90), accuracy: 30, distanceFromPreviousM: 17 },
  ];
  return { ...trace(), events, endedAt: at(90) };
}

function millis(document: Trace): number[] {
  return document.events.map((event) => event.atMillis);
}

describe('the ordering rule stays on by default', () => {
  it('rejects a trace whose events run backwards', () => {
    // Arrange / Act / Assert — a recorder defect must not convert silently.
    assert.throws(() => parseTrace(JSON.parse(JSON.stringify(replayed()))), /ordered by time/);
  });

  it('points at --repair rather than leaving the operator stuck', () => {
    // Arrange / Act / Assert
    assert.throws(() => parseTrace(JSON.parse(JSON.stringify(replayed()))), /--repair/);
  });

  it('parses the same trace when the caller opts out of the ordering rule', () => {
    // Arrange
    const document = JSON.parse(JSON.stringify(replayed())) as unknown;

    // Act
    const parsed = parseTrace(document, 'trace', { allowOutOfOrder: true });

    // Assert — everything else is still checked; only the order is deferred.
    assert.equal(parsed.events.length, 5);
  });

  it('still rejects a coordinate when out-of-order events are allowed', () => {
    // Arrange
    const document = JSON.parse(JSON.stringify(replayed())) as Record<string, unknown>;
    const events = document['events'] as Record<string, unknown>[];
    events[1] = { ...events[1], latitude: 37.123_456_7 };

    // Act / Assert
    assert.throws(
      () => parseTraceText(JSON.stringify(document), 'trace', { allowOutOfOrder: true }),
      /banned/,
    );
  });
});

describe('repairTrace', () => {
  it('drops an event identical to one already recorded', () => {
    // Arrange
    const source = repairTrace(
      parseTrace(JSON.parse(JSON.stringify(replayed())), 'trace', { allowOutOfOrder: true }),
    );

    // Act / Assert
    assert.deepEqual(millis(source.trace), [at(0), at(30), at(60), at(90)]);
    assert.equal(source.repair.droppedReplays.length, 1);
    assert.deepEqual(source.repair.droppedReplays[0], {
      index: 3,
      type: 'location',
      atMillis: at(30),
      duplicateOfIndex: 1,
    });
    assert.equal(source.repair.movedEvents.length, 0);
  });

  it('moves a late delivery into time order instead of dropping it', () => {
    // Arrange — an observation that appears nowhere else in the file.
    const events: TraceEvent[] = [
      { type: 'location', atMillis: at(0), accuracy: 8 },
      { type: 'location', atMillis: at(60), accuracy: 8 },
      { type: 'location', atMillis: at(30), accuracy: 12 },
    ];

    // Act
    const { trace: repaired, repair } = repairTrace({ ...trace(), events, endedAt: at(60) });

    // Assert
    assert.deepEqual(millis(repaired), [at(0), at(30), at(60)]);
    assert.equal(repaired.events[1]?.accuracy, 12);
    assert.equal(repair.droppedReplays.length, 0);
    assert.deepEqual(repair.movedEvents, [
      { index: 2, type: 'location', atMillis: at(30), recordedAfterMillis: at(60) },
    ]);
  });

  it('refuses a backwards event that contradicts one already at that instant', () => {
    // Arrange — same type, same instant, a different accuracy. Not a duplicate.
    const events: TraceEvent[] = [
      { type: 'location', atMillis: at(0), accuracy: 8 },
      { type: 'location', atMillis: at(60), accuracy: 8 },
      { type: 'location', atMillis: at(0), accuracy: 9 },
    ];

    // Act / Assert
    assert.throws(
      () => repairTrace({ ...trace(), events, endedAt: at(60) }),
      /unknown situation, not a duplicate/,
    );
  });

  it('refuses a same-instant repeat that disagrees, even arriving forwards', () => {
    // Arrange — nothing runs backwards here; the second fix simply contradicts the first.
    const events: TraceEvent[] = [
      { type: 'location', atMillis: at(60), accuracy: 47.9 },
      { type: 'location', atMillis: at(60), accuracy: 12 },
    ];

    // Act / Assert
    assert.throws(
      () => repairTrace({ ...trace(), events, endedAt: at(60) }),
      /unknown situation, not a duplicate/,
    );
  });

  it('drops a re-delivery that only lost its derived distance', () => {
    // Arrange — the subway trace's second defect. `distanceFromPreviousM` is derived from
    // a coordinate that is never persisted, so a replay cannot rebuild it; the accuracy
    // agreeing to thirteen decimal places is what says these are one observation.
    const events: TraceEvent[] = [
      { type: 'location', atMillis: at(60), accuracy: 47.861312782481704, distanceFromPreviousM: 928 },
      { type: 'location', atMillis: at(60), accuracy: 47.861312782481704 },
    ];

    // Act
    const { trace: repaired, repair } = repairTrace({ ...trace(), events, endedAt: at(60) });

    // Assert — the one that carries the evidence is the one kept.
    assert.equal(repaired.events.length, 1);
    assert.equal(repaired.events[0]?.distanceFromPreviousM, 928);
    assert.deepEqual(repair.droppedReplays.map((dropped) => dropped.index), [1]);
  });

  it('refuses a repeat that gained a distance the original never had', () => {
    // Arrange — a replay may lose the derived value, never invent one.
    const events: TraceEvent[] = [
      { type: 'location', atMillis: at(60), accuracy: 47.9 },
      { type: 'location', atMillis: at(60), accuracy: 47.9, distanceFromPreviousM: 928 },
    ];

    // Act / Assert
    assert.throws(
      () => repairTrace({ ...trace(), events, endedAt: at(60) }),
      /unknown situation, not a duplicate/,
    );
  });

  it('leaves different events that share an instant alone', () => {
    // Arrange — one motion sample yields two transitions at the same millisecond, and a
    // location event implies a degradation stamped with it.
    const events: TraceEvent[] = [
      { type: 'walking_enter', atMillis: at(0), confidence: 'high' },
      { type: 'stationary_exit', atMillis: at(0), confidence: 'high' },
      { type: 'location', atMillis: at(60), accuracy: 120 },
      { type: 'location_quality_degraded', atMillis: at(60), fromBucket: 'good', toBucket: 'poor' },
    ];

    // Act
    const { trace: repaired, repair } = repairTrace({ ...trace(), events, endedAt: at(60) });

    // Assert
    assert.equal(repaired.events.length, 4);
    assert.equal(isRepaired(repair), false);
  });

  it('changes nothing in an already-ordered trace', () => {
    // Arrange
    const source = trace();

    // Act
    const { trace: repaired, repair } = repairTrace(source);

    // Assert
    assert.deepEqual(millis(repaired), millis(source));
    assert.equal(isRepaired(repair), false);
  });

  it('is idempotent — repairing a repaired trace is a no-op', () => {
    // Arrange
    const once = repairTrace(
      parseTrace(JSON.parse(JSON.stringify(replayed())), 'trace', { allowOutOfOrder: true }),
    );

    // Act
    const twice = repairTrace(once.trace);

    // Assert
    assert.deepEqual(millis(twice.trace), millis(once.trace));
    assert.equal(isRepaired(twice.repair), false);
  });
});

describe('the draft records that it was repaired', () => {
  it('names the source events that were dropped and moved', () => {
    // Arrange
    const { trace: repaired, repair } = repairTrace(
      parseTrace(JSON.parse(JSON.stringify(replayed())), 'trace', { allowOutOfOrder: true }),
    );

    // Act
    const draft = convertTrace(repaired, { repair });

    // Assert
    assert.deepEqual(draft.todo.repair, { droppedReplayIndices: [3], reorderedIndices: [] });
    assert.match(draft.todo.rationale, /--repair/);
  });

  it('says nothing when the repair changed nothing', () => {
    // Arrange
    const { trace: repaired, repair } = repairTrace(trace());

    // Act
    const draft = convertTrace(repaired, { repair });

    // Assert — a fixture that needed no rescue must not claim one.
    assert.equal(draft.todo.repair, undefined);
    assert.doesNotMatch(draft.todo.rationale, /--repair/);
  });

  it('is left out entirely by an ordinary convert', () => {
    // Arrange / Act
    const draft = convertTrace(trace());

    // Assert
    assert.equal(draft.todo.repair, undefined);
  });
});

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { convertTrace, convertTraceToJson, suggestExpectation, toRelativeSeconds } from './convert';
import { parseFixtureText } from './fixture';
import { at, BASE_MILLIS, trace } from './testing';
import type { Trace } from './trace';

describe('toRelativeSeconds', () => {
  it('rounds to the nearest second, half away from zero', () => {
    // Arrange
    const cases: readonly [number, number][] = [
      [0, 0],
      [499, 0],
      [500, 1],
      [1_499, 1],
      [1_500, 2],
      [2_500, 3],
    ];

    for (const [offsetMillis, expected] of cases) {
      // Act
      const actual = toRelativeSeconds(BASE_MILLIS + offsetMillis, BASE_MILLIS);

      // Assert
      assert.equal(actual, expected, `${String(offsetMillis)}ms`);
    }
  });

  it('stays monotonic, so an ordered trace stays ordered', () => {
    // Arrange — two events 1ms apart that round to the same second.
    const first = toRelativeSeconds(BASE_MILLIS + 1_499, BASE_MILLIS);
    const second = toRelativeSeconds(BASE_MILLIS + 1_500, BASE_MILLIS);

    // Assert
    assert.ok(second >= first);
  });
});

describe('convertTrace', () => {
  it('bases relative time on the first event, not on startedAt', () => {
    // Arrange — the recorder opened the session 20s before the first event fired.
    const recording: Trace = {
      ...trace(),
      startedAt: at(-20),
      events: [
        { type: 'vehicle_enter', atMillis: at(0) },
        { type: 'walking_enter', atMillis: at(270) },
      ],
    };

    // Act
    const draft = convertTrace(recording);

    // Assert
    assert.equal(draft.events[0]?.t, 0);
    assert.equal(draft.events[1]?.t, 270);
  });

  it('leaves expected as a TODO rather than treating the recording as an answer key', () => {
    // Arrange / Act
    const draft = convertTrace(trace());

    // Assert
    assert.equal(draft.kind, 'draft');
    assert.equal(draft.todo.status, 'needs_human_review');
    assert.ok(draft.todo.rationale.length > 0);
  });

  it('never proposes confidence or reason codes, which no label implies', () => {
    // Arrange / Act
    const draft = convertTrace(trace());

    // Assert — the proposal type has no room for them, and the rendered draft has none.
    assert.deepEqual(Object.keys(draft.todo.proposedExpected ?? {}).sort(), [
      'candidate',
      'finalState',
    ]);
  });

  it('carries every §8 event field through unchanged apart from the timestamp', () => {
    // Arrange / Act
    const draft = convertTrace(trace());

    // Assert
    assert.deepEqual(draft.events[1], {
      type: 'location',
      t: 30,
      accuracy: 8,
      speed: 9.2,
      distanceFromPreviousM: 41,
      confidence: undefined,
      fromBucket: undefined,
      toBucket: undefined,
      platform: undefined,
      floor: undefined,
    });
  });

  it('assumes IDLE when no initial state is given, and says so', () => {
    // Arrange / Act
    const draft = convertTrace(trace());

    // Assert
    assert.equal(draft.initialState, 'IDLE');
    assert.match(draft.todo.rationale, /assumed/);
  });

  it('uses an explicit initial state without claiming it was assumed', () => {
    // Arrange / Act
    const draft = convertTrace(trace(), { initialState: 'DRIVING' });

    // Assert
    assert.equal(draft.initialState, 'DRIVING');
    assert.doesNotMatch(draft.todo.rationale, /assumed/);
  });
});

describe('convertTraceToJson', () => {
  /**
   * The fixture is copied off the device and committed, so a coordinate reaching it would
   * walk parking locations straight past docs/00_CORE_RULES.md Privacy. Checked against the
   * **encoded bytes** rather than the field list, same reasoning and same technique as
   * `ios/ParkingKokTests/DiagnosticsReportTests.swift` and the Android `DiagnosticsReportTest`:
   * a field-by-field check only covers the fields somebody remembered to look at.
   */
  it('no coordinate survives into the encoded fixture, even when the trace object has one', () => {
    // Arrange — distinctive values that would be unmistakable in the output. The parser
    // would reject these from a file, so they are injected past it on purpose.
    const smuggled = {
      ...trace(),
      events: [
        {
          type: 'location' as const,
          atMillis: at(0),
          accuracy: 12,
          latitude: 37.123_456_7,
          longitude: 127.987_654_3,
        },
      ],
    } as unknown as Trace;

    // Act
    const json = convertTraceToJson(smuggled);

    // Assert
    assert.ok(!json.includes('37.123'));
    assert.ok(!json.includes('127.987'));
    assert.ok(!json.toLowerCase().includes('latitude'));
    assert.ok(!json.toLowerCase().includes('longitude'));
  });

  it('drops the free-text note, which is where a pasted coordinate would hide', () => {
    // Arrange
    const recording: Trace = {
      ...trace(),
      label: { mode: 'car', parked: true, note: '37.123456, 127.987654 근처' },
    };

    // Act
    const json = convertTraceToJson(recording);

    // Assert
    assert.ok(!json.includes('37.123'));
    assert.ok(!json.includes('note'));
  });

  it('renders a draft that the fixture validator accepts as a draft and rejects as a fixture', () => {
    // Arrange
    const json = convertTraceToJson(trace(), { name: 'car_then_walk' });

    // Act
    const asDraft = parseFixtureText(json, 'draft', { allowDraft: true });

    // Assert
    assert.equal(asDraft.kind, 'draft');
    assert.equal(asDraft.name, 'car_then_walk');
    assert.throws(() => parseFixtureText(json, 'draft'), /not an answer key/);
  });
});

describe('suggestExpectation', () => {
  it('proposes no candidate for a bus ride, the required negative case', () => {
    // Arrange / Act
    const suggestion = suggestExpectation({ mode: 'bus', parked: false });

    // Assert — candidate is what the label implies; the final state is not.
    assert.deepEqual(suggestion.proposal, { candidate: false });
  });

  it('proposes a candidate for a car trip that ended in a parking', () => {
    // Arrange / Act
    const suggestion = suggestExpectation({ mode: 'car', parked: true });

    // Assert
    assert.deepEqual(suggestion.proposal, { candidate: true, finalState: 'CANDIDATE_PENDING' });
  });

  it('refuses to propose for a taxi, which is the documented known limitation', () => {
    // Arrange / Act
    const suggestion = suggestExpectation({ mode: 'taxi', parked: true });

    // Assert
    assert.equal(suggestion.proposal, null);
    assert.match(suggestion.rationale, /known limitation/);
  });

  it('refuses to propose from an unlabelled session', () => {
    // Arrange / Act
    const suggestion = suggestExpectation({ mode: 'unknown', parked: null });

    // Assert
    assert.equal(suggestion.proposal, null);
  });

  it('flags a self-contradictory label instead of guessing which half is right', () => {
    // Arrange — a subway ride cannot have ended in parking a car.
    const suggestion = suggestExpectation({ mode: 'subway', parked: true });

    // Assert
    assert.equal(suggestion.proposal, null);
    assert.match(suggestion.rationale, /contradicts itself/);
  });

  it('refuses to propose when the label leaves parked unset', () => {
    // Arrange / Act
    const suggestion = suggestExpectation({ mode: 'car', parked: null });

    // Assert
    assert.equal(suggestion.proposal, null);
  });
});

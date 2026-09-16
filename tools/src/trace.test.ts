import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { MAX_TRACE_EVENTS, parseTrace, parseTraceText } from './trace';
import { at, traceDocument } from './testing';

describe('parseTrace', () => {
  it('accepts a contract §9 trace', () => {
    // Arrange
    const document = traceDocument();

    // Act
    const parsed = parseTrace(document);

    // Assert
    assert.equal(parsed.events.length, 4);
    assert.equal(parsed.label.mode, 'car');
    assert.equal(parsed.label.parked, true);
  });

  it('rejects a coordinate field before it can reach a fixture', () => {
    // Arrange — the failure the whole format exists to prevent.
    const document = traceDocument();
    const events = document['events'] as Record<string, unknown>[];
    events[1] = { ...events[1], latitude: 37.123_456_7, longitude: 127.987_654_3 };

    // Act / Assert — via text, which is how a trace actually arrives.
    assert.throws(() => parseTraceText(JSON.stringify(document), 'trace'), /banned/);
  });

  it('rejects an unknown key even when it does not look like a coordinate', () => {
    // Arrange
    const document = traceDocument({ nickname: 'home run' });

    // Act / Assert
    assert.throws(() => parseTrace(document), /unknown key/);
  });

  it('rejects a missing required field', () => {
    // Arrange
    const document = traceDocument();
    delete document['sessionId'];

    // Act / Assert
    assert.throws(() => parseTrace(document), /sessionId/);
  });

  it('rejects an unsupported schema version rather than guessing', () => {
    // Arrange
    const document = traceDocument({ schemaVersion: 2 });

    // Act / Assert
    assert.throws(() => parseTrace(document), /version 1/);
  });

  it('rejects out-of-order timestamps instead of silently sorting them', () => {
    // Arrange — a clock adjustment mid-session, or a recorder bug.
    const document = traceDocument();
    const events = document['events'] as Record<string, unknown>[];
    events[2] = { ...events[2], atMillis: at(10) };

    // Act / Assert
    assert.throws(() => parseTrace(document), /ordered by time/);
  });

  it('rejects an empty event list', () => {
    // Arrange — there would be nothing to time-base the fixture against.
    const document = traceDocument({ events: [] });

    // Act / Assert
    assert.throws(() => parseTrace(document), /no events/);
  });

  it('rejects a recording longer than the event cap', () => {
    // Arrange — a whole day left recording must not become one unreviewable fixture.
    const events = Array.from({ length: MAX_TRACE_EVENTS + 1 }, (_unused, index) => ({
      type: 'timer_tick',
      atMillis: at(index),
    }));

    // Act / Assert
    assert.throws(() => parseTrace(traceDocument({ events })), /exceeds the/);
  });

  it('requires accuracy on a location event', () => {
    // Arrange
    const document = traceDocument({
      events: [{ type: 'location', atMillis: at(0), speed: 4 }],
    });

    // Act / Assert
    assert.throws(() => parseTrace(document), /accuracy/);
  });

  it('rejects a platform SDK enum smuggled in as an event type', () => {
    // Arrange — contract §4: never expose SDK enum values as product vocabulary.
    const document = traceDocument({
      events: [{ type: 'IN_VEHICLE', atMillis: at(0) }],
    });

    // Act / Assert
    assert.throws(() => parseTrace(document), /expected one of/);
  });

  it('reports malformed JSON as a parse failure, not a crash', () => {
    // Arrange
    const text = '{"schemaVersion":1,';

    // Act / Assert
    assert.throws(() => parseTraceText(text, 'broken.json'), /not valid JSON/);
  });
});

describe('the vocabulary the two platforms share', () => {
  it('accepts stationary_enter and stationary_exit as a symmetric pair', () => {
    // Arrange — Android observes both STILL transitions; iOS derives the exit.
    const document = traceDocument({
      events: [
        { type: 'stationary_enter', atMillis: at(0), confidence: 'high' },
        { type: 'stationary_exit', atMillis: at(60), confidence: 'medium' },
      ],
    });

    // Act
    const parsed = parseTrace(document);

    // Assert
    assert.deepEqual(
      parsed.events.map((event) => event.type),
      ['stationary_enter', 'stationary_exit'],
    );
  });

  it('rejects the bare "stationary" spelling that breaks the enter/exit pattern', () => {
    // Arrange
    const document = traceDocument({ events: [{ type: 'stationary', atMillis: at(0) }] });

    // Act / Assert
    assert.throws(() => parseTrace(document), /expected one of/);
  });

  it('accepts only the agreed location quality buckets', () => {
    // Arrange
    const degraded = (fromBucket: string, toBucket: string): Record<string, unknown> =>
      traceDocument({
        events: [{ type: 'location_quality_degraded', atMillis: at(0), fromBucket, toBucket }],
      });

    // Act / Assert
    assert.doesNotThrow(() => parseTrace(degraded('good', 'fair')));
    assert.doesNotThrow(() => parseTrace(degraded('fair', 'poor')));
    assert.throws(() => parseTrace(degraded('good', 'terrible')), /good \| fair \| poor/);
  });

  it('rejects a negative accuracy instead of bucketing it as poor', () => {
    // Arrange — contract §5 calls this invalid; reaching a trace means an adapter defect.
    const document = traceDocument({
      events: [{ type: 'location', atMillis: at(0), accuracy: -1 }],
    });

    // Act / Assert
    assert.throws(() => parseTrace(document), /adapter defect/);
  });
});

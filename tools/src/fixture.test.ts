import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';

import { formatFixture, parseFixture, parseFixtureText } from './fixture';

/** `lib/` sits one level deeper than the repo root at runtime. */
const FIXTURE_DIR = join(__dirname, '..', '..', 'platform-tests');

function validFixture(): Record<string, unknown> {
  return {
    name: 'vehicle_then_walk',
    initialState: 'IDLE',
    events: [
      { type: 'vehicle_enter', t: 0 },
      { type: 'location', t: 30, accuracy: 8, speed: 9 },
      { type: 'vehicle_exit', t: 240 },
      { type: 'walking_enter', t: 270 },
    ],
    expected: { candidate: true, confidence: 'high', finalState: 'PARKING_CANDIDATE' },
  };
}

describe('the committed parity fixtures', () => {
  it('all satisfy the contract §8 schema', () => {
    // Arrange
    const files = readdirSync(FIXTURE_DIR).filter((name) => name.endsWith('.json'));

    // Assert — a fixture directory that has quietly gone empty would pass vacuously.
    assert.ok(files.length >= 2, 'expected the committed fixtures to be found');

    for (const file of files) {
      // Act
      const document = parseFixtureText(readFileSync(join(FIXTURE_DIR, file), 'utf8'), file);

      // Assert
      assert.equal(document.kind, 'confirmed', `${file} must be a reviewed fixture`);
    }
  });
});

describe('parseFixture', () => {
  it('rejects a fixture carrying a coordinate', () => {
    // Arrange
    const document = validFixture();
    (document['events'] as Record<string, unknown>[])[1] = {
      type: 'location',
      t: 30,
      accuracy: 8,
      latitude: 37.123_456_7,
    };

    // Act / Assert
    assert.throws(() => parseFixtureText(JSON.stringify(document), 'bad'), /banned/);
  });

  it('rejects a fixture missing a required field', () => {
    // Arrange
    const document = validFixture();
    delete (document['expected'] as Record<string, unknown>)['finalState'];

    // Act / Assert
    assert.throws(() => parseFixture(document), /finalState/);
  });

  it('rejects an unreviewed draft by default', () => {
    // Arrange
    const document = {
      ...validFixture(),
      expected: null,
      _todo: {
        status: 'needs_human_review',
        proposedExpected: { candidate: false },
        rationale: 'bus ride',
        source: {
          sessionId: 'x',
          platform: 'ios',
          labelMode: 'bus',
          labelParked: false,
          durationSeconds: 300,
          eventCount: 4,
        },
      },
    };

    // Act / Assert
    assert.throws(() => parseFixture(document), /not an answer key/);
    assert.equal(parseFixture(document, 'fixture', { allowDraft: true }).kind, 'draft');
  });

  it('rejects a reviewed fixture that still carries its _todo block', () => {
    // Arrange — the review is only finished when the TODO is gone.
    const document = { ...validFixture(), _todo: { status: 'needs_human_review' } };

    // Act / Assert
    assert.throws(() => parseFixture(document, 'fixture', { allowDraft: true }), /must not keep/);
  });

  it('rejects a reason code that is not in contract §4', () => {
    // Arrange — a platform SDK enum leaking into product vocabulary.
    const document = validFixture();
    (document['expected'] as Record<string, unknown>)['requiredReasons'] = ['IN_VEHICLE_EXIT'];

    // Act / Assert
    assert.throws(() => parseFixture(document), /reason code/);
  });

  it('rejects a state that is not in contract §3', () => {
    // Arrange — the engine doc lists CANDIDATE_PENDING; the parity contract does not.
    const document = { ...validFixture(), initialState: 'CANDIDATE_PENDING' };

    // Act / Assert
    assert.throws(() => parseFixture(document), /expected one of/);
  });

  it('rejects out-of-order events', () => {
    // Arrange
    const document = validFixture();
    (document['events'] as Record<string, unknown>[])[3] = { type: 'walking_enter', t: 10 };

    // Act / Assert
    assert.throws(() => parseFixture(document), /ordered by time/);
  });
});

describe('formatFixture', () => {
  it('round-trips through the parser unchanged', () => {
    // Arrange
    const document = parseFixture(validFixture());

    // Act
    const reparsed = parseFixtureText(formatFixture(document), 'round-trip');

    // Assert
    assert.deepEqual(reparsed, document);
  });

  it('writes one event per line, the way the committed fixtures are written', () => {
    // Arrange
    const document = parseFixture(validFixture());

    // Act
    const lines = formatFixture(document).split('\n');

    // Assert
    assert.equal(lines.filter((line) => line.trim().startsWith('{"type"')).length, 4);
  });
});

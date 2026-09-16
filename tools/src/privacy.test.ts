import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { assertNoCoordinate } from './privacy';

describe('assertNoCoordinate', () => {
  it('rejects coordinate key names in every spelling a recorder might use', () => {
    // Arrange
    const spellings = ['latitude', 'lat', 'lng', 'longitude', 'gpsLat', 'gps_longitude', 'coords'];

    for (const key of spellings) {
      // Act / Assert
      assert.throws(
        () => { assertNoCoordinate(`{"${key}":37.5}`, 'doc'); },
        /banned/,
        `"${key}" must be rejected`,
      );
    }
  });

  it('does not trip on contract vocabulary that merely contains those letters', () => {
    // Arrange — "location" and "correlationId" both contain "lat" as a substring.
    const benign = '{"type":"location","t":30,"accuracy":8,"correlationId":"x","translation":1}';

    // Act / Assert
    assert.doesNotThrow(() => { assertNoCoordinate(benign, 'doc'); });
  });

  it('rejects a coordinate pair hidden in free text', () => {
    // Arrange — the shape a pasted note takes.
    const note = '{"note":"parked at 37.123456, 127.987654"}';

    // Act / Assert
    assert.throws(() => { assertNoCoordinate(note, 'doc'); }, /coordinate pair/);
  });

  it('leaves ordinary fixture numbers alone', () => {
    // Arrange
    const fixture = '{"events":[{"type":"location","t":150,"accuracy":11,"speed":6.2}]}';

    // Act / Assert
    assert.doesNotThrow(() => { assertNoCoordinate(fixture, 'doc'); });
  });
});

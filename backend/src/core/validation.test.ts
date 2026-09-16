import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { z } from 'zod';
import { ApiError } from './errors';
import { parsePayload } from './validation';

// Mirrors the shape of docs/15_API_CONTRACTS_AND_EVENTS.md §2 ensureAccount.
const samplePayloadSchema = z.object({
  clientAccountId: z.uuid(),
  platform: z.enum(['ios', 'android']),
});

describe('parsePayload', () => {
  it('returns the typed value for a valid payload', () => {
    // Arrange
    const payload = {
      clientAccountId: '3f2504e0-4f89-41d3-9a0c-0305e82c3301',
      platform: 'ios',
    };

    // Act
    const parsed = parsePayload(samplePayloadSchema, payload);

    // Assert
    assert.equal(parsed.clientAccountId, '3f2504e0-4f89-41d3-9a0c-0305e82c3301');
    assert.equal(parsed.platform, 'ios');
  });

  it('strips unknown keys instead of forwarding them downstream', () => {
    // Arrange
    const payload = {
      clientAccountId: '3f2504e0-4f89-41d3-9a0c-0305e82c3301',
      platform: 'android',
      latitude: 37.5665,
    };

    // Act
    const parsed = parsePayload(samplePayloadSchema, payload);

    // Assert — coordinates must never survive into a backend code path (docs/09).
    assert.deepEqual(Object.keys(parsed).sort(), ['clientAccountId', 'platform']);
  });

  it('throws INVALID_ARGUMENT naming the failing field', () => {
    // Arrange
    const payload = { clientAccountId: 'not-a-uuid', platform: 'ios' };

    // Act & Assert
    assert.throws(
      () => parsePayload(samplePayloadSchema, payload),
      (error: unknown) =>
        error instanceof ApiError &&
        error.code === 'INVALID_ARGUMENT' &&
        error.debugMessage.includes('clientAccountId'),
    );
  });


  for (const edgeCase of [undefined, null, 'string', 42, []]) {
    it(`rejects a non-object payload: ${String(edgeCase)}`, () => {
      // Act & Assert
      assert.throws(
        () => parsePayload(samplePayloadSchema, edgeCase),
        (error: unknown) => error instanceof ApiError && error.code === 'INVALID_ARGUMENT',
      );
    });
  }

  it('reports every failing field, not just the first', () => {
    // Arrange
    const payload = { clientAccountId: 'nope', platform: 'web' };

    // Act
    let thrown: ApiError | undefined;
    try {
      parsePayload(samplePayloadSchema, payload);
    } catch (error) {
      thrown = error as ApiError;
    }

    // Assert
    assert.ok(thrown);
    assert.match(thrown.debugMessage, /clientAccountId/);
    assert.match(thrown.debugMessage, /platform/);
  });
});

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { API_ERROR_CODES, ApiError, toClientFacingError, toHttpsError } from './errors';

describe('toHttpsError', () => {
  it('maps every API error code to a distinct HttpsError code', () => {
    // Arrange & Act
    const mapped = API_ERROR_CODES.map((code) => toHttpsError(new ApiError(code)).code);

    // Assert
    assert.equal(new Set(mapped).size, API_ERROR_CODES.length);
  });

  it('sends the stable code as the wire message, never the debug text', () => {
    // Arrange
    const error = new ApiError('RATE_LIMITED', 'accountId=abc exceeded 5 req/min');

    // Act
    const httpsError = toHttpsError(error);

    // Assert
    assert.equal(httpsError.code, 'resource-exhausted');
    assert.equal(httpsError.message, 'RATE_LIMITED');
  });
});

describe('toClientFacingError', () => {
  it('passes through a domain error', () => {
    // Act
    const httpsError = toClientFacingError(new ApiError('UNAUTHENTICATED'));

    // Assert
    assert.equal(httpsError.code, 'unauthenticated');
    assert.equal(httpsError.message, 'UNAUTHENTICATED');
  });

  for (const unexpected of [new Error('firestore: DEADLINE_EXCEEDED at /accounts/abc'), 'boom', null]) {
    it(`collapses an unexpected throw to INTERNAL: ${String(unexpected)}`, () => {
      // Act
      const httpsError = toClientFacingError(unexpected);

      // Assert — no SDK detail may leak to the client (docs/15 §13).
      assert.equal(httpsError.code, 'internal');
      assert.equal(httpsError.message, 'INTERNAL');
    });
  }
});

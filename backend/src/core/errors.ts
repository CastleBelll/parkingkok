import { HttpsError, type FunctionsErrorCode } from 'firebase-functions/v2/https';

/**
 * Stable, machine-readable error codes returned to clients.
 *
 * docs/15_API_CONTRACTS_AND_EVENTS.md §13: the client localises the code, and the
 * server's debug text is never shown to the end user. Business-specific codes
 * (INVALID_CODE, SELF_REFERRAL, ...) are added alongside their functions in M5+.
 */
export const API_ERROR_CODES = [
  'INVALID_ARGUMENT',
  'UNAUTHENTICATED',
  'PERMISSION_DENIED',
  'FAILED_PRECONDITION',
  'RATE_LIMITED',
  'INTERNAL',
] as const;

export type ApiErrorCode = (typeof API_ERROR_CODES)[number];

const HTTPS_ERROR_CODE_BY_API_CODE: Readonly<Record<ApiErrorCode, FunctionsErrorCode>> = {
  INVALID_ARGUMENT: 'invalid-argument',
  UNAUTHENTICATED: 'unauthenticated',
  PERMISSION_DENIED: 'permission-denied',
  FAILED_PRECONDITION: 'failed-precondition',
  RATE_LIMITED: 'resource-exhausted',
  INTERNAL: 'internal',
};

/**
 * Domain-level failure raised inside function bodies.
 *
 * `debugMessage` is for Cloud Logging only and must never carry user-identifying
 * data (docs/09) — the wire payload only ever exposes {@link ApiError.code}.
 */
export class ApiError extends Error {
  readonly code: ApiErrorCode;
  readonly debugMessage: string;

  constructor(code: ApiErrorCode, debugMessage = '') {
    super(code);
    this.name = 'ApiError';
    this.code = code;
    this.debugMessage = debugMessage;
  }
}

/** Converts a domain failure into the wire error the Functions SDK sends. */
export function toHttpsError(error: ApiError): HttpsError {
  return new HttpsError(HTTPS_ERROR_CODE_BY_API_CODE[error.code], error.code);
}

/**
 * Last-resort mapper for unexpected throws. Unknown causes collapse to INTERNAL so
 * that a stack trace or SDK message can never reach the client.
 */
export function toClientFacingError(error: unknown): HttpsError {
  if (error instanceof ApiError) {
    return toHttpsError(error);
  }
  return new HttpsError('internal', 'INTERNAL');
}

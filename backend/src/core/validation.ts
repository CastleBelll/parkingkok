import type { ZodType } from 'zod';
import { ApiError } from './errors';

/**
 * Validates an untrusted callable/webhook payload against a zod schema.
 *
 * docs/16_CODING_STANDARDS.md §9 mandates payload schema validation and forbids
 * `any` on business paths: the schema is the single source of both the runtime
 * check and the static type, so callers receive a fully typed value.
 *
 * @throws {ApiError} INVALID_ARGUMENT with the failing field paths as debug text.
 */
export function parsePayload<T>(schema: ZodType<T>, payload: unknown): T {
  const result = schema.safeParse(payload);
  if (result.success) {
    return result.data;
  }
  throw new ApiError('INVALID_ARGUMENT', summariseIssues(result.error.issues));
}

/**
 * Builds a compact `field: reason` summary for Cloud Logging.
 * Only schema paths and zod's own messages are included — never the rejected value,
 * which may contain user data (docs/09_SECURITY_PRIVACY_COMPLIANCE.md).
 */
function summariseIssues(issues: readonly { path: PropertyKey[]; message: string }[]): string {
  return issues
    .map((issue) => {
      const path = issue.path.map((segment) => String(segment)).join('.');
      return `${path === '' ? '<root>' : path}: ${issue.message}`;
    })
    .join('; ');
}

/**
 * Minimal structural validation helpers shared by the trace (§9) and fixture (§8) parsers.
 *
 * Hand-rolled rather than pulled from a schema library on purpose: `tools/` must run on a
 * bare `node` with no runtime dependency install, because the people converting field
 * traces are testers, not backend developers. The helpers stay small enough to read in one
 * sitting, and `allowOnlyKeys` is the single chokepoint that makes an unexpected key — a
 * latitude, say — a parse failure instead of a silently copied field.
 */

/** A schema violation, always carrying the JSON path that failed. */
export class ValidationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ValidationError';
  }
}

export function fail(path: string, message: string): never {
  throw new ValidationError(`${path}: ${message}`);
}

function describe(value: unknown): string {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  return typeof value;
}

export function asObject(value: unknown, path: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    fail(path, `expected an object, got ${describe(value)}`);
  }
  return value as Record<string, unknown>;
}

export function asArray(value: unknown, path: string): unknown[] {
  if (!Array.isArray(value)) fail(path, `expected an array, got ${describe(value)}`);
  return value;
}

/**
 * Rejects any key the schema does not name.
 *
 * This is the structural half of the coordinate ban (contract §9): a trace or fixture
 * carrying `latitude` fails here before any field is read, so no unknown field can be
 * copied through the converter by accident.
 */
export function allowOnlyKeys(
  object: Record<string, unknown>,
  path: string,
  allowed: readonly string[],
): void {
  const unknown = Object.keys(object).filter((key) => !allowed.includes(key));
  if (unknown.length > 0) {
    fail(
      path,
      `unknown key(s) ${unknown.map((key) => `"${key}"`).join(', ')}; allowed: ${allowed.join(', ')}`,
    );
  }
}

export function requireString(
  object: Record<string, unknown>,
  path: string,
  key: string,
): string {
  const value = object[key];
  if (typeof value !== 'string' || value.length === 0) {
    fail(`${path}.${key}`, `expected a non-empty string, got ${describe(value)}`);
  }
  return value;
}

export function optionalString(
  object: Record<string, unknown>,
  path: string,
  key: string,
): string | undefined {
  if (!(key in object) || object[key] === undefined) return undefined;
  return requireString(object, path, key);
}

export interface NumberBounds {
  readonly min?: number;
  readonly integer?: boolean;
}

export function requireNumber(
  object: Record<string, unknown>,
  path: string,
  key: string,
  bounds: NumberBounds = {},
): number {
  const where = `${path}.${key}`;
  const value = object[key];
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    fail(where, `expected a finite number, got ${describe(value)}`);
  }
  if (bounds.integer === true && !Number.isInteger(value)) {
    fail(where, `expected an integer, got ${String(value)}`);
  }
  if (bounds.min !== undefined && value < bounds.min) {
    fail(where, `expected >= ${String(bounds.min)}, got ${String(value)}`);
  }
  return value;
}

export function optionalNumber(
  object: Record<string, unknown>,
  path: string,
  key: string,
  bounds: NumberBounds = {},
): number | undefined {
  if (!(key in object) || object[key] === undefined) return undefined;
  return requireNumber(object, path, key, bounds);
}

export function requireBoolean(
  object: Record<string, unknown>,
  path: string,
  key: string,
): boolean {
  const value = object[key];
  if (typeof value !== 'boolean') {
    fail(`${path}.${key}`, `expected a boolean, got ${describe(value)}`);
  }
  return value;
}

/** A tri-state field: `true` / `false` / `null` meaning "the labeller did not say". */
export function requireNullableBoolean(
  object: Record<string, unknown>,
  path: string,
  key: string,
): boolean | null {
  if (object[key] === null) return null;
  return requireBoolean(object, path, key);
}

export function optionalBoolean(
  object: Record<string, unknown>,
  path: string,
  key: string,
): boolean | undefined {
  if (!(key in object) || object[key] === undefined) return undefined;
  return requireBoolean(object, path, key);
}

export function requireEnum<T extends string>(
  object: Record<string, unknown>,
  path: string,
  key: string,
  allowed: readonly T[],
): T {
  const value = object[key];
  if (typeof value !== 'string' || !(allowed as readonly string[]).includes(value)) {
    fail(
      `${path}.${key}`,
      `expected one of ${allowed.join(' | ')}, got ${typeof value === 'string' ? `"${value}"` : describe(value)}`,
    );
  }
  return value as T;
}

export function optionalEnum<T extends string>(
  object: Record<string, unknown>,
  path: string,
  key: string,
  allowed: readonly T[],
): T | undefined {
  if (!(key in object) || object[key] === undefined) return undefined;
  return requireEnum(object, path, key, allowed);
}

export function optionalStringArray(
  object: Record<string, unknown>,
  path: string,
  key: string,
): string[] | undefined {
  if (!(key in object) || object[key] === undefined) return undefined;
  const raw = asArray(object[key], `${path}.${key}`);
  return raw.map((entry, index) => {
    if (typeof entry !== 'string' || entry.length === 0) {
      fail(`${path}.${key}[${String(index)}]`, `expected a non-empty string, got ${describe(entry)}`);
    }
    return entry;
  });
}

/**
 * The coordinate ban, checked against text rather than against a field list.
 *
 * Traces and fixtures are copied off the device by design (contract §9), so a latitude
 * reaching one turns it into a record of where someone parks — docs/00_CORE_RULES.md
 * Privacy. Same reasoning and same technique as the existing diagnostics export tests
 * (`ios/ParkingPinTests/DiagnosticsReportTests.swift`,
 * `android/.../DiagnosticsReportTest.kt`): inspect the encoded bytes, because a field list
 * only tells you about the fields you remembered to look at.
 *
 * `allowOnlyKeys` in schema.ts is the structural half of the same rule. This module is the
 * textual half, and it runs on both the input text and the encoded output.
 */

import { ValidationError } from './schema';

/**
 * Key-name tokens that mean "a place on Earth".
 *
 * Matched per token, not as substrings, so `location` and `correlationId` do not trip on
 * the `lat` inside them while `lat`, `latitude` and `gpsLat` all do.
 */
const COORDINATE_TOKENS: readonly string[] = [
  'lat',
  'latitude',
  'lon',
  'lng',
  'long',
  'longitude',
  'coord',
  'coords',
  'coordinate',
  'coordinates',
  'geo',
  'geohash',
  'altitude',
  'alt',
  'bearing',
  'heading',
  'gps',
  'position',
  'waypoint',
  'address',
];

/** A decimal pair with real precision, e.g. `37.123456, 127.987654` hidden in free text. */
const COORDINATE_PAIR = /-?\d{1,3}\.\d{4,}\s*,\s*-?\d{1,3}\.\d{4,}/;

const JSON_KEY = /"((?:[^"\\]|\\.)*)"\s*:/g;

/** Splits `gpsLatitude`, `gps_latitude` and `GPS-Latitude` alike into lowercase tokens. */
function tokenize(key: string): string[] {
  return key
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .split(/[^A-Za-z0-9]+/)
    .filter((token) => token.length > 0)
    .map((token) => token.toLowerCase());
}

function coordinateKeysIn(text: string): string[] {
  const found = new Set<string>();
  for (const match of text.matchAll(JSON_KEY)) {
    const key = match[1];
    if (key === undefined) continue;
    if (tokenize(key).some((token) => COORDINATE_TOKENS.includes(token))) found.add(key);
  }
  return [...found];
}

/**
 * Throws if `text` looks like it carries a coordinate, by key name or by value shape.
 *
 * @param text JSON text — either a file read from disk or an encoded output document.
 * @param label What to call the document in the error message.
 */
export function assertNoCoordinate(text: string, label: string): void {
  const keys = coordinateKeysIn(text);
  if (keys.length > 0) {
    throw new ValidationError(
      `${label}: coordinate field(s) ${keys.map((key) => `"${key}"`).join(', ')} are banned ` +
        '(docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9). Traces and fixtures leave the device; ' +
        'a coordinate in one makes it a record of where the user parks.',
    );
  }

  const pair = COORDINATE_PAIR.exec(text);
  if (pair !== null) {
    throw new ValidationError(
      `${label}: text contains what looks like a coordinate pair ("${pair[0]}"), which is banned ` +
        '(docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9). Free-text notes are the usual culprit.',
    );
  }
}

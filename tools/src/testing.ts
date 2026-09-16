/**
 * Synthetic traces for the tool's own tests.
 *
 * No real field recording exists yet — the recorders are being built in parallel — so the
 * converter is proved against hand-written traces that follow contract §9 exactly.
 */

import type { Trace, TraceEvent, TraceLabel } from './trace';

/** An arbitrary fixed epoch. Nothing here reads a clock. */
export const BASE_MILLIS = 1_789_530_905_483;

export function at(seconds: number): number {
  return BASE_MILLIS + seconds * 1000;
}

export function trace(overrides: Partial<Trace> = {}): Trace {
  const events: TraceEvent[] = [
    { type: 'vehicle_enter', atMillis: at(0), confidence: 'high' },
    { type: 'location', atMillis: at(30), accuracy: 8, speed: 9.2, distanceFromPreviousM: 41 },
    { type: 'vehicle_exit', atMillis: at(240), confidence: 'medium' },
    { type: 'walking_enter', atMillis: at(270), confidence: 'high' },
  ];
  const label: TraceLabel = { mode: 'car', parked: true, note: '지하 3층, 진입 후 GPS 소실' };

  return {
    schemaVersion: 1,
    sessionId: 'a1b2c3d4-0000-4000-8000-000000000001',
    platform: 'ios',
    deviceModel: 'iPhone15,3',
    osVersion: '26.6',
    appVersion: '0.1.0 (12)',
    startedAt: at(0),
    endedAt: at(275),
    label,
    events,
    ...overrides,
  };
}

/** The same trace as a JSON document, so parser tests can mutate it freely. */
export function traceDocument(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return { ...JSON.parse(JSON.stringify(trace())) as Record<string, unknown>, ...overrides };
}

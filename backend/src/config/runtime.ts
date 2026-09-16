import type { GlobalOptions } from 'firebase-functions/v2/options';

/**
 * Primary region for every function in this project.
 *
 * docs/07_FIREBASE_BACKEND.md §3 requires "one intentional primary region".
 * 주차콕 is a Korea-first product, so Seoul (asia-northeast3) minimises RTT for
 * the callable endpoints the client hits during purchase/referral flows.
 */
export const PRIMARY_REGION = 'asia-northeast3';

/**
 * Cost guard rail. docs/07_FIREBASE_BACKEND.md §15 caps backend usage to bounded
 * callable traffic — there is no parking telemetry streaming — so a low instance
 * ceiling is the intended steady state, not a temporary throttle.
 */
const MAX_INSTANCES = 10;

/** Shared by every function; applied once in `src/index.ts`. */
export const GLOBAL_RUNTIME_OPTIONS: GlobalOptions = {
  region: PRIMARY_REGION,
  maxInstances: MAX_INSTANCES,
};

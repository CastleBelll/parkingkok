import { setGlobalOptions } from 'firebase-functions';
import { GLOBAL_RUNTIME_OPTIONS } from './config/runtime';

// Must run before any function is defined, so it precedes the exports below.
setGlobalOptions(GLOBAL_RUNTIME_OPTIONS);

export { healthcheck } from './functions/healthcheck';

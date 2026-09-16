import { onRequest } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions';
import { PRIMARY_REGION } from '../config/runtime';

/**
 * Deployment smoke test. Reports that the runtime booted in the expected region —
 * nothing more. It touches no Firestore document and reads no request body, so it
 * stays safe to expose while the real callables land in M5+.
 */
export const healthcheck = onRequest((_request, response) => {
  logger.info('healthcheck', { region: PRIMARY_REGION });

  response.status(200).json({
    status: 'ok',
    region: PRIMARY_REGION,
    // K_REVISION is injected by Cloud Run; absent under the local emulator.
    revision: process.env['K_REVISION'] ?? 'local',
    checkedAt: new Date().toISOString(),
  });
});

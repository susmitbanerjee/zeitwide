import { ApiProblem } from "@/api/client";

/** Waits between attempts: about 27 seconds in all, enough for the API to start from zero. */
export const RETRY_DELAYS_MS: readonly number[] = [1_000, 3_000, 8_000, 15_000];

/**
 * Whether trying again could help: the API is busy (429), failed (5xx) or couldn't be reached
 * (fetch throws a TypeError while an instance is still starting). A refusal that is the API's
 * answer, such as an unknown calendar, is never retried.
 */
export function isTransient(error: unknown): boolean {
  if (error instanceof ApiProblem) {
    return error.status === 429 || error.status >= 500;
  }
  return error instanceof TypeError;
}

/**
 * Runs `call`, retrying transient failures. The API scales to zero, so the first calls after a
 * quiet spell, such as a build prerendering thousands of pages, can meet an instance that is
 * still starting; one such failure must not fail the build or a visitor's page.
 */
export async function withRetry<T>(
  call: () => Promise<T>,
  delays: readonly number[] = RETRY_DELAYS_MS,
  sleep: (ms: number) => Promise<void> = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    try {
      return await call();
    } catch (error) {
      const delay = delays[attempt];
      if (delay === undefined || !isTransient(error)) {
        throw error;
      }
      await sleep(delay);
    }
  }
}

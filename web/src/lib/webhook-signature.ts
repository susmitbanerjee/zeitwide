import { createHmac, timingSafeEqual } from "node:crypto";

/** Deliveries older (or newer) than this are refused, so a captured one can't be replayed later. */
export const SIGNATURE_TOLERANCE_SECONDS = 300;

/**
 * Checks a `Zeitwide-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.body">` header, as the
 * API's WebhookSignature writes it, in constant time.
 */
export function verifyWebhookSignature(
  header: string | null,
  body: string,
  secret: string,
  nowSeconds: number,
): boolean {
  if (header === null || secret === "") {
    return false;
  }
  const parts = new Map(
    header.split(",").map((part) => {
      const separator = part.indexOf("=");
      return [part.slice(0, separator).trim(), part.slice(separator + 1).trim()] as const;
    }),
  );
  const timestamp = Number(parts.get("t"));
  const signature = parts.get("v1") ?? "";
  if (
    !Number.isInteger(timestamp) ||
    Math.abs(nowSeconds - timestamp) > SIGNATURE_TOLERANCE_SECONDS
  ) {
    return false;
  }
  if (!/^[0-9a-f]{64}$/.test(signature)) {
    return false;
  }
  const expected = createHmac("sha256", secret).update(`${timestamp}.${body}`).digest();
  return timingSafeEqual(expected, Buffer.from(signature, "hex"));
}

/** The calendar a change event is about, from the webhook envelope's data. */
export function changedCalendar(envelope: unknown): string | null {
  const data = (envelope as { data?: { calendar_id?: unknown } } | null)?.data;
  const id = data?.calendar_id;
  return typeof id === "string" && /^[A-Z0-9]+(-[A-Z0-9]+)*$/.test(id) ? id : null;
}

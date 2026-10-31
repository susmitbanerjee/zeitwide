import { revalidateTag } from "next/cache";

import { CALENDARS_TAG, calendarTag } from "@/lib/api-server";
import { changedCalendar, verifyWebhookSignature } from "@/lib/webhook-signature";

const MAX_BODY_BYTES = 64 * 1024;

/**
 * Zeitwide's own webhook: the API calls it when a change is published, and the calendar's answer
 * pages are refreshed on their next visit. Subscribe this URL to every change type with the
 * account webhooks API, and set its signing secret as ZEITWIDE_WEBHOOK_SECRET.
 */
export async function POST(request: Request): Promise<Response> {
  const body = await request.text();
  if (body.length > MAX_BODY_BYTES) {
    return new Response(null, { status: 413 });
  }
  const signed = verifyWebhookSignature(
    request.headers.get("zeitwide-signature"),
    body,
    process.env.ZEITWIDE_WEBHOOK_SECRET ?? "",
    Math.floor(Date.now() / 1000),
  );
  if (!signed) {
    return new Response(null, { status: 401 });
  }
  let envelope: unknown;
  try {
    envelope = JSON.parse(body);
  } catch {
    return new Response(null, { status: 400 });
  }
  const calendarId = changedCalendar(envelope);
  revalidateTag(CALENDARS_TAG, "max");
  if (calendarId !== null) {
    revalidateTag(calendarTag(calendarId), "max");
  }
  return new Response(null, { status: 204 });
}

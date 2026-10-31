import { createHmac } from "node:crypto";

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const revalidateTag = vi.fn();
vi.mock("next/cache", () => ({ revalidateTag, cacheLife: vi.fn(), cacheTag: vi.fn() }));

const { POST } = await import("./route");

const SECRET = "whsec_unit";

function delivery(body: string, secret = SECRET) {
  const t = Math.floor(Date.now() / 1000);
  const signature = createHmac("sha256", secret).update(`${t}.${body}`).digest("hex");
  return new Request("http://localhost/api/revalidate", {
    method: "POST",
    headers: { "zeitwide-signature": `t=${t},v1=${signature}` },
    body,
  });
}

describe("POST /api/revalidate", () => {
  beforeEach(() => {
    vi.stubEnv("ZEITWIDE_WEBHOOK_SECRET", SECRET);
    revalidateTag.mockClear();
  });
  afterEach(() => vi.unstubAllEnvs());

  it("refreshes the changed calendar's pages", async () => {
    const response = await POST(delivery('{"data":{"calendar_id":"GB-CHAPS"}}'));
    expect(response.status).toBe(204);
    expect(revalidateTag).toHaveBeenCalledWith("calendars", "max");
    expect(revalidateTag).toHaveBeenCalledWith("calendar:GB-CHAPS", "max");
  });

  it("refuses unsigned or wrongly signed deliveries", async () => {
    expect((await POST(delivery("{}", "other"))).status).toBe(401);
    expect(
      (await POST(new Request("http://localhost", { method: "POST", body: "{}" }))).status,
    ).toBe(401);
    expect(revalidateTag).not.toHaveBeenCalled();
  });

  it("refuses bodies that aren't JSON or are too large", async () => {
    expect((await POST(delivery("not json"))).status).toBe(400);
    expect((await POST(delivery("x".repeat(70_000)))).status).toBe(413);
  });
});

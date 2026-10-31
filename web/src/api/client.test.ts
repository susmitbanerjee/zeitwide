import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiProblem, createZeitwideClient, unwrap, type DayAnswer } from "./client";

const ANSWER: DayAnswer = {
  date: "2026-11-26",
  is_business_day: false,
  combine: "all_open",
  calendars: [],
  as_of: "2026-10-29T00:00:00Z",
  dataset_release: "2026.10.29.0",
  calendar_revisions: { "US-FEDWIRE": 1 },
  receipt: {
    id: "rcpt_0123456789abcdef0123456789abcdef",
    payload: {
      request: {},
      answer: {},
      dataset_release: "2026.10.29.0",
      calendar_revisions: { "US-FEDWIRE": 1 },
      issued_at: "2026-10-29T00:00:00Z",
    },
    payload_hash: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    key_id: "test-key",
    signature:
      "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  },
};

function recordingFetch(status: number, body: unknown) {
  const requests: Request[] = [];
  const fetch = (input: Request | string | URL) => {
    requests.push(input instanceof Request ? input : new Request(input));
    return Promise.resolve(Response.json(body, { status }));
  };
  return { fetch: fetch as typeof globalThis.fetch, requests };
}

describe("createZeitwideClient", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("uses the global fetch by default", async () => {
    const { fetch, requests } = recordingFetch(200, ANSWER);
    vi.stubGlobal("fetch", fetch);
    const client = createZeitwideClient({ baseUrl: "https://api.test" });

    await client.GET("/v1/calendars");

    expect(requests[0]?.url).toBe("https://api.test/v1/calendars");
  });

  it("asks for a day on the documented path", async () => {
    const { fetch, requests } = recordingFetch(200, ANSWER);
    const client = createZeitwideClient({ baseUrl: "https://api.test", fetch });

    const { data } = await client.GET("/v1/calendars/{id}/days/{date}", {
      params: { path: { id: "US-FEDWIRE", date: "2026-11-26" } },
    });

    expect(data?.is_business_day).toBe(false);
    expect(requests[0]?.url).toBe("https://api.test/v1/calendars/US-FEDWIRE/days/2026-11-26");
    expect(requests[0]?.headers.get("Authorization")).toBeNull();
  });

  it("sends an API key as a bearer token", async () => {
    const { fetch, requests } = recordingFetch(200, ANSWER);
    const client = createZeitwideClient({
      baseUrl: "https://api.test",
      apiKey: "zw_test_x",
      fetch,
    });

    await client.POST("/v1/business-days/check", {
      body: { calendars: ["US-FEDWIRE", "EU-T2"], date: "2026-04-03", combine: "any_open" },
    });

    expect(requests[0]?.headers.get("Authorization")).toBe("Bearer zw_test_x");
    expect(await requests[0]?.json()).toEqual({
      calendars: ["US-FEDWIRE", "EU-T2"],
      date: "2026-04-03",
      combine: "any_open",
    });
  });

  it("returns problem details as the error", async () => {
    const problem = { title: "Out of range", status: 422, code: "out_of_range" };
    const { fetch } = recordingFetch(422, problem);
    const client = createZeitwideClient({ baseUrl: "https://api.test", fetch });

    const { data, error } = await client.POST("/v1/business-days/add", {
      body: { calendars: ["US-FEDWIRE"], date: "2099-01-02", days: 1 },
    });

    expect(data).toBeUndefined();
    expect(error?.code).toBe("out_of_range");
  });
});

describe("unwrap", () => {
  it("returns data or raises the API's problem", () => {
    const ok = new Response(null, { status: 200 });
    expect(unwrap({ data: 1, response: ok })).toBe(1);
    const bad = new Response(null, { status: 422 });
    const problem = {
      title: "Out of range",
      status: 422,
      code: "out_of_range",
      detail: "Too late",
    };
    expect(() => unwrap({ error: problem, response: bad })).toThrow("Too late");
    try {
      unwrap({ error: problem, response: bad });
    } catch (error) {
      expect(error).toBeInstanceOf(ApiProblem);
      expect((error as ApiProblem).code).toBe("out_of_range");
    }
  });

  it("names a non-problem failure by its status", () => {
    const error = ApiProblem.from(new Response(null, { status: 502 }), "<html>");
    expect(error.message).toBe("HTTP 502");
    expect(error.code).toBe("internal_error");
    expect(
      ApiProblem.from(new Response(null, { status: 400 }), { code: "x", title: "T" }).message,
    ).toBe("T");
  });
});

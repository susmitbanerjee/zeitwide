import { describe, expect, it, vi } from "vitest";

import { ApiProblem } from "@/api/client";

import { isTransient, withRetry } from "./retry";

const noWait = () => Promise.resolve();

describe("isTransient", () => {
  it("retries a busy or failing API and a connection that couldn't be made", () => {
    expect(isTransient(new ApiProblem(429, "rate_limited", "slow down"))).toBe(true);
    expect(isTransient(new ApiProblem(503, "internal_error", "starting"))).toBe(true);
    expect(isTransient(new TypeError("fetch failed"))).toBe(true);
  });

  it("never retries an answer the API meant", () => {
    expect(isTransient(new ApiProblem(400, "validation_failed", "bad date"))).toBe(false);
    expect(isTransient(new ApiProblem(401, "unauthorized", "no key"))).toBe(false);
    expect(isTransient(new Error("something else"))).toBe(false);
  });
});

describe("withRetry", () => {
  it("returns the first success", async () => {
    const call = vi.fn().mockResolvedValue("ok");
    await expect(withRetry(call, [1, 2], noWait)).resolves.toBe("ok");
    expect(call).toHaveBeenCalledTimes(1);
  });

  it("tries again after a transient failure and returns the later success", async () => {
    const call = vi
      .fn()
      .mockRejectedValueOnce(new TypeError("fetch failed"))
      .mockRejectedValueOnce(new ApiProblem(503, "internal_error", "starting"))
      .mockResolvedValue("ok");
    const sleep = vi.fn(noWait);
    await expect(withRetry(call, [10, 20, 30], sleep)).resolves.toBe("ok");
    expect(sleep.mock.calls).toEqual([[10], [20]]);
  });

  it("gives up after the last delay and throws the last error", async () => {
    const failure = new ApiProblem(503, "internal_error", "still down");
    const call = vi.fn().mockRejectedValue(failure);
    await expect(withRetry(call, [1, 2], noWait)).rejects.toBe(failure);
    expect(call).toHaveBeenCalledTimes(3);
  });

  it("throws a refusal at once", async () => {
    const refusal = new ApiProblem(401, "unauthorized", "no key");
    const call = vi.fn().mockRejectedValue(refusal);
    await expect(withRetry(call, [1, 2], noWait)).rejects.toBe(refusal);
    expect(call).toHaveBeenCalledTimes(1);
  });
});

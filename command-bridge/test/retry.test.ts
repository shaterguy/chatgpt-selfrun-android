import { describe, expect, it } from "vitest";
import { retryDelaySeconds } from "../src/retry.js";

describe("retryDelaySeconds", () => {
  it("uses the bounded ACK resend schedule and caps at ten minutes", () => {
    expect([0, 1, 2, 3, 4, 5, 6, 20].map(retryDelaySeconds))
      .toEqual([15, 30, 60, 120, 300, 600, 600, 600]);
  });
});

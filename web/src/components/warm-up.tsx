"use client";

import { useEffect } from "react";

/**
 * Tells the server a visitor has arrived, so the API starts waking before they sign in and the
 * app first asks it something. Once per browser session; failure is silent because nothing depends on it.
 */
export function WarmUp() {
  useEffect(() => {
    try {
      if (sessionStorage.getItem("zw-warm") !== null) {
        return;
      }
      sessionStorage.setItem("zw-warm", "1");
    } catch {
      // Storage blocked: ask anyway, the server throttles.
    }
    fetch("/api/warm", { method: "POST", keepalive: true }).catch(() => undefined);
  }, []);
  return null;
}

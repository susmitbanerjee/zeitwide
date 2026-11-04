"use client";

import { useEffect } from "react";

import { FARO_SESSION_SAMPLE_RATE, withoutQuery } from "@/lib/telemetry/faro-options";

interface FaroProps {
  /** The Grafana Faro collector URL (it is public by design: browsers post to it). */
  url: string;
  environment: string;
}

/** Browser errors and Web Vitals to Grafana Faro. Loaded after the page, so it never blocks it. */
export function Faro({ url, environment }: FaroProps) {
  useEffect(() => {
    void import("@grafana/faro-web-sdk").then(
      ({ faro, getWebInstrumentations, initializeFaro }) => {
        if (faro.api !== undefined) {
          return;
        }
        initializeFaro({
          url,
          app: { name: "zeitwide-web", environment },
          sessionTracking: { enabled: true, samplingRate: FARO_SESSION_SAMPLE_RATE },
          instrumentations: getWebInstrumentations({ captureConsole: false }),
          beforeSend: (item) => {
            const page = item.meta.page;
            if (page?.url !== undefined) {
              page.url = withoutQuery(page.url);
            }
            return item;
          },
        });
      },
    );
  }, [url, environment]);
  return null;
}

"use client";

import { useQuery } from "@tanstack/react-query";

import { unwrap, type SourceHealth } from "@/api/client";
import { PageHeading } from "@/components/app-chrome";
import { Card, ErrorText, Status, Table, Td, Th } from "@/components/ui/primitives";
import { browserApi } from "@/lib/api-browser";
import { cn } from "@/lib/cn";
import { formatInstant, shortHash } from "@/lib/format";
import { errorMessage } from "@/lib/queries";

const HEALTH: Record<SourceHealth["health"], { label: string; className: string }> = {
  healthy: { label: "Healthy", className: "bg-success-soft text-success" },
  failing: { label: "Failing", className: "bg-closed-soft text-closed-ink" },
  suspended: { label: "Suspended", className: "bg-closed-soft text-closed-ink" },
  disabled: { label: "Disabled", className: "bg-surface-sunken text-ink-muted" },
  never_checked: { label: "Never checked", className: "bg-warning-soft text-warning" },
};

/** Freshness and failures per source. A broken source must never look like "no change". */
export function Sources() {
  const sources = useQuery({
    queryKey: ["sources"],
    queryFn: async () => unwrap(await browserApi.GET("/v1/admin/sources")),
  });
  const failing =
    sources.data?.sources.filter((s) => s.health !== "healthy" && s.health !== "disabled") ?? [];
  return (
    <div className="flex flex-col gap-6">
      <PageHeading
        title="Source health"
        lead="Every source the pipeline reads, and when it last read it."
      />
      {sources.isPending && <Status>Loading sources…</Status>}
      {sources.isError && <ErrorText>{errorMessage(sources.error)}</ErrorText>}
      {failing.length > 0 && (
        <p
          role="status"
          className="rounded-md bg-closed-soft px-4 py-3 font-semibold text-closed-ink"
        >
          {failing.length === 1 ? "1 source needs" : `${failing.length} sources need`} attention.
        </p>
      )}
      {sources.data !== undefined && (
        <Card>
          <Table>
            <caption className="sr-only">Sources</caption>
            <thead>
              <tr>
                <Th scope="col">Source</Th>
                <Th scope="col">Health</Th>
                <Th scope="col">Last checked</Th>
                <Th scope="col">Last success</Th>
                <Th scope="col">Latest snapshot</Th>
                <Th scope="col">Failures</Th>
              </tr>
            </thead>
            <tbody>
              {sources.data.sources.map((source) => (
                <tr key={source.source}>
                  <Td>
                    <span className="block font-semibold">{source.publisher}</span>
                    <span className="block font-mono text-xs text-ink-subtle">
                      {source.source} · {source.role} · {source.schedule}
                    </span>
                  </Td>
                  <Td>
                    <span
                      className={cn(
                        "rounded px-2 py-0.5 text-[13px] font-semibold",
                        HEALTH[source.health].className,
                      )}
                    >
                      {HEALTH[source.health].label}
                    </span>
                    {source.last_error !== null && (
                      <span className="mt-1 block text-xs text-ink-muted">{source.last_error}</span>
                    )}
                  </Td>
                  <Td>{formatInstant(source.last_checked_at)}</Td>
                  <Td>{formatInstant(source.last_success_at)}</Td>
                  <Td>
                    {formatInstant(source.last_snapshot_at)}
                    {source.last_snapshot_sha256 !== null && (
                      <span className="block font-mono text-xs text-ink-subtle">
                        {shortHash(source.last_snapshot_sha256)}
                      </span>
                    )}
                  </Td>
                  <Td>{source.consecutive_failures}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        </Card>
      )}
    </div>
  );
}

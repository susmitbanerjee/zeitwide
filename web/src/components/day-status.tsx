import type { DayKind } from "@/lib/answers";
import { cn } from "@/lib/cn";

const LABELS: Record<DayKind, string> = {
  open: "Open",
  closed: "Closed",
  weekend: "Weekend",
  "weekend-holiday": "Holiday on a weekend",
};

/** A status chip: text plus a swatch, so the state never depends on colour alone. */
export function DayStatus({ kind, label }: { kind: DayKind; label?: string }) {
  const closed = kind === "closed";
  return (
    <span
      className={cn(
        "inline-flex items-center gap-2 rounded px-2 py-0.5 text-[13px] leading-5 font-semibold",
        closed ? "bg-closed-soft text-closed" : "border border-line-strong bg-surface text-ink",
      )}
    >
      <DaySwatch kind={kind} className="size-2.5" />
      {label ?? LABELS[kind]}
    </span>
  );
}

export function DaySwatch({ kind, className }: { kind: DayKind; className?: string }) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        "inline-block shrink-0 rounded-[2px]",
        kind === "closed" && "bg-closed",
        kind === "open" && "border-[1.5px] border-ink bg-surface",
        kind === "weekend" && "hatch border border-line",
        kind === "weekend-holiday" && "hatch border-2 border-closed",
        className,
      )}
    />
  );
}

export function DayLegend() {
  return (
    <ul className="flex flex-wrap gap-x-5 gap-y-2 text-[13px] text-ink-muted">
      {(Object.keys(LABELS) as DayKind[]).map((kind) => (
        <li key={kind} className="inline-flex items-center gap-2">
          <DaySwatch kind={kind} className="size-3.5" />
          {LABELS[kind]}
        </li>
      ))}
    </ul>
  );
}

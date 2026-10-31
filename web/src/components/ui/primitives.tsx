import type { ComponentProps } from "react";

import { cn } from "@/lib/cn";

/** Small shadcn-style primitives, styled with the Zeitwide tokens. */

export function Card({ className, ...props }: ComponentProps<"div">) {
  return <div className={cn("rounded-lg border border-line bg-surface", className)} {...props} />;
}

export function SectionTitle({ className, children, ...props }: ComponentProps<"h2">) {
  return (
    <h2 className={cn("text-xl leading-7 font-semibold", className)} {...props}>
      {children}
    </h2>
  );
}

export function Eyebrow({ className, ...props }: ComponentProps<"p">) {
  return (
    <p
      className={cn("text-xs font-semibold tracking-[0.08em] text-ink-subtle uppercase", className)}
      {...props}
    />
  );
}

/** Callers always pass htmlFor. */
export function Label({ className, htmlFor, ...props }: ComponentProps<"label">) {
  return (
    <label
      htmlFor={htmlFor}
      className={cn("text-sm font-semibold text-ink", className)}
      {...props}
    />
  );
}

const field =
  "min-h-11 w-full rounded-md border border-line-strong bg-surface px-3 text-[15px] text-ink placeholder:text-ink-subtle";

export function Input({ className, ...props }: ComponentProps<"input">) {
  return <input className={cn(field, className)} {...props} />;
}

export function Select({ className, ...props }: ComponentProps<"select">) {
  return <select className={cn(field, "pr-8", className)} {...props} />;
}

export function Textarea({ className, ...props }: ComponentProps<"textarea">) {
  return <textarea className={cn(field, "min-h-24 py-2", className)} {...props} />;
}

export function Mono({ className, ...props }: ComponentProps<"span">) {
  return <span className={cn("font-mono", className)} {...props} />;
}

export function Code({ className, ...props }: ComponentProps<"pre">) {
  return (
    <pre
      className={cn(
        "overflow-x-auto rounded-md bg-ink p-4 font-mono text-[13px] leading-5 text-[#e9ebee]",
        className,
      )}
      {...props}
    />
  );
}

export function Table({ className, ...props }: ComponentProps<"table">) {
  return (
    <div className="overflow-x-auto">
      <table className={cn("w-full border-collapse text-left text-sm", className)} {...props} />
    </div>
  );
}

export function Th({ className, ...props }: ComponentProps<"th">) {
  return (
    <th
      className={cn("border-b border-line px-3 py-2 font-semibold text-ink-muted", className)}
      {...props}
    />
  );
}

export function Td({ className, ...props }: ComponentProps<"td">) {
  return <td className={cn("border-b border-line px-3 py-3 align-top", className)} {...props} />;
}

/** A polite live region for results that appear after a request. */
export function Status({ className, ...props }: ComponentProps<"p">) {
  return <p role="status" className={cn("text-sm text-ink-muted", className)} {...props} />;
}

export function ErrorText({ className, ...props }: ComponentProps<"p">) {
  return (
    <p
      role="alert"
      className={cn("rounded-md bg-closed-soft px-3 py-2 text-sm text-closed", className)}
      {...props}
    />
  );
}

"use client";

import { useState } from "react";

import { Button, type ButtonProps } from "./ui/button";

/** Copies text to the clipboard and says so to screen readers too. */
export function CopyButton({
  text,
  label = "Copy",
  ...props
}: { text: string; label?: string } & Omit<ButtonProps, "onClick">) {
  const [state, setState] = useState<"idle" | "copied" | "failed">("idle");
  return (
    <>
      <Button
        type="button"
        variant="outline"
        {...props}
        onClick={() => {
          navigator.clipboard.writeText(text).then(
            () => setState("copied"),
            () => setState("failed"),
          );
        }}
      >
        {state === "copied" ? "Copied" : label}
      </Button>
      <span role="status" className="sr-only">
        {state === "copied" ? "Copied to the clipboard" : state === "failed" ? "Copy failed" : ""}
      </span>
    </>
  );
}

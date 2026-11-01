"use client";

import type { ReactNode } from "react";
import { useFormStatus } from "react-dom";

import { Button } from "./ui/button";

/**
 * A submit button that says something is happening: a button that does nothing for a moment
 * looks broken. While the form is sending, every button is disabled so a second click can't
 * start a second sign-in.
 */
export function ProviderButton({
  field = "provider",
  value,
  tip,
  children,
}: {
  /** The form field this button sets: the provider, or a fixed test user. */
  field?: "provider" | "testUser";
  value: string;
  tip: string;
  children: ReactNode;
}) {
  const { pending, data } = useFormStatus();
  const mine = pending && data?.get(field) === value;
  return (
    <Button
      type="submit"
      name={field}
      value={value}
      variant="outline"
      tip={tip}
      disabled={pending}
      aria-busy={mine}
    >
      {mine ? (
        <>
          <span
            aria-hidden="true"
            className="size-4 animate-spin rounded-full border-2 border-line-strong border-t-ink"
          />
          Connecting…
        </>
      ) : (
        children
      )}
    </Button>
  );
}

"use client";

import type { ReactNode } from "react";
import { useFormStatus } from "react-dom";

import { Button } from "./ui/button";

/**
 * A submit button that says something is happening. Signing in can take a few seconds when
 * Keycloak has to start, and a button that does nothing looks broken. While the form is sending,
 * every button is disabled so a second click can't start a second sign-in.
 */
export function ProviderButton({
  value,
  tip,
  children,
}: {
  value: string;
  tip: string;
  children: ReactNode;
}) {
  const { pending, data } = useFormStatus();
  const mine = pending && (data?.get("provider") ?? "") === value;
  return (
    <Button
      type="submit"
      name="provider"
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

"use server";

import { signIn } from "@/auth";
import { identityProviderHint } from "@/auth/identity-providers";

import { safeCallback } from "./callback";

/**
 * Starts the Keycloak sign-in for the provider the visitor picked. The landing page comes from
 * the form, not from a closure, and is checked again here: a form field is client input.
 */
export async function startSignIn(form: FormData): Promise<void> {
  await signIn(
    "keycloak",
    { redirectTo: safeCallback(form.get("callbackUrl"), "/app") },
    identityProviderHint(form.get("provider")),
  );
}

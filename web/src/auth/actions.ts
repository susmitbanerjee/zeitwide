"use server";

import { signIn } from "@/auth";
import { providerFrom, testLoginEnabled, testUserFrom } from "@/auth/identity-providers";

import { safeCallback } from "./callback";

/**
 * Starts the sign-in the visitor picked. The landing page comes from the form, not from a
 * closure, and is checked again here: a form field is client input, and so is the provider.
 */
export async function startSignIn(form: FormData): Promise<void> {
  const redirectTo = safeCallback(form.get("callbackUrl"), "/app");
  const testUser = testUserFrom(form.get("testUser"));
  if (testUser !== null && testLoginEnabled()) {
    await signIn("test-login", { user: testUser, redirectTo });
    return;
  }
  const provider = providerFrom(form.get("provider"));
  if (provider !== null) {
    await signIn(provider, { redirectTo });
  }
}

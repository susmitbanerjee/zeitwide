import "server-only";

import { SignJWT } from "jose";

import { env } from "@/lib/env";

export const API_AUDIENCE = "zeitwide-api";
/** One call's worth: the API refuses a token meant to last more than five minutes. */
const LIFETIME_SECONDS = 60;

export interface SignedInUser {
  subject: string;
  name: string | undefined;
  email: string | undefined;
  roles: string[];
}

/**
 * The token the web app sends the API for one call on a signed-in person's behalf. The API trusts
 * it because only the web app holds the secret it is signed with, so who the caller is, and
 * whether they are a reviewer, can't be changed from the browser.
 */
export async function signApiToken(
  user: SignedInUser,
  secret: string,
  issuer: string = env.appUrl,
  nowSeconds: number = Math.floor(Date.now() / 1000),
): Promise<string> {
  return new SignJWT({
    ...(user.name === undefined ? {} : { name: user.name }),
    ...(user.email === undefined ? {} : { email: user.email }),
    roles: user.roles,
  })
    .setProtectedHeader({ alg: "HS256", typ: "JWT" })
    .setIssuer(issuer)
    .setAudience(API_AUDIENCE)
    .setSubject(user.subject)
    .setIssuedAt(nowSeconds)
    .setExpirationTime(nowSeconds + LIFETIME_SECONDS)
    .sign(new TextEncoder().encode(secret));
}

import { NextResponse, type NextRequest } from "next/server";

import { hasSessionCookie, hostRedirect, isPrivatePath, needsSession } from "@/lib/routing";
import { contentSecurityPolicy } from "@/lib/security-headers";

const siteOrigin = new URL(process.env.SITE_URL ?? "http://localhost:3000").origin;
const appOrigin = new URL(process.env.APP_URL ?? siteOrigin).origin;
const development = process.env.NODE_ENV === "development";

/**
 * Runs before every page: sends signed-in routes to the app host, asks for sign-in where a page
 * needs it, and sets the Content-Security-Policy. Signed-in pages render per request, so they get
 * a fresh nonce; Next.js reads it from the request's policy and puts it on its own scripts.
 */
export function proxy(request: NextRequest): NextResponse {
  const { pathname, search } = request.nextUrl;
  const host = request.headers.get("x-forwarded-host") ?? request.headers.get("host") ?? "";
  const redirect = hostRedirect(host, pathname, search, siteOrigin, appOrigin);
  if (redirect !== null) {
    return NextResponse.redirect(redirect, 308);
  }
  if (needsSession(pathname) && !hasSessionCookie(request.cookies.getAll().map((c) => c.name))) {
    const signIn = new URL("/signin", request.url);
    signIn.searchParams.set("callbackUrl", `${pathname}${search}`);
    return NextResponse.redirect(signIn);
  }

  const isPrivate = isPrivatePath(pathname);
  const nonce = isPrivate ? Buffer.from(crypto.randomUUID()).toString("base64") : undefined;
  const policy = contentSecurityPolicy({
    ...(nonce === undefined ? {} : { nonce }),
    development,
    https: appOrigin.startsWith("https:"),
  });
  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("content-security-policy", policy);
  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set("content-security-policy", policy);
  if (isPrivate || (appOrigin !== siteOrigin && host === new URL(appOrigin).host)) {
    response.headers.set("x-robots-tag", "noindex, nofollow");
  }
  return response;
}

export const config = {
  matcher: [
    {
      source: "/((?!_next/static|_next/image|favicon.ico|icon.svg|api/zw|api/health).*)",
      missing: [
        { type: "header", key: "next-router-prefetch" },
        { type: "header", key: "purpose", value: "prefetch" },
      ],
    },
  ],
};

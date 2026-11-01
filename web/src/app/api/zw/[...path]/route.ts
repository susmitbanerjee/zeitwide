import { getToken } from "next-auth/jwt";
import { NextResponse, type NextRequest } from "next/server";

import { auth } from "@/auth";
import { applySetCookies } from "@/auth/tokens";
import { env } from "@/lib/env";
import {
  FORWARDED_REQUEST_HEADERS,
  isForwardedResponseHeader,
  isTrustedOrigin,
  MAX_BODY_BYTES,
  upstreamPath,
} from "@/lib/upstream";

// The API scales to zero too and takes about 20 seconds to start, so the first call after a quiet
// spell is slow rather than failed.
const UPSTREAM_TIMEOUT_MS = 45_000;

interface RouteContext {
  params: Promise<{ path: string[] }>;
}

async function forward(request: NextRequest, { params }: RouteContext): Promise<Response> {
  const path = upstreamPath((await params).path);
  if (path === null) {
    return problem(404, "not_found", "No such API route");
  }
  if (
    !isTrustedOrigin(
      request.method,
      request.headers.get("origin"),
      request.headers.get("sec-fetch-site"),
      env.appUrl,
    )
  ) {
    return problem(403, "forbidden", "Cross-site requests are not allowed");
  }
  const body =
    request.method === "GET" || request.method === "DELETE" ? undefined : await readBody(request);
  if (body === null) {
    return problem(413, "payload_too_large", "The request is too large");
  }

  const { accessToken, setCookies } = await sessionToken(request);
  // Only signed-in users reach the API through here; it relays nothing anonymously.
  if (accessToken === undefined) {
    return problem(401, "unauthorized", "Sign in to use the API from the app");
  }
  const headers = new Headers();
  for (const name of FORWARDED_REQUEST_HEADERS) {
    const value = request.headers.get(name);
    if (value !== null) {
      headers.set(name, value);
    }
  }
  headers.set("authorization", `Bearer ${accessToken}`);

  let upstream: Response;
  try {
    upstream = await fetch(`${env.apiUrl}${path}${request.nextUrl.search}`, {
      method: request.method,
      headers,
      ...(body === undefined ? {} : { body }),
      redirect: "manual",
      cache: "no-store",
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
  } catch {
    return problem(502, "internal_error", "The Zeitwide API could not be reached");
  }

  const response = new NextResponse(upstream.status === 204 ? null : upstream.body, {
    status: upstream.status,
  });
  upstream.headers.forEach((value, name) => {
    if (isForwardedResponseHeader(name)) {
      response.headers.set(name, value);
    }
  });
  response.headers.set("cache-control", "no-store");
  for (const cookie of setCookies) {
    response.headers.append("set-cookie", cookie);
  }
  return response;
}

/**
 * The signed-in user's access token, refreshed by Auth.js when it is about to expire. A refresh
 * rewrites the session cookie, so the new cookie goes back on this response and is read here.
 */
async function sessionToken(
  request: NextRequest,
): Promise<{ accessToken: string | undefined; setCookies: string[] }> {
  const pending = { headers: new Headers() };
  // The { req, res } form only reads the session: it runs the jwt callback (refreshing tokens) and
  // puts any new cookie on `pending`. A real Request would be handled as an Auth.js route instead.
  await (
    auth as unknown as (context: {
      req: { headers: Headers };
      res: typeof pending;
    }) => Promise<unknown>
  )({
    req: { headers: request.headers },
    res: pending,
  });
  const setCookies = pending.headers.getSetCookie();
  const secret = process.env.AUTH_SECRET;
  if (secret === undefined) {
    return { accessToken: undefined, setCookies };
  }
  const secureCookie = env.appUrl.startsWith("https://");
  const original = await getToken({
    req: { headers: { cookie: request.headers.get("cookie") ?? "" } },
    secret,
    secureCookie,
  });
  const token = await getToken({
    req: {
      headers: { cookie: applySetCookies(request.headers.get("cookie") ?? "", setCookies) },
    },
    secret,
    secureCookie,
  });
  const accessToken = token === null || token.error !== undefined ? undefined : token.accessToken;
  // Auth.js re-issues the session cookie on every read to slide its expiry. Send it back only when
  // the tokens were really refreshed: a call still in flight when the visitor signs out would
  // otherwise put the cookie that sign-out just deleted back in the browser.
  const refreshed = original?.accessToken !== token?.accessToken;
  return { accessToken, setCookies: refreshed ? setCookies : [] };
}

async function readBody(request: NextRequest): Promise<ArrayBuffer | null> {
  const declared = Number(request.headers.get("content-length") ?? "0");
  if (declared > MAX_BODY_BYTES) {
    return null;
  }
  const body = await request.arrayBuffer();
  return body.byteLength > MAX_BODY_BYTES ? null : body;
}

function problem(status: number, code: string, detail: string): Response {
  return Response.json(
    { title: detail, status, code, detail },
    {
      status,
      headers: { "content-type": "application/problem+json", "cache-control": "no-store" },
    },
  );
}

export { forward as DELETE, forward as GET, forward as POST, forward as PUT };

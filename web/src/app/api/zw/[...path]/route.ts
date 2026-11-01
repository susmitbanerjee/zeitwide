import { getToken } from "next-auth/jwt";
import { NextResponse, type NextRequest } from "next/server";

import { type SignedInUser, signApiToken } from "@/auth/api-token";
import { env } from "@/lib/env";
import {
  FORWARDED_REQUEST_HEADERS,
  isForwardedResponseHeader,
  isTrustedOrigin,
  MAX_BODY_BYTES,
  upstreamPath,
} from "@/lib/upstream";

// The API scales to zero and takes about 20 seconds to start, so the first call after a quiet
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

  // Only signed-in users reach the API through here; it relays nothing anonymously.
  const user = await sessionUser(request);
  if (user === undefined) {
    return problem(401, "unauthorized", "Sign in to use the API from the app");
  }
  const tokenSecret = process.env.ZEITWIDE_API_TOKEN_SECRET;
  if (tokenSecret === undefined || tokenSecret === "") {
    return problem(500, "internal_error", "The app isn't set up to call the API");
  }
  const headers = new Headers();
  for (const name of FORWARDED_REQUEST_HEADERS) {
    const value = request.headers.get(name);
    if (value !== null) {
      headers.set(name, value);
    }
  }
  headers.set("authorization", `Bearer ${await signApiToken(user, tokenSecret)}`);

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
  return response;
}

/**
 * Who the session cookie says is signed in, or undefined. It only reads the cookie: unlike an
 * Auth.js call that renews the session, it never sends one back, so a call still in flight when
 * someone signs out can't bring their cookie back.
 */
async function sessionUser(request: NextRequest): Promise<SignedInUser | undefined> {
  const secret = process.env.AUTH_SECRET;
  if (secret === undefined) {
    return undefined;
  }
  const token = await getToken({
    req: { headers: request.headers },
    secret,
    secureCookie: env.appUrl.startsWith("https://"),
  });
  if (token === null || typeof token.subject !== "string") {
    return undefined;
  }
  return {
    subject: token.subject,
    name: typeof token.name === "string" ? token.name : undefined,
    email: typeof token.email === "string" ? token.email : undefined,
    roles: token.roles ?? [],
  };
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

/** Headers every response carries. The Content-Security-Policy comes from contentSecurityPolicy. */
export const SECURITY_HEADERS: { key: string; value: string }[] = [
  { key: "Strict-Transport-Security", value: "max-age=63072000; includeSubDomains; preload" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Cross-Origin-Opener-Policy", value: "same-origin" },
  { key: "Cross-Origin-Resource-Policy", value: "same-origin" },
  {
    key: "Permissions-Policy",
    value: "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()",
  },
];

export interface CspOptions {
  /** A per-request nonce: signed-in pages render on every request, so they can carry one. */
  nonce?: string;
  /** Development needs eval for React's error overlays. */
  development?: boolean;
  /** Only an HTTPS site can upgrade requests; on plain-http localhost it would break forms. */
  https?: boolean;
  /** Other origins the browser may send data to, such as the Grafana Faro collector. */
  connectOrigins?: string[];
}

/**
 * The Content-Security-Policy. Signed-in pages get a strict nonce policy. Public pages are
 * prerendered once and served from the CDN, so no per-request nonce exists for the inline
 * scripts Next.js writes into them; they allow inline scripts but nothing from other origins.
 */
export function contentSecurityPolicy({
  nonce,
  development = false,
  https = true,
  connectOrigins = [],
}: CspOptions): string {
  const scripts =
    nonce === undefined
      ? ["'self'", "'unsafe-inline'"]
      : ["'self'", `'nonce-${nonce}'`, "'strict-dynamic'"];
  if (development) {
    scripts.push("'unsafe-eval'");
  }
  const directives: Record<string, string[]> = {
    "default-src": ["'self'"],
    "script-src": scripts,
    // Radix positions popovers with inline style attributes.
    "style-src": ["'self'", "'unsafe-inline'"],
    "img-src": ["'self'", "data:"],
    "font-src": ["'self'"],
    "connect-src": ["'self'", ...connectOrigins],
    "object-src": ["'none'"],
    "base-uri": ["'none'"],
    "form-action": ["'self'"],
    "frame-ancestors": ["'none'"],
    "manifest-src": ["'self'"],
    "worker-src": ["'self'"],
  };
  const policy = Object.entries(directives).map(([name, values]) => `${name} ${values.join(" ")}`);
  if (https && !development) {
    policy.push("upgrade-insecure-requests");
  }
  return policy.join("; ");
}

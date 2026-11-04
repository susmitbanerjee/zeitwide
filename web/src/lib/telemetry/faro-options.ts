/** A tenth of browser sessions report errors and Web Vitals; the rest send nothing. */
export const FARO_SESSION_SAMPLE_RATE = 0.1;

/** The Faro collector's origin, for the Content-Security-Policy; undefined when Faro is off. */
export function faroOrigin(url: string | undefined): string | undefined {
  if (url === undefined || url.trim() === "") {
    return undefined;
  }
  const parsed = new URL(url);
  if (parsed.protocol !== "https:") {
    throw new Error("ZEITWIDE_FARO_URL must be an https URL");
  }
  return parsed.origin;
}

/** A page URL without its query string or fragment, which can carry callback URLs or tokens. */
export function withoutQuery(url: string): string {
  const cut = url.search(/[?#]/);
  return cut === -1 ? url : url.slice(0, cut);
}

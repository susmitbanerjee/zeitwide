export const REVIEWER_ROLE = "reviewer";

/** Who may review changes, from a comma-separated list of email addresses in the environment. */
export function reviewerEmails(value: string | undefined): Set<string> {
  return new Set(
    (value ?? "")
      .split(",")
      .map((email) => email.trim().toLowerCase())
      .filter((email) => email !== ""),
  );
}

/**
 * The roles a person gets at sign-in. A reviewer decides what reaches the published dataset, so
 * the role goes only to an address the provider has verified: Google and GitHub confirm theirs,
 * Microsoft does not, so a Microsoft sign-in is never a reviewer, whatever email it claims.
 */
export function rolesFor(
  provider: string,
  email: string | null | undefined,
  emailVerified: boolean,
  reviewers: Set<string>,
): string[] {
  if (provider === "test-login") {
    return [];
  }
  const verified = (provider === "google" || provider === "github") && emailVerified;
  return verified && typeof email === "string" && reviewers.has(email.toLowerCase())
    ? [REVIEWER_ROLE]
    : [];
}

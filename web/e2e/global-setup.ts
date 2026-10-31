import { randomUUID } from "node:crypto";
import { mkdirSync } from "node:fs";

import { chromium, type FullConfig } from "@playwright/test";

import { KEYCLOAK, signInThroughKeycloak, STATE, USERS, type Role } from "./support";

/**
 * Creates the two test users in the local Keycloak (docker compose), each with a password made up
 * for this run, then signs each in once through the real PKCE flow and keeps the session.
 */
export default async function globalSetup(config: FullConfig): Promise<void> {
  const baseURL = config.projects[0]?.use.baseURL ?? "http://localhost:3000";
  const token = await adminToken();
  mkdirSync(STATE.dir, { recursive: true });
  const browser = await chromium.launch();
  for (const role of Object.keys(USERS) as Role[]) {
    const password = `E2e-${randomUUID()}`;
    process.env[`E2E_PASSWORD_${role.toUpperCase()}`] = password;
    await upsertUser(token, role, password);
    const page = await browser.newPage({ baseURL });
    await page.request.post(`${process.env.E2E_MOCK_API_URL ?? "http://127.0.0.1:8090"}/__reset`);
    await signInThroughKeycloak(page, USERS[role].username, password);
    await page.context().storageState({ path: STATE[role] });
    await page.close();
  }
  await browser.close();
}

async function adminToken(): Promise<string> {
  const response = await fetch(`${KEYCLOAK}/realms/master/protocol/openid-connect/token`, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "password",
      client_id: "admin-cli",
      username: "admin",
      // The local docker compose default; never a deployed credential.
      password: process.env.KEYCLOAK_ADMIN_PASSWORD ?? "admin",
    }),
  });
  if (!response.ok) {
    throw new Error(`Keycloak admin sign-in failed (${response.status}). Is \`make up\` running?`);
  }
  return ((await response.json()) as { access_token: string }).access_token;
}

async function upsertUser(token: string, role: Role, password: string): Promise<void> {
  const admin = `${KEYCLOAK}/admin/realms/zeitwide`;
  const headers = { authorization: `Bearer ${token}`, "content-type": "application/json" };
  const { username, firstName } = USERS[role];
  const found = (await (
    await fetch(`${admin}/users?exact=true&username=${username}`, { headers })
  ).json()) as { id: string }[];
  let id = found[0]?.id;
  if (id === undefined) {
    const created = await fetch(`${admin}/users`, {
      method: "POST",
      headers,
      body: JSON.stringify({
        username,
        enabled: true,
        email: `${username}@example.test`,
        emailVerified: true,
        firstName,
        lastName: "Tester",
      }),
    });
    id = created.headers.get("location")?.split("/").at(-1);
  }
  if (id === undefined) {
    throw new Error(`Could not create ${username}`);
  }
  await fetch(`${admin}/users/${id}/reset-password`, {
    method: "PUT",
    headers,
    body: JSON.stringify({ type: "password", value: password, temporary: false }),
  });
  if (role === "reviewer") {
    const reviewer = await (await fetch(`${admin}/roles/reviewer`, { headers })).json();
    await fetch(`${admin}/users/${id}/role-mappings/realm`, {
      method: "POST",
      headers,
      body: JSON.stringify([reviewer]),
    });
  }
}

import type { DefaultSession } from "next-auth";

declare module "next-auth" {
  interface Session {
    user: DefaultSession["user"] & { roles: string[]; reviewer: boolean };
    error?: "RefreshTokenError";
  }
}

declare module "next-auth/jwt" {
  interface JWT {
    accessToken?: string;
    refreshToken?: string | undefined;
    idToken?: string | undefined;
    expiresAt?: number | undefined;
    roles?: string[];
    error?: "RefreshTokenError" | undefined;
  }
}

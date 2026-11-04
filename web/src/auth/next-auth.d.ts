import type { DefaultSession } from "next-auth";

declare module "next-auth" {
  interface Session {
    user: DefaultSession["user"] & { roles: string[]; reviewer: boolean };
  }
}

declare module "next-auth/jwt" {
  interface JWT {
    /** Provider and the person's ID there, such as "google:1234": what the API knows them by. */
    subject?: string;
    roles?: string[];
    /** The AI referral seen at sign-in (`ai:<vendor>`), for the API to store on a new account. */
    signupSource?: string;
  }
}

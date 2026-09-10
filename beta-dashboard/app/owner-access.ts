import type { ChatGPTUser } from "./auth-headers";

// Verified owner of this private Sites project. Sites strips client-supplied auth
// headers at its boundary; never expose this worker on an untrusted direct route.
const OWNER_ID = "7bc1ddcb-d58a-4eda-8c01-7f57215fb01f";
const OWNER_EMAIL = "vishwajit1236@gmail.com";

export function isDashboardOwner(user: ChatGPTUser | null): boolean {
  if (!user) return false;
  // Some Sites sessions omit the optional ID; accept the verified email then.
  return user.userId === OWNER_ID ||
    (user.userId === user.email && user.email.toLowerCase() === OWNER_EMAIL);
}

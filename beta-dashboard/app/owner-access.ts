import type { ChatGPTUser } from "./auth-headers";

// Verified site-scoped identity from the owner’s signed-in Sites request.
// This differs from the account ID returned by Sites access management.
// Sites strips client-supplied auth
// headers at its boundary; never expose this worker on an untrusted direct route.
const OWNER_ID = "GQlioQa1hMoSqM4X3RRC5AzgE5zMYIhRctKHkXjW0EtWmzBKuOszif";
const OWNER_EMAIL = "vishwajit1236@gmail.com";

export function isDashboardOwner(user: ChatGPTUser | null): boolean {
  if (!user) return false;
  // Some Sites sessions omit the optional ID; accept the verified email then.
  return user.userId === OWNER_ID ||
    (user.userId === user.email && user.email.toLowerCase() === OWNER_EMAIL);
}

/** Read the tester flag on every authenticated request, including old installation tokens. */
export function installationAccessResponse(
  installation: { access_suspended: boolean },
  route: string | undefined,
): Response | null {
  // A suspended installation must still check for restoration and may delete its support name.
  if (!installation.access_suspended || route === "config" || route === "profile-delete") return null;
  return Response.json({ error: "access_suspended", allowed: false, reason: "access_suspended" }, { status: 423 });
}

export function accessConfig(suspended: boolean, now = new Date()) {
  return {
    access_suspended: suspended,
    // Offline display is bounded; attendance always requires an immediate online check.
    access_valid_until: suspended ? null : new Date(now.getTime() + 6 * 60 * 60 * 1000).toISOString(),
  };
}

export function testerDisplayName(tester: { support_name: string | null }): string {
  return tester.support_name?.trim() || "Awaiting profile";
}

type CohortProfile = {
  self_section: string | null;
  self_plc: string | null;
  detected_sections: string[];
  detected_groups: string[];
};

export function profileMatchStatus(profile: CohortProfile): "confirmed" | "mismatch" | "unknown" {
  if (profile.detected_sections.length === 0 && profile.detected_groups.length === 0) return "unknown";
  const section = profile.self_section?.match(/\b([A-Z])\b/i)?.[1]?.toUpperCase();
  const group = profile.self_plc?.match(/\d+/)?.[0];
  if (section && !profile.detected_sections.map((value) => value.toUpperCase()).includes(section)) return "mismatch";
  if (group && !profile.detected_groups.includes(group)) return "mismatch";
  return "confirmed";
}

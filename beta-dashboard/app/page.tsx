import { getChatGPTUser } from "./chatgpt-auth";
import Dashboard from "./Dashboard";
import { emptyDashboard } from "./types";

export const dynamic = "force-dynamic";

export default async function Home() {
  const user = await getChatGPTUser();
  return <Dashboard initialData={emptyDashboard()} ownerLabel={user?.email ?? "Site owner"} />;
}

export type BackendStatus = {
  name: "AI Exchange";
  stage: "scaffold";
  simulationAvailable: false;
};

export async function getBackendStatus(fetcher: typeof fetch = fetch): Promise<BackendStatus | null> {
  try {
    const response = await fetcher("http://127.0.0.1:8080/api/v1/status", {
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(2_000),
    });
    if (!response.ok) return null;
    const value: unknown = await response.json();
    if (typeof value !== "object" || value === null ||
        !("name" in value) || value.name !== "AI Exchange" ||
        !("stage" in value) || value.stage !== "scaffold" ||
        !("simulationAvailable" in value) || value.simulationAvailable !== false) return null;
    return value as BackendStatus;
  } catch {
    return null;
  }
}

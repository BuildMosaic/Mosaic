import registry from '../../../compatibility/registry.json';

// The registry contains certified stable releases. Never use the development build version here.
function latest(releases: Record<string, unknown>): string {
  const version = Object.keys(releases)
    .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }))
    .at(-1);
  if (!version) throw new Error('Documentation requires a certified release for each train.');
  return version;
}

export const runtimeVersion = latest(registry.runtime);
export const analysisVersion = latest(registry.analysis);
export const analysisRuntimeVersion = registry.analysis[analysisVersion as keyof typeof registry.analysis].maxRuntime;

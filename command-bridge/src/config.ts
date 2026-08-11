export interface RuntimeConfig {
  androidReadToken: string;
  mcpCapability: string;
}

export function getRuntimeConfig(): RuntimeConfig {
  return {
    androidReadToken: process.env.SELF_RUN_ANDROID_READ_TOKEN ?? "",
    mcpCapability: (process.env.SELF_RUN_MCP_CAPABILITY ?? "").trim(),
  };
}

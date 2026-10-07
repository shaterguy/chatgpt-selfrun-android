import { SharedGoogleAuthClient } from './shared-google-auth-client.mjs';

// SelfRun is a consumer only. The independent Termux service owns token files,
// expiry checks, OAuth refresh, serialization and authorization generations.
export class GoogleOAuthTokenProvider {
  constructor(config) {
    this.client = new SharedGoogleAuthClient({
      tokenFile: config.driveAuthTokenFile,
      socketPath: config.driveAuthServiceSocket,
    });
  }

  async accessToken() {
    return this.client.accessToken();
  }

  async forceRefresh(rejectedAccessToken) {
    return this.client.forceRefresh(rejectedAccessToken);
  }
}

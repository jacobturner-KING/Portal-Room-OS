export interface TokenSet {
  access_token: string;
  refresh_token: string;
  expires_at: number; // epoch ms
  scope?: string;
}

export interface ActionResult {
  ok: boolean;
  message: string;
}

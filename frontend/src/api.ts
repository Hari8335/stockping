export interface Session {
  accessToken: string;
  expiresAt: string;
  userId: string;
  merchantId: string;
  role: string;
}

export interface Waitlist {
  id: string;
  customerId: string;
  customerPhone: string;
  displayName: string | null;
  variantId: string;
  status: string;
  createdAt: string;
  consentStatus: string | null;
  eligible: boolean;
}

export interface WaitlistPage {
  items: Waitlist[];
  totalElements: number;
  page: number;
  size: number;
}

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

export async function api<T>(path: string, token?: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers);
  if (options.body) headers.set('Content-Type', 'application/json');
  if (token) headers.set('Authorization', `Bearer ${token}`);
  const response = await fetch(path, { ...options, headers, cache: 'no-store' });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new ApiError(response.status, problem?.detail || 'The request could not be completed. Please try again.');
  }
  return response.status === 204 ? undefined as T : response.json();
}

export function maskedPhone(phone: string) {
  return `${phone.slice(0, 3)}${'•'.repeat(Math.max(0, phone.length - 7))}${phone.slice(-4)}`;
}

export interface AiQueryResponse {
  query: string;
  intent: string;
  confidence: number;
  needsClarification: boolean;
  clarificationPrompt: string;
  extracted: {
    productPhrase: string;
    brand: string;
    model: string;
    attributes: Record<string, string>;
  };
  catalogStatus: string;
  matchedVariant: {
    variantId: string;
    sku: string;
    productName: string;
    brand: string;
    attributes: Record<string, string>;
    available: boolean;
    quantity: number;
  } | null;
  suggestedReply: string;
  purpose: string;
  disclaimer: string;
  provider: string;
  latencyMs: number;
}

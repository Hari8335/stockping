import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import App from './App';

const session = { accessToken: 'test-token', expiresAt: new Date(Date.now() + 1800000).toISOString(), userId: 'user', merchantId: 'merchant', role: 'MERCHANT_ADMIN' };
const row = { id: 'request', customerId: 'customer', customerPhone: '+94771234567', displayName: 'Demo Customer', variantId: '11111111-1111-1111-1111-111111111111', status: 'WAITING', createdAt: '2026-10-26T00:00:00Z', consentStatus: 'OPT_IN', eligible: true };
const empty = { items: [], totalElements: 0, page: 0, size: 25 };
let fetchMock: ReturnType<typeof vi.fn>;
function response(value: unknown, status = 200) { return new Response(status === 204 ? null : JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } }); }
function login() {
  fireEvent.change(screen.getByLabelText(/Email/), { target: { value: 'admin@demo.test' } });
  fireEvent.change(screen.getByLabelText(/Password/), { target: { value: 'demo-password' } });
  fireEvent.submit(screen.getByRole('form', { name: 'Merchant login' }));
}

beforeEach(() => {
  fetchMock = vi.fn(async (url: string) => {
    if (url.endsWith('/login')) return response(session);
    if (url.endsWith('/logout') || url.endsWith('/opt-out')) return response(null, 204);
    if (url.includes('/analytics/')) return response({ totalActive: 0 });
    return response(empty);
  });
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

test('identifies StockPing and renders the merchant login', () => {
  render(<App />);
  expect(screen.getByRole('main')).toBeInTheDocument();
  expect(screen.getByRole('heading', { level: 1, name: 'StockPing' })).toBeVisible();
  expect(screen.getByText('Merchant waitlists')).toBeVisible();
  expect(screen.getByLabelText(/Email/)).toBeRequired();
  expect(screen.getByLabelText(/Password/)).toBeRequired();
  expect(screen.queryByRole('heading', { name: 'Waitlist' })).not.toBeInTheDocument();
});

test('required input validation prevents login requests', () => {
  render(<App />);
  fireEvent.submit(screen.getByRole('form', { name: 'Merchant login' }));
  expect(screen.getByRole('alert')).toHaveTextContent('Enter your email and password.');
  expect(fetchMock).not.toHaveBeenCalled();
});

test('successful login opens the waitlist empty state and sends bearer authorization', async () => {
  render(<App />); login();
  expect(await screen.findByRole('heading', { name: 'No waitlist requests' })).toBeVisible();
  expect(screen.getByRole('heading', { name: 'Waitlist' })).toBeVisible();
  const request = fetchMock.mock.calls.find(([url]) => String(url).startsWith('/api/waitlists'));
  expect((request?.[1] as RequestInit).headers).toBeInstanceOf(Headers);
  expect(new Headers((request?.[1] as RequestInit).headers).get('Authorization')).toBe('Bearer test-token');
  expect(localStorage.length).toBe(0); expect(sessionStorage.length).toBe(0);
});

test('failed login shows the safe server error and keeps protected content hidden', async () => {
  fetchMock.mockResolvedValueOnce(response({ detail: 'Email or password is incorrect' }, 401));
  render(<App />); login();
  expect(await screen.findByRole('alert')).toHaveTextContent('Email or password is incorrect');
  expect(screen.queryByRole('heading', { name: 'Waitlist' })).not.toBeInTheDocument();
});

test('login displays its pending state and prevents repeated submission', async () => {
  let resolve!: (response: Response) => void;
  fetchMock.mockImplementationOnce(() => new Promise<Response>(done => { resolve = done; }));
  render(<App />); login();
  expect(screen.getByRole('button', { name: 'Signing in…' })).toBeDisabled();
  expect(screen.getByLabelText(/Password/)).toBeDisabled();
  await act(async () => resolve(response(session)));
  expect(await screen.findByRole('heading', { name: 'Waitlist' })).toBeVisible();
});

test('waitlist loading state is visible until data arrives', async () => {
  fetchMock.mockImplementation(async (url: string) => url.endsWith('/login') ? response(session) : new Promise(() => {}));
  render(<App />); login();
  expect(await screen.findByRole('status', { name: 'Loading waitlists' })).toBeVisible();
});

test('renders waitlist rows with masked phone, variant, consent and status', async () => {
  fetchMock.mockImplementation(async (url: string) => url.endsWith('/login') ? response(session)
    : url.includes('/analytics/') ? response({ totalActive: 1 }) : response({ ...empty, items: [row], totalElements: 1 }));
  render(<App />); login();
  expect(await screen.findByRole('table', { name: 'Customer waitlists' })).toBeVisible();
  expect(screen.getByText('Demo Customer')).toBeVisible();
  expect(screen.getByText(row.variantId)).toBeVisible();
  expect(screen.getByText('Opted in')).toBeVisible();
  expect(screen.getByText('WAITING')).toBeVisible();
  expect(screen.queryByText(row.customerPhone)).not.toBeInTheDocument();
  expect(screen.getByText('+94•••••4567')).toBeVisible();
  expect(screen.getByText('1 active requests')).toBeVisible();
});

test('waitlist failures show an error and permit retry', async () => {
  fetchMock.mockImplementation(async (url: string) => url.endsWith('/login') ? response(session) : response({ detail: 'Service unavailable' }, 503));
  render(<App />); login();
  expect(await screen.findByRole('alert')).toHaveTextContent('Service unavailable');
  fetchMock.mockImplementation(async (url: string) => url.includes('/analytics/') ? response({ totalActive: 0 }) : response(empty));
  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
  expect(await screen.findByRole('heading', { name: 'No waitlist requests' })).toBeVisible();
});

test('logout removes protected data and token, and calls the logout endpoint', async () => {
  render(<App />); login();
  await screen.findByRole('heading', { name: 'No waitlist requests' });
  fireEvent.click(screen.getByRole('button', { name: 'Log out' }));
  expect(screen.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  expect(screen.queryByRole('heading', { name: 'Waitlist' })).not.toBeInTheDocument();
  expect(fetchMock.mock.calls.some(([url]) => url === '/api/auth/logout')).toBe(true);
  expect(screen.getByLabelText(/Password/)).toHaveValue('');
});

test('expired authorization returns to login', async () => {
  fetchMock.mockImplementation(async (url: string) => url.endsWith('/login') ? response(session) : response({ detail: 'Token is invalid or expired' }, 401));
  render(<App />); login();
  expect(await screen.findByText('Your session expired. Please sign in again.')).toBeVisible();
  expect(screen.getByRole('heading', { name: 'Sign in' })).toBeVisible();
});

test('status and variant filters are sent to the server', async () => {
  render(<App />); login();
  await screen.findByRole('heading', { name: 'No waitlist requests' });
  fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Status' }));
  fireEvent.click(screen.getByRole('option', { name: 'WAITING' }));
  fireEvent.change(screen.getByLabelText('Variant ID'), { target: { value: row.variantId } });
  fireEvent.click(screen.getByRole('button', { name: 'Apply filters' }));
  await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).includes('status=WAITING') && String(url).includes(`variantId=${row.variantId}`))).toBe(true));
});

test('customer opt-out requires confirmation and refreshes the list', async () => {
  fetchMock.mockImplementation(async (url: string) => url.endsWith('/login') ? response(session)
    : url.includes('/analytics/') ? response({ totalActive: 1 }) : url.endsWith('/opt-out') ? response(null, 204)
      : response({ ...empty, items: [row], totalElements: 1 }));
  render(<App />); login();
  await screen.findByRole('table');
  fireEvent.click(screen.getByRole('button', { name: /Opt out \+94/ }));
  expect(await screen.findByRole('dialog')).toBeVisible();
  expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/opt-out'))).toBe(false);
  fireEvent.click(screen.getByRole('button', { name: 'Confirm opt-out' }));
  await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => url === '/api/customers/customer/opt-out')).toBe(true));
});

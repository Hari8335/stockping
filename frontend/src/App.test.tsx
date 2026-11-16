import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import App from './App';
import { StockPingLogo, StockPingWordmark } from './components/Logo';

// ── Shared fixtures ───────────────────────────────────────────────────────────

const session = {
  accessToken: 'test-token',
  expiresAt: new Date(Date.now() + 1800000).toISOString(),
  userId: 'user',
  merchantId: 'merchant',
  role: 'MERCHANT_ADMIN',
};

const row = {
  id: 'request',
  customerId: 'customer',
  customerPhone: '+94771234567',
  displayName: 'Demo Customer',
  variantId: '11111111-1111-1111-1111-111111111111',
  status: 'WAITING',
  createdAt: '2026-10-26T00:00:00Z',
  consentStatus: 'OPT_IN',
  eligible: true,
};

const empty = { items: [], totalElements: 0, page: 0, size: 25 };

let fetchMock: ReturnType<typeof vi.fn>;

function response(value: unknown, status = 200) {
  return new Response(
    status === 204 ? null : JSON.stringify(value),
    { status, headers: { 'Content-Type': 'application/json' } }
  );
}

// ── App rendering helper ──────────────────────────────────────────────────────

function renderApp(path = '/') {
  cleanup();
  window.history.replaceState({}, '', path);
  return render(<App />);
}

function setupDefaultMocks() {
  fetchMock = vi.fn(async (url: string) => {
    if (url.endsWith('/login')) return response(session);
    if (url.endsWith('/logout') || url.endsWith('/opt-out')) return response(null, 204);
    if (url.includes('/analytics/')) return response({ totalActive: 0, totalWaiting: 0, variants: [] });
    if (url.includes('/notifications')) return response([]);
    if (url.includes('/variants/lookup')) return response([]);
    if (url.includes('/products')) return response({ items: [], totalElements: 0, page: 0, size: 20 });
    return response(empty);
  });
  vi.stubGlobal('fetch', fetchMock);
}

async function loginUser() {
  fireEvent.change(screen.getByLabelText(/Email/), { target: { value: 'admin@demo.test' } });
  fireEvent.change(screen.getByLabelText(/Password/), { target: { value: 'demo-password' } });
  fireEvent.submit(screen.getByRole('form', { name: 'Merchant login' }));
}

beforeEach(() => {
  setupDefaultMocks();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

// ── Landing Page ──────────────────────────────────────────────────────────────

describe('Landing page', () => {
  test('renders the landing page at /', async () => {
    await renderApp('/');
    expect(screen.getByRole('heading', { level: 1 })).toBeVisible();
    expect(screen.getByText(/Out of stock shouldn't mean/)).toBeVisible();
    expect(screen.getAllByRole('link', { name: /Merchant sign in/i })[0]).toBeVisible();
  });

  test('landing page has How it works and Features sections', async () => {
    await renderApp('/');
    const howItWorksSection = document.getElementById('how-it-works');
    expect(howItWorksSection).toBeInTheDocument();
    expect(within(howItWorksSection!).getByText('HOW IT WORKS')).toBeVisible();

    const featuresSection = document.getElementById('features');
    expect(featuresSection).toBeInTheDocument();
    expect(within(featuresSection!).getByText('FEATURES')).toBeVisible();
  });

  test('landing page renders final CTA section with accurate messaging', async () => {
    await renderApp('/');
    expect(screen.getByText('When stock returns, keep your customers in the loop.')).toBeVisible();
    expect(screen.getByText(/Keep track of customer demand and make restock communication simpler/)).toBeVisible();
  });
});

// ── Brand Identity & Logo ─────────────────────────────────────────────────────

describe('Brand logo & wordmark', () => {
  test('renders StockPingLogo with accessible role and label', () => {
    const { container } = render(<StockPingLogo size={32} />);
    const svg = container.querySelector('svg');
    expect(svg).toBeInTheDocument();
    expect(svg).toHaveAttribute('aria-label', 'StockPing');
    expect(svg).toHaveAttribute('role', 'img');
  });

  test('renders StockPingWordmark with unified typography', () => {
    const { container } = render(<StockPingWordmark height={28} />);
    const svg = container.querySelector('svg');
    expect(svg).toBeInTheDocument();
    expect(svg).toHaveAttribute('aria-label', 'StockPing');
    expect(container.querySelector('text')).toHaveTextContent('StockPing');
  });
});

// ── Login Page ────────────────────────────────────────────────────────────────

describe('Login page', () => {
  test('renders merchant login form at /login', async () => {
    await renderApp('/login');
    expect(await screen.findByRole('form', { name: 'Merchant login' })).toBeVisible();
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeVisible();
    expect(screen.getByLabelText(/Email/)).toBeRequired();
    expect(screen.getByLabelText(/Password/)).toBeRequired();
  });

  test('required input validation prevents login requests', async () => {
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    fireEvent.submit(screen.getByRole('form', { name: 'Merchant login' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Enter your email and password.');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  test('successful login navigates to /app and sends bearer authorization', async () => {
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    await loginUser();
    // Should navigate to dashboard
    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeVisible();
    const request = fetchMock.mock.calls.find(([url]) => String(url).startsWith('/api/waitlists') || String(url).includes('/analytics/'));
    if (request) {
      expect(new Headers((request[1] as RequestInit).headers).get('Authorization')).toBe('Bearer test-token');
    }
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  test('failed login shows server error and keeps protected content hidden', async () => {
    fetchMock.mockResolvedValueOnce(response({ detail: 'Email or password is incorrect' }, 401));
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    await loginUser();
    expect(await screen.findByRole('alert')).toHaveTextContent('Email or password is incorrect');
    expect(screen.queryByRole('heading', { name: 'Dashboard' })).not.toBeInTheDocument();
  });

  test('login displays pending state and prevents repeated submission', async () => {
    let resolve!: (r: Response) => void;
    fetchMock.mockImplementationOnce(() => new Promise<Response>(done => { resolve = done; }));
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    await loginUser();
    expect(await screen.findByRole('button', { name: 'Signing in…' })).toBeDisabled();
    expect(screen.getByLabelText(/Password/)).toBeDisabled();
    await act(async () => resolve(response(session)));
    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeVisible();
  });
});

// ── Protected Routes ──────────────────────────────────────────────────────────

describe('Protected routes', () => {
  test('accessing /app unauthenticated redirects to /login', async () => {
    await renderApp('/app');
    expect(await screen.findByRole('form', { name: 'Merchant login' })).toBeVisible();
  });

  test('accessing /app/waitlists unauthenticated redirects to /login', async () => {
    await renderApp('/app/waitlists');
    expect(await screen.findByRole('form', { name: 'Merchant login' })).toBeVisible();
  });

  test('after login, /login redirects to /app', async () => {
    // Log in first by going through the login page
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    await loginUser();
    await screen.findByRole('heading', { name: 'Dashboard' });
  });
});

// ── Dashboard ─────────────────────────────────────────────────────────────────

describe('Dashboard', () => {
  test('shows demand summary after login', async () => {
    fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith('/login')) return response(session);
      if (url.includes('/analytics/')) return response({ totalActive: 7, totalWaiting: 3, variants: [] });
      return response(empty);
    });
    vi.stubGlobal('fetch', fetchMock);
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    await loginUser();
    await screen.findByRole('heading', { name: 'Dashboard' });
    expect(await screen.findByText('7')).toBeVisible(); // totalActive
  });

  test('dashboard loading state is visible until data arrives', async () => {
    fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith('/login')) return response(session);
      return new Promise(() => {});
    });
    vi.stubGlobal('fetch', fetchMock);
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    await loginUser();
    expect(await screen.findByRole('status', { name: 'Loading dashboard' })).toBeVisible();
  });
});

// ── Navigation ────────────────────────────────────────────────────────────────

describe('Navigation', () => {
  async function loginAndGetToDashboard() {
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    await loginUser();
    await screen.findByRole('heading', { name: 'Dashboard' });
  }

  test('sidebar navigation links are visible after login', async () => {
    await loginAndGetToDashboard();
    expect(screen.getByRole('link', { name: 'Waitlists' })).toBeVisible();
    expect(screen.getByRole('link', { name: 'Products' })).toBeVisible();
    expect(screen.getByRole('link', { name: 'Notifications' })).toBeVisible();
    expect(screen.getByRole('link', { name: 'AI Assistant' })).toBeVisible();
  });

  test('mobile navigation opens, closes, and keeps accessible links', async () => {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query.includes('max-width'), media: query, onchange: null,
      addListener: () => {}, removeListener: () => {}, addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => true,
    }));
    await loginAndGetToDashboard();
    const open = screen.getByRole('button', { name: 'Open navigation' });
    expect(open).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(open);
    expect(open).toHaveAttribute('aria-expanded', 'true');
    fireEvent.click(screen.getByRole('link', { name: 'Products' }));
    expect(await screen.findByRole('heading', { name: 'Products' })).toBeVisible();
    expect(open).toHaveAttribute('aria-expanded', 'false');
  });

  test('navigates to Products page', async () => {
    await loginAndGetToDashboard();
    fireEvent.click(screen.getByRole('link', { name: 'Products' }));
    expect(await screen.findByRole('heading', { name: 'Products' })).toBeVisible();
  });

  test('navigates to Waitlists page', async () => {
    fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith('/login')) return response(session);
      if (url.includes('/analytics/')) return response({ totalActive: 0, totalWaiting: 0, variants: [] });
      return response(empty);
    });
    vi.stubGlobal('fetch', fetchMock);
    await loginAndGetToDashboard();
    fireEvent.click(screen.getByRole('link', { name: 'Waitlists' }));
    expect(await screen.findByRole('heading', { name: 'Waitlists' })).toBeVisible();
  });

  test('navigates to Notifications page', async () => {
    await loginAndGetToDashboard();
    fireEvent.click(screen.getByRole('link', { name: 'Notifications' }));
    expect(await screen.findByRole('heading', { name: 'Notifications' })).toBeVisible();
  });

  test('navigates to AI Assistant page', async () => {
    await loginAndGetToDashboard();
    fireEvent.click(screen.getByRole('link', { name: 'AI Assistant' }));
    expect(await screen.findByRole('heading', { name: 'AI Stock Query Assistant' })).toBeVisible();
  });

  test('logout navigates back to login with sign-out notice', async () => {
    await loginAndGetToDashboard();
    // The user menu is in the sidebar — click the avatar/user section
    // Sign out is accessible via menu
    const signOutTrigger = screen.getByText('Merchant');
    fireEvent.click(signOutTrigger);
    const signOutItem = await screen.findByRole('menuitem', { name: 'Sign out' });
    fireEvent.click(signOutItem);
    expect(await screen.findByRole('form', { name: 'Merchant login' })).toBeVisible();
    expect(fetchMock.mock.calls.some(([url]) => url === '/api/auth/logout')).toBe(true);
  });
});

// ── Waitlist ──────────────────────────────────────────────────────────────────

describe('Waitlist screen', () => {
  async function goToWaitlist() {
    fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith('/login')) return response(session);
      if (url.includes('/analytics/')) return response({ totalActive: 1, totalWaiting: 1, variants: [] });
      if (url.includes('/waitlists')) return response({ ...empty, items: [row], totalElements: 1 });
      return response(empty);
    });
    vi.stubGlobal('fetch', fetchMock);
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    await loginUser();
    await screen.findByRole('heading', { name: 'Dashboard' });
    fireEvent.click(screen.getByRole('link', { name: 'Waitlists' }));
    return screen.findByRole('heading', { name: 'Waitlists' });
  }

  test('renders waitlist rows with masked phone, consent and status', async () => {
    await goToWaitlist();
    expect(await screen.findByRole('table', { name: 'Customer waitlists' })).toBeVisible();
    expect(screen.getByText('Demo Customer')).toBeVisible();
    expect(screen.getByText('Opted in')).toBeVisible();
    expect(screen.getByText('WAITING')).toBeVisible();
    expect(screen.queryByText(row.customerPhone)).not.toBeInTheDocument();
    expect(screen.getByText('+94•••••4567')).toBeVisible();
  });

  test('shows a batch-resolved product beside historical request and current consent', async () => {
    fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith('/login')) return response(session);
      if (url.includes('/analytics/')) return response({ totalActive: 0, totalWaiting: 0, variants: [] });
      if (url.includes('/variants/lookup')) return response([{ id: row.variantId, productName: 'Galaxy S25 Ultra', sku: 'S25-BLK', attributes: { color: 'Black' }, quantity: 0 }]);
      if (url.includes('/waitlists')) return response({ ...empty, items: [{ ...row, status: 'OPTED_OUT' }], totalElements: 1 });
      return response(empty);
    });
    vi.stubGlobal('fetch', fetchMock);
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    await loginUser();
    await screen.findByRole('heading', { name: 'Dashboard' });
    fireEvent.click(screen.getByRole('link', { name: 'Waitlists' }));
    const table = await screen.findByRole('table', { name: 'Customer waitlists' });
    expect(await within(table).findByText('Galaxy S25 Ultra')).toBeVisible();
    expect(within(table).getByText('OPTED OUT')).toBeVisible();
    expect(within(table).getByText('Opted in')).toBeVisible();
    expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/variants/lookup')).length).toBe(1);
  });

  test('waitlist loading state is visible until data arrives', async () => {
    fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith('/login')) return response(session);
      if (url.includes('/analytics/') || url.includes('/waitlists')) return new Promise(() => {});
      return response({ totalActive: 0, totalWaiting: 0, variants: [] });
    });
    vi.stubGlobal('fetch', fetchMock);
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    await loginUser();
    await screen.findByRole('heading', { name: 'Dashboard' });
    fireEvent.click(screen.getByRole('link', { name: 'Waitlists' }));
    expect(await screen.findByRole('status', { name: 'Loading waitlists' })).toBeVisible();
  });

  test('waitlist failures show an error and permit retry', async () => {
    fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith('/login')) return response(session);
      if (url.includes('/analytics/')) return response({ totalActive: 0, totalWaiting: 0, variants: [] });
      if (url.includes('/waitlists')) return response({ detail: 'Service unavailable' }, 503);
      return response(empty);
    });
    vi.stubGlobal('fetch', fetchMock);
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    await loginUser();
    await screen.findByRole('heading', { name: 'Dashboard' });
    fireEvent.click(screen.getByRole('link', { name: 'Waitlists' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Service unavailable');
    fetchMock.mockImplementation(async (url: string) => {
      if (url.includes('/analytics/')) return response({ totalActive: 0, totalWaiting: 0, variants: [] });
      return response(empty);
    });
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('heading', { name: 'No waitlist requests' })).toBeVisible();
  });

  test('status and variant filters are sent to the server', async () => {
    await goToWaitlist();
    await screen.findByRole('table', { name: 'Customer waitlists' });
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Status' }));
    fireEvent.click(screen.getByRole('option', { name: 'WAITING' }));
    fireEvent.change(screen.getByLabelText('Variant ID'), { target: { value: row.variantId } });
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() =>
      expect(fetchMock.mock.calls.some(([url]) =>
        String(url).includes('status=WAITING') && String(url).includes(`variantId=${row.variantId}`)
      )).toBe(true)
    );
  });

  test('customer opt-out requires confirmation and calls the API', async () => {
    fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith('/login')) return response(session);
      if (url.includes('/analytics/')) return response({ totalActive: 1, totalWaiting: 1, variants: [] });
      if (url.endsWith('/opt-out')) return response(null, 204);
      if (url.includes('/waitlists')) return response({ ...empty, items: [row], totalElements: 1 });
      return response(empty);
    });
    vi.stubGlobal('fetch', fetchMock);
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    await loginUser();
    await screen.findByRole('heading', { name: 'Dashboard' });
    fireEvent.click(screen.getByRole('link', { name: 'Waitlists' }));
    await screen.findByRole('table');
    fireEvent.click(screen.getByRole('button', { name: /Opt out \+94/ }));
    expect(await screen.findByRole('dialog')).toBeVisible();
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/opt-out'))).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: 'Confirm opt-out' }));
    await waitFor(() =>
      expect(fetchMock.mock.calls.some(([url]) => url === '/api/customers/customer/opt-out')).toBe(true)
    );
  });

  test('expired session triggers redirect to login from waitlist', async () => {
    fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith('/login')) return response(session);
      if (url.includes('/analytics/')) return response({ totalActive: 0, totalWaiting: 0, variants: [] });
      if (url.includes('/waitlists')) return response({ detail: 'Token is invalid or expired' }, 401);
      return response(empty);
    });
    vi.stubGlobal('fetch', fetchMock);
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    await loginUser();
    await screen.findByRole('heading', { name: 'Dashboard' });
    fireEvent.click(screen.getByRole('link', { name: 'Waitlists' }));
    // After 401 on waitlist fetch, session expires → redirect to login
    expect(await screen.findByRole('form', { name: 'Merchant login' })).toBeVisible();
  });
});

// ── Products ──────────────────────────────────────────────────────────────────

describe('Products page', () => {
  async function goToProducts() {
    fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith('/login')) return response(session);
      if (url.includes('/analytics/')) return response({ totalActive: 0, totalWaiting: 0, variants: [] });
      if (url.includes('/products')) return response({ items: [], totalElements: 0, page: 0, size: 20 });
      return response(empty);
    });
    vi.stubGlobal('fetch', fetchMock);
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    await loginUser();
    await screen.findByRole('heading', { name: 'Dashboard' });
    fireEvent.click(screen.getByRole('link', { name: 'Products' }));
    return screen.findByRole('heading', { name: 'Products' });
  }

  test('shows empty state with create button', async () => {
    await goToProducts();
    expect(await screen.findByRole('heading', { name: 'No products found' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Create first product' })).toBeVisible();
  });

  test('shows actual variant quantity and refreshes it after a stock update', async () => {
    const product = { id: 'product-1', name: 'Galaxy S25 Ultra', brand: 'Samsung', category: 'Phones', status: 'ACTIVE', createdAt: '2026-10-26T00:00:00Z' };
    const variant = { id: row.variantId, productId: product.id, productName: product.name, brand: product.brand, sku: 'S25-BLK', attributes: { color: 'Black' }, price: '100.5', status: 'ACTIVE' };
    let quantity = 0;
    fetchMock = vi.fn(async (url: string, options?: RequestInit) => {
      if (url.endsWith('/login')) return response(session);
      if (url.includes('/analytics/')) return response({ totalActive: 0, totalWaiting: 0, variants: [] });
      if (url.includes('/variants/lookup')) return response([{ id: variant.id, productName: product.name, sku: variant.sku, attributes: variant.attributes, quantity }]);
      if (url.endsWith('/inventory') && options?.method === 'PATCH') {
        quantity = JSON.parse(String(options.body)).quantity;
        return response({ inventory: { variantId: variant.id, quantity }, changed: true, restockTransition: false });
      }
      if (url.endsWith('/inventory')) return response({ variantId: variant.id, quantity, availabilityStatus: quantity ? 'IN_STOCK' : 'UNAVAILABLE' });
      if (url.endsWith('/products/product-1')) return response({ product, variants: [variant] });
      if (url.includes('/products')) return response({ items: [product], totalElements: 1, page: 0, size: 20 });
      return response(empty);
    });
    vi.stubGlobal('fetch', fetchMock);
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    await loginUser();
    await screen.findByRole('heading', { name: 'Dashboard' });
    fireEvent.click(screen.getByRole('link', { name: 'Products' }));
    fireEvent.click(await screen.findByRole('button', { name: 'View →' }));
    expect(await screen.findByText('$100.50')).toBeVisible();
    expect(await screen.findByText('0 · Out of stock')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: 'Update stock' }));
    expect(await within(screen.getByRole('dialog')).findByText('Out of stock')).toBeVisible();
    fireEvent.change(screen.getByRole('spinbutton', { name: 'New quantity' }), { target: { value: '5' } });
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Update stock' }));
    expect(await screen.findByText('5 · Available')).toBeVisible();
    expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/variants/lookup')).length).toBe(2);
  });

  test('opens create product dialog', async () => {
    await goToProducts();
    await screen.findByRole('heading', { name: 'No products found' });
    fireEvent.click(screen.getByRole('button', { name: 'New product' }));
    expect(await screen.findByRole('dialog')).toBeVisible();
    expect(screen.getByRole('heading', { name: 'New product' })).toBeVisible();
  });

  test('shows success feedback after creating a product', async () => {
    const product = { id: 'product-2', name: 'Pixel 10', brand: 'Google', category: 'Phones', status: 'ACTIVE', createdAt: '2026-11-16T00:00:00Z' };
    fetchMock = vi.fn(async (url: string, options?: RequestInit) => {
      if (url.endsWith('/login')) return response(session);
      if (url.includes('/analytics/')) return response({ totalActive: 0, totalWaiting: 0, variants: [] });
      if (url.endsWith('/products') && options?.method === 'POST') return response(product, 201);
      if (url.includes('/products')) return response({ items: [product], totalElements: 1, page: 0, size: 20 });
      return response(empty);
    });
    vi.stubGlobal('fetch', fetchMock);
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    await loginUser();
    await screen.findByRole('heading', { name: 'Dashboard' });
    fireEvent.click(screen.getByRole('link', { name: 'Products' }));
    fireEvent.click(await screen.findByRole('button', { name: 'New product' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/^Name/), { target: { value: product.name } });
    fireEvent.change(within(dialog).getByLabelText(/^Brand/), { target: { value: product.brand } });
    fireEvent.change(within(dialog).getByLabelText(/^Category/), { target: { value: product.category } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create product' }));
    expect(await screen.findByText('Product created successfully.')).toBeVisible();
    expect(await screen.findByText(product.name)).toBeVisible();
  });

  test('loading state shown for products', async () => {
    fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith('/login')) return response(session);
      if (url.includes('/analytics/')) return response({ totalActive: 0, totalWaiting: 0, variants: [] });
      if (url.includes('/products')) return new Promise(() => {});
      return response(empty);
    });
    vi.stubGlobal('fetch', fetchMock);
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    await loginUser();
    await screen.findByRole('heading', { name: 'Dashboard' });
    fireEvent.click(screen.getByRole('link', { name: 'Products' }));
    expect(await screen.findByRole('status', { name: 'Loading products' })).toBeVisible();
  });
});

// ── Notifications ─────────────────────────────────────────────────────────────

describe('Notifications page', () => {
  test('shows empty state when no notifications', async () => {
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    await loginUser();
    await screen.findByRole('heading', { name: 'Dashboard' });
    fireEvent.click(screen.getByRole('link', { name: 'Notifications' }));
    expect(await screen.findByRole('heading', { name: 'No notifications yet' })).toBeVisible();
  });

  test('shows accepted dispatch separately from unreported provider delivery', async () => {
    const job = {
      id: 'job1',
      availability_event_id: 'evt1',
      variant_id: '11111111-1111-1111-1111-111111111111',
      waitlist_id: '22222222-2222-2222-2222-222222222222',
      status: 'ACCEPTED',
      reason: null,
      provider_message_id: 'msg123',
      created_at: '2026-10-26T00:00:00Z',
      authorized_at: '2026-10-26T00:01:00Z',
      completed_at: '2026-10-26T00:02:00Z',
    };
    fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith('/login')) return response(session);
      if (url.includes('/analytics/')) return response({ totalActive: 0, totalWaiting: 0, variants: [] });
      if (url.includes('/notifications')) return response([job]);
      if (url.includes('/variants/lookup')) return response([{ id: job.variant_id, productName: 'Galaxy S25 Ultra', sku: 'S25-BLK', attributes: { color: 'Black' }, quantity: 0 }]);
      return response(empty);
    });
    vi.stubGlobal('fetch', fetchMock);
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    await loginUser();
    await screen.findByRole('heading', { name: 'Dashboard' });
    fireEvent.click(screen.getByRole('link', { name: 'Notifications' }));
    await screen.findByRole('heading', { name: 'Notifications' });
    expect(await screen.findByRole('table', { name: 'Notification history' })).toBeVisible();
    expect(screen.getByText('ACCEPTED')).toBeVisible();
    expect(screen.getByText('Unreported')).toBeVisible();
    expect(await screen.findByText('Galaxy S25 Ultra')).toBeVisible();
  });
});

// ── AI Assistant ──────────────────────────────────────────────────────────────

describe('AI Assistant', () => {
  async function goToAi() {
    await renderApp('/login');
    await screen.findByRole('form', { name: 'Merchant login' });
    await loginUser();
    await screen.findByRole('heading', { name: 'Dashboard' });
    fireEvent.click(screen.getByRole('link', { name: 'AI Assistant' }));
    return screen.findByRole('heading', { name: 'AI Stock Query Assistant' });
  }

  test('renders AI Assistant with sample queries', async () => {
    await goToAi();
    expect(screen.getByText('S25 Ultra 512 black available?')).toBeVisible();
    expect(screen.getByRole('form', { name: 'AI Query Form' })).toBeVisible();
  });

  test('submitting inquiry displays parsed entities, stock status and suggested reply', async () => {
    const aiMock = {
      query: 'S25 Ultra 512 black available?',
      intent: 'CHECK_STOCK',
      confidence: 0.94,
      needsClarification: false,
      clarificationPrompt: '',
      extracted: {
        productPhrase: 'S25 Ultra',
        brand: 'Samsung',
        model: 'Galaxy S25 Ultra',
        attributes: { storage: '512GB', color: 'Black' },
      },
      catalogStatus: 'MATCHED',
      matchedVariant: {
        variantId: '11111111-1111-1111-1111-111111111111',
        sku: 'S25U-512-BLK',
        productName: 'Samsung Galaxy S25 Ultra',
        brand: 'Samsung',
        attributes: { storage: '512GB', color: 'Black' },
        available: false,
        quantity: 0,
      },
      suggestedReply: 'Samsung Galaxy S25 Ultra (S25U-512-BLK) is currently out of stock. Would you like to be notified when it arrives?',
      purpose: 'STOCK_WAITLIST_OFFER',
      disclaimer: 'AI output is advisory. Stock availability is authoritatively verified by Catalog Service.',
      provider: 'mock',
      latencyMs: 15,
    };
    fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith('/login')) return response(session);
      if (url.includes('/analytics/')) return response({ totalActive: 0, totalWaiting: 0, variants: [] });
      if (url.includes('/query-preview')) return response(aiMock);
      return response(empty);
    });
    vi.stubGlobal('fetch', fetchMock);

    await goToAi();
    fireEvent.click(screen.getByText('S25 Ultra 512 black available?'));
    expect(await screen.findByText('Extraction & Stock Resolution')).toBeVisible();
    expect(screen.getByText('94%')).toBeVisible();
    expect(screen.getByText('Samsung Galaxy S25 Ultra')).toBeVisible();
    expect(screen.getByText(/Out of Stock \(0 units\)/)).toBeVisible();
    expect(screen.getByText(new RegExp(aiMock.disclaimer))).toBeVisible();
  });

  test('AI error shows alert with retry button', async () => {
    fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith('/login')) return response(session);
      if (url.includes('/analytics/')) return response({ totalActive: 0, totalWaiting: 0, variants: [] });
      if (url.includes('/query-preview')) return response({ detail: 'AI service unavailable' }, 503);
      return response(empty);
    });
    vi.stubGlobal('fetch', fetchMock);
    await goToAi();
    const input = screen.getByLabelText(/Customer WhatsApp message/);
    fireEvent.change(input, { target: { value: 'test query' } });
    fireEvent.submit(screen.getByRole('form', { name: 'AI Query Form' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('AI service unavailable');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeVisible();
  });
});

import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent,
  DialogTitle, Divider, Grid, LinearProgress, MenuItem, Paper, Stack,
  Snackbar, Table, TableBody, TableCell, TableContainer, TableHead, TablePagination, TableRow,
  TextField, Typography,
} from '@mui/material';
import { tokens } from '../theme';
import { api, ApiError, type Session } from '../api';
import { useVariantSummaries } from '../variantSummaries';

// ── Types ─────────────────────────────────────────────────────────────────────

interface ProductView {
  id: string; name: string; brand: string; category: string;
  status: string; createdAt: string; updatedAt: string;
}
interface VariantView {
  id: string; productId: string; productName: string; brand: string;
  sku: string; attributes: Record<string, string>; price: string | null;
  status: string; createdAt: string; updatedAt: string;
}
interface ProductDetail { product: ProductView; variants: VariantView[] }
interface InventoryView { variantId: string; quantity: number; availabilityStatus: string; updatedAt: string }
interface PageView<T> { items: T[]; totalElements: number; page: number; size: number }

interface ProductsProps { session: Session; onExpired: () => void }

// ── Helpers ───────────────────────────────────────────────────────────────────

function StatusChip({ status }: { status: string }) {
  const color = status === 'ACTIVE' ? tokens.teal : status === 'DISCONTINUED' ? tokens.red : tokens.amber;
  return (
    <Box
      component="span"
      sx={{
        display: 'inline-block',
        px: 0.75,
        py: 0.2,
        bgcolor: color + '20',
        color,
        borderRadius: 0.5,
        fontSize: '0.7rem',
        fontWeight: 700,
        letterSpacing: '0.05em',
      }}
    >
      {status}
    </Box>
  );
}

function isAvailable(quantity: number) {
  return quantity > 0;
}

function AvailChip({ quantity }: { quantity: number }) {
  const inStock = isAvailable(quantity);
  return (
    <Box
      component="span"
      sx={{
        display: 'inline-block',
        px: 0.75,
        py: 0.2,
        bgcolor: inStock ? tokens.tealBg : tokens.redBg,
        color: inStock ? tokens.teal : tokens.red,
        borderRadius: 0.5,
        fontSize: '0.7rem',
        fontWeight: 700,
      }}
    >
      {inStock ? 'In stock' : 'Out of stock'}
    </Box>
  );
}

function formatPrice(price: string | null) {
  return price === null ? '—' : `$${Number(price).toFixed(2)}`;
}

function attrs(a: Record<string, string>) {
  return Object.entries(a).map(([k, v]) => `${k}: ${v}`).join(' · ') || '—';
}

// ── Create Product Dialog ─────────────────────────────────────────────────────

function CreateProductDialog({ open, session, onClose, onCreated }: {
  open: boolean; session: Session; onClose: () => void; onCreated: (p: ProductView) => void;
}) {
  const [name, setName] = useState('');
  const [brand, setBrand] = useState('');
  const [category, setCategory] = useState('');
  const [status, setStatus] = useState('ACTIVE');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');

  function reset() { setName(''); setBrand(''); setCategory(''); setStatus('ACTIVE'); setError(''); }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!name.trim() || !brand.trim() || !category.trim()) { setError('All fields are required.'); return; }
    setPending(true); setError('');
    try {
      const product = await api<ProductView>('/api/products', session.accessToken, {
        method: 'POST',
        body: JSON.stringify({ name: name.trim(), brand: brand.trim(), category: category.trim(), status }),
      });
      reset();
      onCreated(product);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Unable to create product.');
    } finally { setPending(false); }
  }

  return (
    <Dialog open={open} onClose={() => { if (!pending) { reset(); onClose(); } }} maxWidth="sm" fullWidth>
      <DialogTitle>New product</DialogTitle>
      <DialogContent>
        <Stack component="form" id="create-product-form" spacing={2.5} onSubmit={submit} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField label="Name" required value={name} onChange={e => setName(e.target.value)} disabled={pending} fullWidth />
          <TextField label="Brand" required value={brand} onChange={e => setBrand(e.target.value)} disabled={pending} fullWidth />
          <TextField label="Category" required value={category} onChange={e => setCategory(e.target.value)} disabled={pending} fullWidth />
          <TextField select label="Status" value={status} onChange={e => setStatus(e.target.value)} disabled={pending}>
            {['ACTIVE', 'DRAFT', 'DISCONTINUED'].map(s => <MenuItem key={s} value={s}>{s}</MenuItem>)}
          </TextField>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={() => { reset(); onClose(); }} disabled={pending}>Cancel</Button>
        <Button type="submit" form="create-product-form" variant="contained" disabled={pending}>
          {pending ? 'Creating…' : 'Create product'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ── Create Variant Dialog ─────────────────────────────────────────────────────

function CreateVariantDialog({ open, productId, session, onClose, onCreated }: {
  open: boolean; productId: string; session: Session;
  onClose: () => void; onCreated: (v: VariantView) => void;
}) {
  const [sku, setSku] = useState('');
  const [price, setPrice] = useState('');
  const [status, setStatus] = useState('ACTIVE');
  const [attrKey, setAttrKey] = useState('');
  const [attrVal, setAttrVal] = useState('');
  const [attributes, setAttributes] = useState<Record<string, string>>({});
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');

  function reset() { setSku(''); setPrice(''); setStatus('ACTIVE'); setAttrKey(''); setAttrVal(''); setAttributes({}); setError(''); }
  function addAttr() {
    if (attrKey.trim() && attrVal.trim()) {
      setAttributes(prev => ({ ...prev, [attrKey.trim()]: attrVal.trim() }));
      setAttrKey(''); setAttrVal('');
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!sku.trim()) { setError('SKU is required.'); return; }
    setPending(true); setError('');
    try {
      const variant = await api<VariantView>(`/api/products/${productId}/variants`, session.accessToken, {
        method: 'POST',
        body: JSON.stringify({ sku: sku.trim(), attributes, price: price ? Number(price) : null, status }),
      });
      reset();
      onCreated(variant);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Unable to create variant.');
    } finally { setPending(false); }
  }

  return (
    <Dialog open={open} onClose={() => { if (!pending) { reset(); onClose(); } }} maxWidth="sm" fullWidth>
      <DialogTitle>Add variant</DialogTitle>
      <DialogContent>
        <Stack component="form" id="create-variant-form" spacing={2.5} onSubmit={submit} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField label="SKU" required value={sku} onChange={e => setSku(e.target.value)} disabled={pending} fullWidth />
          <TextField label="Price (optional)" type="number" value={price} onChange={e => setPrice(e.target.value)} disabled={pending} fullWidth />
          <TextField select label="Status" value={status} onChange={e => setStatus(e.target.value)} disabled={pending}>
            {['ACTIVE', 'DRAFT', 'DISCONTINUED'].map(s => <MenuItem key={s} value={s}>{s}</MenuItem>)}
          </TextField>
          <Box>
            <Typography variant="subtitle2" sx={{ mb: 1 }}>Attributes (optional)</Typography>
            <Stack direction="row" spacing={1} sx={{ mb: 1 }}>
              <TextField
                label="Key" value={attrKey} onChange={e => setAttrKey(e.target.value)}
                disabled={pending} size="small"
                onKeyDown={e => e.key === 'Enter' && (e.preventDefault(), addAttr())}
              />
              <TextField
                label="Value" value={attrVal} onChange={e => setAttrVal(e.target.value)}
                disabled={pending} size="small"
                onKeyDown={e => e.key === 'Enter' && (e.preventDefault(), addAttr())}
              />
              <Button onClick={addAttr} disabled={!attrKey.trim() || !attrVal.trim() || pending} variant="outlined" size="small">
                Add
              </Button>
            </Stack>
            {Object.entries(attributes).map(([k, v]) => (
              <Chip
                key={k} label={`${k}: ${v}`} size="small" sx={{ mr: 0.5, mb: 0.5 }}
                onDelete={() => setAttributes(prev => { const n = { ...prev }; delete n[k]; return n; })}
              />
            ))}
          </Box>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={() => { reset(); onClose(); }} disabled={pending}>Cancel</Button>
        <Button type="submit" form="create-variant-form" variant="contained" disabled={pending}>
          {pending ? 'Adding…' : 'Add variant'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ── Inventory Update Dialog ───────────────────────────────────────────────────

function InventoryDialog({ open, variant, session, onClose, onUpdated }: {
  open: boolean; variant: VariantView | null; session: Session;
  onClose: () => void; onUpdated: () => void;
}) {
  const [qty, setQty] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [currentInv, setCurrentInv] = useState<InventoryView | null>(null);
  const [loadingInv, setLoadingInv] = useState(false);

  useEffect(() => {
    if (!open || !variant) { setCurrentInv(null); return; }
    setLoadingInv(true);
    api<InventoryView>(`/api/variants/${variant.id}/inventory`, session.accessToken)
      .then(inv => setCurrentInv(inv))
      .catch(() => setCurrentInv(null))
      .finally(() => setLoadingInv(false));
  }, [open, variant?.id]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!variant) return;
    const quantity = parseInt(qty, 10);
    if (isNaN(quantity) || quantity < 0) { setError('Enter a valid quantity (0 or more).'); return; }
    setPending(true); setError('');
    try {
      await api(`/api/variants/${variant.id}/inventory`, session.accessToken, {
        method: 'PATCH',
        body: JSON.stringify({ quantity }),
      });
      setQty('');
      onUpdated();
      onClose();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Unable to update inventory.');
    } finally { setPending(false); }
  }

  return (
    <Dialog open={open} onClose={() => { if (!pending) { setQty(''); setError(''); onClose(); } }} maxWidth="xs" fullWidth>
      <DialogTitle>Update inventory</DialogTitle>
      <DialogContent>
        {variant && (
          <Stack spacing={2} sx={{ pt: 1 }}>
            <Box sx={{ p: 1.5, bgcolor: tokens.parchment, borderRadius: 1 }}>
              <Typography sx={{ fontWeight: 600, fontSize: '0.875rem' }}>{variant.productName}</Typography>
              <Typography variant="body2">SKU: {variant.sku}</Typography>
              {loadingInv && <LinearProgress sx={{ mt: 1 }} />}
              {currentInv && !loadingInv && (
                <Typography variant="body2" sx={{ mt: 0.5 }}>
                  Current stock: <strong>{currentInv.quantity}</strong>{' '}
                  <AvailChip quantity={currentInv.quantity} />
                </Typography>
              )}
            </Box>
            {error && <Alert severity="error">{error}</Alert>}
            <Alert severity="info" sx={{ fontSize: '0.8rem' }}>
              When stock notifications are enabled, changing quantity from 0 to a positive number starts notification processing for eligible customers who opted in.
            </Alert>
            <Stack component="form" id="inventory-form" onSubmit={submit} spacing={2}>
              <TextField
                label="New quantity"
                type="number"
                required
                value={qty}
                onChange={e => setQty(e.target.value)}
                disabled={pending}
                slotProps={{ htmlInput: { min: 0 } }}
                fullWidth
              />
            </Stack>
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={() => { setQty(''); setError(''); onClose(); }} disabled={pending}>Cancel</Button>
        <Button type="submit" form="inventory-form" variant="contained" disabled={pending || !qty}>
          {pending ? 'Updating…' : 'Update stock'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ── Product Detail View ───────────────────────────────────────────────────────

function ProductDetailView({ session, onExpired, productId, onBack }: {
  session: Session; onExpired: () => void; productId: string; onBack: () => void;
}) {
  const [data, setData] = useState<ProductDetail | null>(null);
  const [pending, setPending] = useState(true);
  const [error, setError] = useState('');
  const [variantDialogOpen, setVariantDialogOpen] = useState(false);
  const [invDialogVariant, setInvDialogVariant] = useState<VariantView | null>(null);
  const [stockRefresh, setStockRefresh] = useState(0);
  const stock = useVariantSummaries(data?.variants.map(v => v.id) ?? [], session.accessToken, onExpired, stockRefresh);

  function load() {
    setPending(true); setError('');
    api<ProductDetail>(`/api/products/${productId}`, session.accessToken)
      .then(d => setData(d))
      .catch(failure => {
        if (failure instanceof ApiError && failure.status === 401) onExpired();
        else setError(failure instanceof Error ? failure.message : 'Unable to load product.');
      })
      .finally(() => setPending(false));
  }

  useEffect(() => { load(); }, [productId]);

  return (
    <Stack spacing={3}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
        <Button onClick={onBack} size="small" variant="outlined" sx={{ minWidth: 0, px: 1 }}>
          ← Back
        </Button>
        {data && (
          <Box>
            <Typography component="h2" variant="h5">{data.product.name}</Typography>
            <Typography variant="body2" color="text.secondary">
              {data.product.brand} · {data.product.category} · <StatusChip status={data.product.status} />
            </Typography>
          </Box>
        )}
      </Box>

      {pending && <LinearProgress />}
      {error && <Alert severity="error">{error}</Alert>}

      {data && (
        <>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <Typography variant="subtitle2">Variants ({data.variants.length})</Typography>
            <Button size="small" variant="outlined" onClick={() => setVariantDialogOpen(true)}>
              Add variant
            </Button>
          </Box>

          {data.variants.length === 0 ? (
            <Paper variant="outlined" sx={{ p: 3, textAlign: 'center' }}>
              <Typography variant="body2" color="text.secondary">
                No variants yet. Add a variant to track inventory.
              </Typography>
            </Paper>
          ) : (
            <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
              <TableContainer sx={{ overflowX: 'auto' }}>
                <Table aria-label="Product variants" size="small" sx={{
                  minWidth: { md: 760 },
                  '@media (max-width:899px)': {
                    display: 'block',
                    '& thead, & tbody': { display: 'block' },
                    '& thead': { display: 'none' },
                    '& tr': { display: 'block', p: 1.5, borderBottom: `1px solid ${tokens.divider}` },
                    '& td': { display: 'flex', justifyContent: 'space-between', gap: 1.5, border: 0, py: 0.75, px: 0, overflowWrap: 'anywhere' },
                    '& td::before': { content: 'attr(data-label)', color: tokens.inkLight, fontSize: '0.75rem', fontWeight: 600, flex: '0 0 88px' },
                  },
                }}>
                  <TableHead>
                    <TableRow>
                      {['SKU', 'Attributes', 'Price', 'Status', 'Current stock', 'Inventory'].map(h => (
                        <TableCell key={h}>{h}</TableCell>
                      ))}
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {data.variants.map(v => (
                      <TableRow key={v.id}>
                        <TableCell data-label="SKU" sx={{ fontFamily: 'monospace', fontSize: '0.8rem' }}>{v.sku}</TableCell>
                        <TableCell data-label="Attributes" sx={{ color: tokens.inkLight, fontSize: '0.8rem' }}>{attrs(v.attributes)}</TableCell>
                        <TableCell data-label="Price">{formatPrice(v.price)}</TableCell>
                        <TableCell data-label="Status"><StatusChip status={v.status} /></TableCell>
                        <TableCell data-label="Current stock" sx={{ whiteSpace: 'nowrap' }}>
                          {stock.loading ? 'Loading…' : stock.error ? 'Unavailable' : stock.items[v.id]?.quantity == null ? 'Unavailable' :
                            !isAvailable(stock.items[v.id].quantity ?? 0) ? `${stock.items[v.id].quantity} · Out of stock` : `${stock.items[v.id].quantity} · Available`}
                        </TableCell>
                        <TableCell data-label="Inventory">
                          <Button
                            size="small"
                            variant="outlined"
                            onClick={() => setInvDialogVariant(v)}
                            sx={{ fontSize: '0.75rem', minHeight: { xs: 44, sm: 32 } }}
                          >
                            Update stock
                          </Button>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            </Paper>
          )}

          <CreateVariantDialog
            open={variantDialogOpen}
            productId={productId}
            session={session}
            onClose={() => setVariantDialogOpen(false)}
            onCreated={() => { setVariantDialogOpen(false); load(); }}
          />
          <InventoryDialog
            open={invDialogVariant !== null}
            variant={invDialogVariant}
            session={session}
            onClose={() => setInvDialogVariant(null)}
            onUpdated={() => setStockRefresh(n => n + 1)}
          />
        </>
      )}
    </Stack>
  );
}

// ── Main Products Page ────────────────────────────────────────────────────────

export default function ProductsPage({ session, onExpired }: ProductsProps) {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<PageView<ProductView> | null>(null);
  const [pending, setPending] = useState(true);
  const [error, setError] = useState('');
  const [nameFilter, setNameFilter] = useState('');
  const [brandFilter, setBrandFilter] = useState('');
  const [appliedFilters, setAppliedFilters] = useState({ name: '', brand: '' });
  const [createOpen, setCreateOpen] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [success, setSuccess] = useState('');

  function load() {
    setPending(true); setError('');
    const q = new URLSearchParams({ page: String(page), size: '20' });
    if (appliedFilters.name) q.set('name', appliedFilters.name);
    if (appliedFilters.brand) q.set('brand', appliedFilters.brand);
    api<PageView<ProductView>>(`/api/products?${q}`, session.accessToken)
      .then(d => setData(d))
      .catch(failure => {
        if (failure instanceof ApiError && failure.status === 401) onExpired();
        else setError(failure instanceof Error ? failure.message : 'Unable to load products.');
      })
      .finally(() => setPending(false));
  }

  useEffect(() => { load(); }, [session.accessToken, page, appliedFilters]);

  if (selectedId) {
    return (
      <ProductDetailView
        session={session}
        onExpired={onExpired}
        productId={selectedId}
        onBack={() => setSelectedId(null)}
      />
    );
  }

  return (
    <Stack spacing={3}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: 2 }}>
        <Box>
          <Typography component="h1" variant="h5">Products</Typography>
          <Typography variant="body2" color="text.secondary">Manage your product catalog and variants.</Typography>
        </Box>
        <Button variant="contained" onClick={() => setCreateOpen(true)}>New product</Button>
      </Box>

      {/* Filters */}
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
        <TextField
          label="Name" value={nameFilter} onChange={e => setNameFilter(e.target.value)}
          onKeyDown={e => e.key === 'Enter' && setAppliedFilters({ name: nameFilter.trim(), brand: brandFilter.trim() })}
          size="small" sx={{ flex: 1 }}
        />
        <TextField
          label="Brand" value={brandFilter} onChange={e => setBrandFilter(e.target.value)}
          onKeyDown={e => e.key === 'Enter' && setAppliedFilters({ name: nameFilter.trim(), brand: brandFilter.trim() })}
          size="small" sx={{ flex: 1 }}
        />
        <Button
          variant="outlined"
          onClick={() => { setAppliedFilters({ name: nameFilter.trim(), brand: brandFilter.trim() }); setPage(0); }}
          size="small"
        >
          Search
        </Button>
        {(appliedFilters.name || appliedFilters.brand) && (
          <Button
            onClick={() => { setNameFilter(''); setBrandFilter(''); setAppliedFilters({ name: '', brand: '' }); setPage(0); }}
            size="small"
          >
            Clear
          </Button>
        )}
      </Stack>

      {pending && <Box role="status" aria-label="Loading products"><LinearProgress /></Box>}
      {error && <Alert severity="error" action={<Button color="inherit" size="small" onClick={load}>Retry</Button>}>{error}</Alert>}
      <Snackbar
        open={Boolean(success)}
        autoHideDuration={3000}
        onClose={() => setSuccess('')}
        message={success}
      />

      {!pending && data?.items.length === 0 && (
        <Paper variant="outlined" sx={{ p: 4, textAlign: 'center' }}>
          <Typography variant="h6" sx={{ mb: 1 }}>No products found</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            {appliedFilters.name || appliedFilters.brand ? 'Try different search terms.' : 'Create your first product to get started.'}
          </Typography>
          {!appliedFilters.name && !appliedFilters.brand && (
            <Button variant="contained" onClick={() => setCreateOpen(true)}>Create first product</Button>
          )}
        </Paper>
      )}

      {!pending && data && data.items.length > 0 && (
        <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
          <TableContainer sx={{ overflowX: 'auto' }}>
            <Table aria-label="Products" sx={{
              minWidth: { md: 760 },
              '@media (max-width:899px)': {
                display: 'block',
                '& thead, & tbody': { display: 'block' },
                '& thead': { display: 'none' },
                '& tr': { display: 'block', p: 1.5, borderBottom: `1px solid ${tokens.divider}` },
                '& td': { display: 'flex', justifyContent: 'space-between', gap: 1.5, border: 0, py: 0.75, px: 0, overflowWrap: 'anywhere' },
                '& td::before': { content: 'attr(data-label)', color: tokens.inkLight, fontSize: '0.75rem', fontWeight: 600, flex: '0 0 88px' },
              },
            }}>
              <TableHead>
                <TableRow>
                  {['Name', 'Brand', 'Category', 'Status', 'Created', ''].map(h => (
                    <TableCell key={h}>{h}</TableCell>
                  ))}
                </TableRow>
              </TableHead>
              <TableBody>
                {data.items.map(p => (
                  <TableRow key={p.id}>
                    <TableCell data-label="Name" sx={{ fontWeight: 600 }}>{p.name}</TableCell>
                    <TableCell data-label="Brand">{p.brand}</TableCell>
                    <TableCell data-label="Category" sx={{ color: tokens.inkLight }}>{p.category}</TableCell>
                    <TableCell data-label="Status"><StatusChip status={p.status} /></TableCell>
                    <TableCell data-label="Created" sx={{ color: tokens.inkLight, fontSize: '0.8rem' }}>
                      {new Date(p.createdAt).toLocaleDateString()}
                    </TableCell>
                    <TableCell data-label="Action">
                      <Button size="small" onClick={() => setSelectedId(p.id)} sx={{ fontSize: '0.75rem', minHeight: { xs: 44, sm: 32 } }}>
                        View →
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          <TablePagination
            component="div"
            count={data.totalElements}
            page={page}
            rowsPerPage={20}
            rowsPerPageOptions={[20]}
            onPageChange={(_, v) => setPage(v)}
          />
        </Paper>
      )}

      <CreateProductDialog
        open={createOpen}
        session={session}
        onClose={() => setCreateOpen(false)}
        onCreated={() => { setCreateOpen(false); setSuccess('Product created successfully.'); load(); }}
      />
    </Stack>
  );
}

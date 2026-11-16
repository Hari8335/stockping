import { useEffect, useState, type FormEvent } from 'react';
import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle,
  LinearProgress, MenuItem, Paper, Stack, Table, TableBody, TableCell,
  TableContainer, TableHead, TablePagination, TableRow, TextField, Typography,
} from '@mui/material';
import { tokens } from '../theme';
import { api, ApiError, maskedPhone, type Session, type Waitlist, type WaitlistPage } from '../api';
import { useVariantSummaries, variantDetails, variantLabel } from '../variantSummaries';

const statuses = ['WAITING', 'NOTIFICATION_QUEUED', 'NOTIFIED', 'FULFILLED', 'EXPIRED', 'CANCELLED', 'OPTED_OUT'];

const statusColors: Record<string, string> = {
  WAITING: tokens.amber,
  NOTIFICATION_QUEUED: tokens.teal,
  NOTIFIED: tokens.teal,
  FULFILLED: tokens.inkLight,
  EXPIRED: tokens.inkLight,
  CANCELLED: tokens.inkLight,
  OPTED_OUT: tokens.red,
};

function StatusBadge({ status }: { status: string }) {
  const color = statusColors[status] ?? tokens.inkLight;
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
        letterSpacing: '0.04em',
        whiteSpace: 'nowrap',
      }}
    >
      {status.replaceAll('_', ' ')}
    </Box>
  );
}

// ── Register Waitlist Dialog ──────────────────────────────────────────────────

function RegisterDialog({ open, session, onClose, onCreated }: {
  open: boolean; session: Session; onClose: () => void; onCreated: () => void;
}) {
  const [phone, setPhone] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [variantId, setVariantId] = useState('');
  const [consent, setConsent] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');

  function reset() { setPhone(''); setDisplayName(''); setVariantId(''); setConsent(false); setError(''); }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!phone.trim() || !variantId.trim()) { setError('Phone number and Variant ID are required.'); return; }
    if (!consent) { setError('Explicit customer consent is required to register a waitlist entry.'); return; }
    setPending(true); setError('');
    try {
      await api<Waitlist>('/api/waitlists', session.accessToken, {
        method: 'POST',
        body: JSON.stringify({
          customerPhone: phone.trim(),
          displayName: displayName.trim() || undefined,
          variantId: variantId.trim(),
          consent: true,
        }),
      });
      reset();
      onCreated();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Unable to register waitlist entry.');
    } finally { setPending(false); }
  }

  return (
    <Dialog open={open} onClose={() => { if (!pending) { reset(); onClose(); } }} maxWidth="sm" fullWidth>
      <DialogTitle>Register waitlist entry</DialogTitle>
      <DialogContent>
        <Stack component="form" id="register-waitlist-form" spacing={2.5} onSubmit={submit} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <Alert severity="info" sx={{ fontSize: '0.8rem' }}>
            Explicit customer consent is required. Only register customers who have confirmed they wish to receive WhatsApp restock notifications.
          </Alert>
          <TextField
            label="Customer phone (WhatsApp number)"
            placeholder="+94771234567"
            required
            value={phone}
            onChange={e => setPhone(e.target.value)}
            disabled={pending}
            fullWidth
          />
          <TextField
            label="Customer display name (optional)"
            value={displayName}
            onChange={e => setDisplayName(e.target.value)}
            disabled={pending}
            fullWidth
          />
          <TextField
            label="Variant ID"
            placeholder="UUID of the product variant"
            required
            value={variantId}
            onChange={e => setVariantId(e.target.value)}
            disabled={pending}
            fullWidth
          />
          <Box
            sx={{
              p: 2,
              border: `1px solid ${consent ? tokens.teal : tokens.divider}`,
              borderRadius: 1,
              bgcolor: consent ? tokens.tealBg : 'transparent',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              gap: 1.5,
            }}
            onClick={() => setConsent(v => !v)}
            role="checkbox"
            aria-checked={consent}
            aria-label="Customer consent confirmed"
            tabIndex={0}
            onKeyDown={e => e.key === ' ' && setConsent(v => !v)}
          >
            <Box
              sx={{
                width: 18,
                height: 18,
                border: `2px solid ${consent ? tokens.teal : tokens.divider}`,
                borderRadius: 0.5,
                bgcolor: consent ? tokens.teal : 'transparent',
                flexShrink: 0,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: '#fff',
                fontSize: '0.75rem',
              }}
            >
              {consent && '✓'}
            </Box>
            <Typography sx={{ fontSize: '0.875rem', userSelect: 'none' }}>
              I confirm the customer has explicitly consented to receive WhatsApp restock notifications for this product.
            </Typography>
          </Box>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={() => { reset(); onClose(); }} disabled={pending}>Cancel</Button>
        <Button type="submit" form="register-waitlist-form" variant="contained" disabled={pending}>
          {pending ? 'Registering…' : 'Register'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ── Main Waitlist Page ────────────────────────────────────────────────────────

export default function WaitlistPage({ session, onExpired }: { session: Session; onExpired: () => void }) {
  const [status, setStatus] = useState('');
  const [variantInput, setVariantInput] = useState('');
  const [variant, setVariant] = useState('');
  const [page, setPage] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const [data, setData] = useState<WaitlistPage | null>(null);
  const [count, setCount] = useState<number | null>(null);
  const [pending, setPending] = useState(true);
  const [error, setError] = useState('');
  const [selected, setSelected] = useState<Waitlist | null>(null);
  const [saving, setSaving] = useState(false);
  const [registerOpen, setRegisterOpen] = useState(false);
  const summaries = useVariantSummaries(data?.items.map(item => item.variantId) ?? [], session.accessToken, onExpired, refresh);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    setPending(true); setError('');
    const q = new URLSearchParams({ page: String(page), size: '25' });
    if (status) q.set('status', status);
    if (variant) q.set('variantId', variant);
    Promise.all([
      api<WaitlistPage>(`/api/waitlists?${q}`, session.accessToken, { signal: controller.signal }),
      api<{ totalActive: number }>('/api/analytics/demand', session.accessToken, { signal: controller.signal }),
    ]).then(([result, summary]) => {
      if (active) { setData(result); setCount(summary.totalActive); }
    }).catch(failure => {
      if (!active) return;
      if (failure instanceof ApiError && failure.status === 401) onExpired();
      else setError(failure instanceof Error ? failure.message : 'Unable to load waitlists.');
    }).finally(() => { if (active) setPending(false); });
    return () => { active = false; controller.abort(); };
  }, [session.accessToken, status, variant, page, refresh]);

  async function optOut() {
    if (!selected) return;
    setSaving(true);
    try {
      await api(`/api/customers/${selected.customerId}/opt-out`, session.accessToken, { method: 'POST' });
      setSelected(null); setRefresh(v => v + 1);
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) onExpired();
      else { setSelected(null); setError(failure instanceof Error ? failure.message : 'Unable to record opt-out.'); }
    } finally { setSaving(false); }
  }

  return (
    <Stack spacing={3}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: 2 }}>
        <Box>
          <Typography component="h1" variant="h5">Waitlists</Typography>
          {count !== null && (
            <Typography variant="body2" color="text.secondary">
              {count} active request{count !== 1 ? 's' : ''} across your store
            </Typography>
          )}
        </Box>
        <Button variant="outlined" onClick={() => setRegisterOpen(true)} size="small">
          Register customer
        </Button>
      </Box>

      <Typography variant="body2" color="text.secondary">
        Request status records what happened to that waitlist entry. Current consent shows the customer's latest preference and may differ from an older request status.
      </Typography>

      {/* Filters */}
      <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, gap: 1.5, flexWrap: 'wrap' }}>
        <TextField
          select label="Status" value={status}
          onChange={e => { setStatus(e.target.value); setPage(0); }}
          sx={{ minWidth: 180 }} size="small"
        >
          <MenuItem value="">All statuses</MenuItem>
          {statuses.map(s => <MenuItem key={s} value={s}>{s.replaceAll('_', ' ')}</MenuItem>)}
        </TextField>
        <TextField
          label="Variant ID" value={variantInput}
          onChange={e => setVariantInput(e.target.value)}
          onKeyDown={e => e.key === 'Enter' && (setVariant(variantInput.trim()), setPage(0))}
          size="small" sx={{ flex: 1 }}
          placeholder="Filter by variant UUID"
          helperText="Optional exact variant ID filter. Product details appear in results."
        />
        <Button
          variant="outlined" size="small"
          onClick={() => { setVariant(variantInput.trim()); setPage(0); setRefresh(v => v + 1); }}
        >
          Apply
        </Button>
        <Button size="small" onClick={() => setRefresh(v => v + 1)} disabled={pending}>Refresh</Button>
        {(status || variant) && (
          <Button size="small" onClick={() => { setStatus(''); setVariantInput(''); setVariant(''); setPage(0); }}>
            Clear filters
          </Button>
        )}
      </Box>

      {pending && <Box role="status" aria-label="Loading waitlists"><LinearProgress /></Box>}
      {error && (
        <Alert severity="error" action={<Button color="inherit" size="small" onClick={() => setRefresh(v => v + 1)}>Retry</Button>}>
          {error}
        </Alert>
      )}

      {!pending && data?.items.length === 0 && (
        <Paper variant="outlined" sx={{ p: 4, textAlign: 'center' }}>
          <Typography component="h2" variant="h6" sx={{ mb: 1 }}>No waitlist requests</Typography>
          <Typography variant="body2" color="text.secondary">
            {status || variant ? 'No requests match the current filters.' : 'Customer waitlist requests will appear here.'}
          </Typography>
        </Paper>
      )}

      {!pending && data && data.items.length > 0 && (
        <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
          <TableContainer sx={{ overflowX: 'auto' }}>
            <Table aria-label="Customer waitlists" size="small" sx={{
              minWidth: { md: 960 },
              '@media (max-width:899px)': {
                display: 'block',
                '& thead': { display: 'none' },
                '& tbody': { display: 'block' },
                '& tr': { display: 'block', p: 1.5, borderBottom: `1px solid ${tokens.divider}` },
                '& td': { display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 1.5, border: 0, py: 0.75, px: 0, overflowWrap: 'anywhere' },
                '& td::before': { content: 'attr(data-label)', color: tokens.inkLight, fontSize: '0.75rem', fontWeight: 600, flex: '0 0 96px' },
              },
            }}>
              <TableHead>
                <TableRow>
                  {['Customer', 'Product', 'Request status', 'Current consent', 'Created', 'Action'].map(h => (
                    <TableCell key={h}>{h}</TableCell>
                  ))}
                </TableRow>
              </TableHead>
              <TableBody>
                {data.items.map(item => (
                  <TableRow key={item.id}>
                    <TableCell data-label="Customer">
                      <Typography sx={{ fontWeight: 600, fontSize: '0.875rem' }}>
                        {item.displayName || 'Customer'}
                      </Typography>
                      <Typography variant="body2" sx={{ fontFamily: 'monospace', fontSize: '0.78rem' }}>
                        {maskedPhone(item.customerPhone)}
                      </Typography>
                    </TableCell>
                    <TableCell data-label="Product" sx={{ minWidth: { md: 180 }, maxWidth: 260, overflowWrap: 'anywhere' }}>
                      <Box>
                        <Typography sx={{ fontSize: '0.875rem', fontWeight: 600 }}>
                          {variantLabel(summaries.items[item.variantId], summaries.loading, summaries.error)}
                        </Typography>
                        <Typography variant="body2">{variantDetails(summaries.items[item.variantId])}</Typography>
                      </Box>
                    </TableCell>
                    <TableCell data-label="Request"><StatusBadge status={item.status} /></TableCell>
                    <TableCell data-label="Consent">
                      <Box
                        sx={{
                          display: 'inline-block',
                          px: 0.75,
                          py: 0.2,
                          bgcolor: item.consentStatus === 'OPT_IN' ? tokens.tealBg : item.consentStatus === 'OPT_OUT' ? tokens.redBg : tokens.parchment,
                          color: item.consentStatus === 'OPT_IN' ? tokens.teal : item.consentStatus === 'OPT_OUT' ? tokens.red : tokens.inkLight,
                          borderRadius: 0.5,
                          fontSize: '0.7rem',
                          fontWeight: 600,
                        }}
                      >
                        {item.consentStatus === 'OPT_IN' ? 'Opted in' : item.consentStatus === 'OPT_OUT' ? 'Opted out' : 'Not recorded'}
                      </Box>
                    </TableCell>
                    <TableCell data-label="Created" sx={{ color: tokens.inkLight, fontSize: '0.8rem' }}>
                      {new Date(item.createdAt).toLocaleString()}
                    </TableCell>
                    <TableCell data-label="Action">
                      <Button
                        size="small"
                        disabled={item.consentStatus === 'OPT_OUT'}
                        onClick={() => setSelected(item)}
                        aria-label={`Opt out ${maskedPhone(item.customerPhone)}`}
                        color="error"
                        sx={{ fontSize: '0.75rem', minHeight: { xs: 44, sm: 32 } }}
                      >
                        Opt out
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
            rowsPerPage={25}
            rowsPerPageOptions={[25]}
            onPageChange={(_, v) => setPage(v)}
          />
        </Paper>
      )}

      {/* Opt-out confirmation */}
      <Dialog open={selected !== null} onClose={() => { if (!saving) setSelected(null); }}>
        <DialogTitle>Record customer opt-out?</DialogTitle>
        <DialogContent>
          <Typography>
            This records{' '}
            <strong>{selected?.displayName || maskedPhone(selected?.customerPhone ?? '')}</strong>'s
            opt-out and ends all their active waitlists for your shop.
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            The customer will no longer receive WhatsApp restock notifications from your store.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setSelected(null)} disabled={saving}>Cancel</Button>
          <Button onClick={optOut} disabled={saving} color="error" variant="contained">
            {saving ? 'Saving…' : 'Confirm opt-out'}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Register dialog */}
      <RegisterDialog
        open={registerOpen}
        session={session}
        onClose={() => setRegisterOpen(false)}
        onCreated={() => { setRegisterOpen(false); setRefresh(v => v + 1); }}
      />
    </Stack>
  );
}

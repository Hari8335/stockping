import { useEffect, useState } from 'react';
import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, LinearProgress, MenuItem,
  Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TablePagination, TableRow, TextField, Typography } from '@mui/material';
import { api, ApiError, maskedPhone, type Session, type Waitlist, type WaitlistPage } from './api';

const statuses = ['WAITING', 'NOTIFICATION_QUEUED', 'NOTIFIED', 'FULFILLED', 'EXPIRED', 'CANCELLED', 'OPTED_OUT'];

export default function WaitlistScreen({ session, onExpired }: { session: Session; onExpired: () => void }) {
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

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    setPending(true); setError(''); setData(null); setCount(null);
    const query = new URLSearchParams({ page: String(page), size: '25' });
    if (status) query.set('status', status);
    if (variant) query.set('variantId', variant);
    Promise.all([
      api<WaitlistPage>(`/api/waitlists?${query}`, session.accessToken, { signal: controller.signal }),
      api<{ totalActive: number }>('/api/analytics/demand', session.accessToken, { signal: controller.signal }),
    ]).then(([result, summary]) => {
      if (active) { setData(result); setCount(summary.totalActive); }
    }).catch(failure => {
      if (!active) return;
      if (failure instanceof ApiError && failure.status === 401) onExpired();
      else setError(failure instanceof Error ? failure.message : 'Unable to load waitlists.');
    }).finally(() => { if (active) setPending(false); });
    return () => { active = false; controller.abort(); };
    // This screen is mounted for one session; filter changes and refresh trigger reads.
  }, [session.accessToken, status, variant, page, refresh]);

  async function optOut() {
    if (!selected) return;
    setSaving(true);
    try {
      await api(`/api/customers/${selected.customerId}/opt-out`, session.accessToken, { method: 'POST' });
      setSelected(null); setRefresh(value => value + 1);
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) onExpired();
      else { setSelected(null); setError(failure instanceof Error ? failure.message : 'Unable to record opt-out.'); }
    } finally { setSaving(false); }
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Typography component="h2" variant="h5">Waitlist</Typography>
        {count !== null && <Typography color="text.secondary">{count} active requests</Typography>}
      </Box>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <TextField select label="Status" value={status} sx={{ minWidth: 200 }} onChange={event => { setStatus(event.target.value); setPage(0); }}>
          <MenuItem value="">All statuses</MenuItem>
          {statuses.map(value => <MenuItem key={value} value={value}>{value.replaceAll('_', ' ')}</MenuItem>)}
        </TextField>
        <TextField label="Variant ID" value={variantInput} onChange={event => setVariantInput(event.target.value)} sx={{ flex: 1 }} />
        <Button onClick={() => { setVariant(variantInput.trim()); setPage(0); setRefresh(value => value + 1); }}>Apply filters</Button>
        <Button onClick={() => setRefresh(value => value + 1)} disabled={pending}>Refresh</Button>
      </Stack>
      {pending && <Box role="status" aria-label="Loading waitlists"><LinearProgress /><Typography sx={{ mt: 1 }}>Loading waitlists…</Typography></Box>}
      {error && <Alert severity="error" action={<Button color="inherit" onClick={() => setRefresh(value => value + 1)}>Retry</Button>}>{error}</Alert>}
      {!pending && data?.items.length === 0 && <Paper variant="outlined" sx={{ p: 4 }}>
        <Typography component="h3" variant="h6">No waitlist requests</Typography>
        <Typography color="text.secondary">Requests matching these filters will appear here.</Typography>
      </Paper>}
      {!pending && data && data.items.length > 0 && <Paper variant="outlined">
        <TableContainer>
          <Table aria-label="Customer waitlists">
            <TableHead><TableRow>
              {['Customer', 'Variant', 'Status', 'Consent', 'Created', 'Action'].map(label => <TableCell key={label}>{label}</TableCell>)}
            </TableRow></TableHead>
            <TableBody>{data.items.map(item => <TableRow key={item.id}>
              <TableCell>{item.displayName || 'Customer'}<Typography variant="body2" color="text.secondary">{maskedPhone(item.customerPhone)}</Typography></TableCell>
              <TableCell sx={{ overflowWrap: 'anywhere', maxWidth: 220 }}>{item.variantId}</TableCell>
              <TableCell><Chip size="small" label={item.status.replaceAll('_', ' ')} /></TableCell>
              <TableCell>{item.consentStatus === 'OPT_IN' ? 'Opted in' : item.consentStatus === 'OPT_OUT' ? 'Opted out' : 'Not recorded'}</TableCell>
              <TableCell>{new Date(item.createdAt).toLocaleString()}</TableCell>
              <TableCell><Button disabled={item.consentStatus === 'OPT_OUT'} onClick={() => setSelected(item)}
                aria-label={`Opt out ${maskedPhone(item.customerPhone)}`}>Opt out</Button></TableCell>
            </TableRow>)}</TableBody>
          </Table>
        </TableContainer>
      </Paper>}
      {!pending && data && data.totalElements > 0 && <TablePagination component="div" count={data.totalElements} page={page} rowsPerPage={25}
        rowsPerPageOptions={[25]} onPageChange={(_, value) => setPage(value)} />}
      <Dialog open={selected !== null} onClose={() => { if (!saving) setSelected(null); }}>
        <DialogTitle>Record customer opt-out?</DialogTitle>
        <DialogContent>This records the customer’s opt-out and ends all their active waitlists for your shop.</DialogContent>
        <DialogActions>
          <Button onClick={() => setSelected(null)} disabled={saving}>Cancel</Button>
          <Button onClick={optOut} disabled={saving} color="error">{saving ? 'Saving…' : 'Confirm opt-out'}</Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

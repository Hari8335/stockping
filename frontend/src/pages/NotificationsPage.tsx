import { useEffect, useState } from 'react';
import {
  Alert, Box, Button, LinearProgress, Paper, Stack, Table, TableBody,
  TableCell, TableContainer, TableHead, TableRow, Typography,
} from '@mui/material';
import { tokens } from '../theme';
import { api, ApiError, type Session } from '../api';
import { useVariantSummaries, variantDetails, variantLabel } from '../variantSummaries';

interface NotificationJob {
  id: string;
  availability_event_id: string;
  variant_id: string;
  waitlist_id: string;
  status: string;
  reason: string | null;
  provider_message_id: string | null;
  created_at: string;
  authorized_at: string | null;
  completed_at: string | null;
}

const statusColors: Record<string, string> = {
  PENDING: tokens.amber,
  AUTHORIZED: tokens.teal,
  SENT: tokens.teal,
  DELIVERED: tokens.teal,
  FAILED: tokens.red,
  SKIPPED: tokens.inkLight,
  ACCEPTED: tokens.teal,
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
      }}
    >
      {status}
    </Box>
  );
}

function fmt(dt: string | null) {
  if (!dt) return '—';
  return new Date(dt).toLocaleString();
}

export default function NotificationsPage({ session, onExpired }: { session: Session; onExpired: () => void }) {
  const [jobs, setJobs] = useState<NotificationJob[] | null>(null);
  const [pending, setPending] = useState(true);
  const [error, setError] = useState('');
  const summaries = useVariantSummaries(jobs?.map(job => job.variant_id) ?? [], session.accessToken, onExpired);

  function load() {
    setPending(true); setError('');
    api<NotificationJob[]>('/api/notifications', session.accessToken)
      .then(data => setJobs(data))
      .catch(failure => {
        if (failure instanceof ApiError && failure.status === 401) onExpired();
        else setError(failure instanceof Error ? failure.message : 'Unable to load notifications.');
      })
      .finally(() => setPending(false));
  }

  useEffect(() => { load(); }, [session.accessToken]);

  return (
    <Stack spacing={3}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: 2 }}>
        <Box>
          <Typography component="h1" variant="h5">Notifications</Typography>
          <Typography variant="body2" color="text.secondary">
            Restock notification job history (most recent 100).
          </Typography>
        </Box>
        <Button size="small" variant="outlined" onClick={load} disabled={pending}>Refresh</Button>
      </Box>

      <Alert severity="info" sx={{ fontSize: '0.8rem' }}>
        QUEUED awaits processing; ACCEPTED means Meta accepted the send request. Provider SENT, DELIVERED, and READ receipts are separate. This API does not expose those receipts, so provider delivery is unreported.
      </Alert>

      {pending && <Box role="status" aria-label="Loading notifications"><LinearProgress /></Box>}
      {error && <Alert severity="error" action={<Button color="inherit" size="small" onClick={load}>Retry</Button>}>{error}</Alert>}

      {!pending && jobs?.length === 0 && (
        <Paper variant="outlined" sx={{ p: 4, textAlign: 'center' }}>
          <Typography variant="h6" sx={{ mb: 1 }}>No notifications yet</Typography>
          <Typography variant="body2" color="text.secondary">
            Restock notification jobs will appear here when inventory updates trigger them.
            Customers must have an active waitlist entry and have opted in to receive notifications.
          </Typography>
        </Paper>
      )}

      {!pending && jobs && jobs.length > 0 && (
        <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
          <TableContainer>
            <Table aria-label="Notification history" size="small">
              <TableHead>
                <TableRow>
                  {['Product', 'Waitlist ID', 'Dispatch status', 'Provider delivery', 'Created', 'Authorized', 'Completed', 'Failure reason'].map(h => (
                    <TableCell key={h}>{h}</TableCell>
                  ))}
                </TableRow>
              </TableHead>
              <TableBody>
                {jobs.map(job => (
                  <TableRow key={job.id}>
                    <TableCell>
                      <Typography sx={{ fontSize: '0.875rem', fontWeight: 600 }}>
                        {variantLabel(summaries.items[job.variant_id], summaries.loading, summaries.error)}
                      </Typography>
                      <Typography variant="body2">{variantDetails(summaries.items[job.variant_id])}</Typography>
                    </TableCell>
                    <TableCell>
                      <Typography sx={{ fontSize: '0.75rem', fontFamily: 'monospace', color: tokens.inkLight }}>
                        {String(job.waitlist_id).substring(0, 8)}…
                      </Typography>
                    </TableCell>
                    <TableCell><StatusBadge status={job.status} /></TableCell>
                    <TableCell sx={{ fontSize: '0.8rem', color: tokens.inkLight }}>
                      Unreported
                    </TableCell>
                    <TableCell sx={{ fontSize: '0.8rem', whiteSpace: 'nowrap', color: tokens.inkLight }}>
                      {fmt(job.created_at)}
                    </TableCell>
                    <TableCell sx={{ fontSize: '0.8rem', whiteSpace: 'nowrap', color: tokens.inkLight }}>
                      {fmt(job.authorized_at)}
                    </TableCell>
                    <TableCell sx={{ fontSize: '0.8rem', whiteSpace: 'nowrap', color: tokens.inkLight }}>
                      {fmt(job.completed_at)}
                    </TableCell>
                    <TableCell sx={{ color: tokens.red, fontSize: '0.8rem', maxWidth: 200, overflowWrap: 'anywhere' }}>
                      {job.reason || '—'}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </Paper>
      )}
    </Stack>
  );
}

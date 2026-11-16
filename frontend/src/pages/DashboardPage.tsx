import { useEffect, useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Alert, Box, Button, LinearProgress, Paper, Stack, Typography } from '@mui/material';
import { tokens } from '../theme';
import { api, ApiError, type Session } from '../api';
import { useVariantSummaries, variantDetails, variantLabel } from '../variantSummaries';

interface DashboardProps { session: Session; onExpired: () => void }

interface DemandSummary {
  totalActive: number;
  totalWaiting: number;
  variants: Array<{ variantId: string; requestCount: number; waitingCount: number; activeCount: number }>;
}

function StatCard({ label, value, sub, accent }: { label: string; value: string | number; sub?: string; accent?: string }) {
  return (
    <Box
      sx={{
        p: 2.5,
        border: `1px solid ${tokens.divider}`,
        borderRadius: 1,
        bgcolor: tokens.white,
        borderTop: `3px solid ${accent ?? tokens.teal}`,
      }}
    >
      <Typography variant="subtitle2" sx={{ mb: 1 }}>{label}</Typography>
      <Typography sx={{ fontSize: '1.75rem', fontWeight: 700, color: tokens.ink, lineHeight: 1 }}>
        {value}
      </Typography>
      {sub && <Typography variant="body2" sx={{ mt: 0.5 }}>{sub}</Typography>}
    </Box>
  );
}

export default function DashboardPage({ session, onExpired }: DashboardProps) {
  const [demand, setDemand] = useState<DemandSummary | null>(null);
  const [pending, setPending] = useState(true);
  const [error, setError] = useState('');
  const topVariants = [...(demand?.variants ?? [])].sort((a, b) => b.requestCount - a.requestCount || a.variantId.localeCompare(b.variantId)).slice(0, 8);
  const summaries = useVariantSummaries(topVariants.map(v => v.variantId), session.accessToken, onExpired);

  useEffect(() => {
    let active = true;
    setPending(true); setError('');
    api<DemandSummary>('/api/analytics/demand', session.accessToken)
      .then(data => { if (active) setDemand(data); })
      .catch(failure => {
        if (!active) return;
        if (failure instanceof ApiError && failure.status === 401) onExpired();
        else setError(failure instanceof Error ? failure.message : 'Unable to load dashboard data.');
      })
      .finally(() => { if (active) setPending(false); });
    return () => { active = false; };
  }, [session.accessToken]);

  return (
    <Stack spacing={3}>
      <Box>
        <Typography component="h1" variant="h5">Dashboard</Typography>
        <Typography variant="body2" color="text.secondary">
          Demand and waitlist summary for your store.
        </Typography>
      </Box>

      {pending && <Box role="status" aria-label="Loading dashboard"><LinearProgress /></Box>}
      {error && <Alert severity="error" action={<Button color="inherit" size="small" onClick={() => window.location.reload()}>Retry</Button>}>{error}</Alert>}

      {demand && (
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr 1fr', md: '1fr 1fr 1fr 1fr' }, gap: 2 }}>
          <StatCard label="Active waitlist requests" value={demand.totalActive} sub="Requests in active states" accent={tokens.teal} />
          <StatCard label="Waiting requests" value={demand.totalWaiting} sub="Requests currently awaiting restock" accent={tokens.amber} />
          <StatCard label="Variants with requests" value={demand.variants.length} sub="Includes historical requests" accent={tokens.inkMid} />
          <StatCard
            label="Top demand"
            value={topVariants.length > 0 ? topVariants[0].requestCount : '—'}
            sub={demand.variants.length > 0 ? 'All-time requests on top variant' : 'No demand data yet'}
            accent={tokens.red}
          />
        </Box>
      )}

      {demand && demand.variants.length > 0 && (
        <Paper variant="outlined" sx={{ p: 0, overflow: 'hidden' }}>
          <Box sx={{ px: 2.5, py: 2, borderBottom: `1px solid ${tokens.divider}` }}>
            <Typography variant="subtitle2">Top variants by demand</Typography>
          </Box>
          <Stack divider={<Box sx={{ borderBottom: `1px solid ${tokens.divider}` }} />}>
            {topVariants.map(v => (
              <Stack
                key={v.variantId}
                direction="row"
                sx={{ px: 2.5, py: 1.5, alignItems: 'center', '&:hover': { bgcolor: tokens.parchment } }}
              >
                <Box sx={{ flex: 1, minWidth: 0 }}>
                  <Typography sx={{ fontSize: '0.875rem', fontWeight: 600, overflowWrap: 'anywhere' }}>
                    {variantLabel(summaries.items[v.variantId], summaries.loading, summaries.error)}
                  </Typography>
                  <Typography variant="body2" sx={{ color: tokens.inkLight, overflowWrap: 'anywhere' }}>
                    {variantDetails(summaries.items[v.variantId])}
                  </Typography>
                </Box>
                <Stack direction="row" spacing={2} sx={{ flexShrink: 0, ml: 2 }}>
                  <Box sx={{ textAlign: 'right' }}>
                    <Typography sx={{ fontSize: '0.75rem', color: tokens.inkLight }}>Requests</Typography>
                    <Typography sx={{ fontWeight: 600, fontSize: '0.875rem' }}>{v.requestCount}</Typography>
                  </Box>
                  <Box sx={{ textAlign: 'right' }}>
                    <Typography sx={{ fontSize: '0.75rem', color: tokens.inkLight }}>Waiting</Typography>
                    <Typography sx={{ fontWeight: 600, fontSize: '0.875rem', color: tokens.amber }}>{v.waitingCount}</Typography>
                  </Box>
                </Stack>
              </Stack>
            ))}
          </Stack>
        </Paper>
      )}

      {/* Quick actions */}
      <Paper variant="outlined" sx={{ p: 2.5 }}>
        <Typography variant="subtitle2" sx={{ mb: 2 }}>Quick actions</Typography>
        <Stack direction="row" spacing={1.5} sx={{ flexWrap: 'wrap' }}>
          <Button variant="outlined" component={RouterLink} to="/app/products" size="small">
            Browse products
          </Button>
          <Button variant="outlined" component={RouterLink} to="/app/waitlists" size="small">
            View waitlists
          </Button>
          <Button variant="outlined" component={RouterLink} to="/app/notifications" size="small">
            Notification history
          </Button>
          <Button variant="outlined" component={RouterLink} to="/app/ai" size="small">
            Test AI assistant
          </Button>
        </Stack>
      </Paper>
    </Stack>
  );
}

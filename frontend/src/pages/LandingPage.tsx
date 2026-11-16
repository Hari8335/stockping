import { Link as RouterLink } from 'react-router-dom';
import { Box, Button, Container, Stack, Typography, Link, Paper } from '@mui/material';
import { tokens } from '../theme';
import { StockPingWordmark } from '../components/Logo';

// Operational Feature Cards — Concise, scannable, and grounded in real merchant capabilities
const features = [
  {
    category: 'INVENTORY PRECISION',
    title: 'Variant-Level Stock Architecture',
    desc: 'Track demand down to the exact size, color, or model SKU so shoppers get alerts for the right item.',
    highlight: 'SKU & attribute granularity',
    icon: (
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
        <polyline points="3.27 6.96 12 12.01 20.73 6.96" />
        <line x1="12" y1="22.08" x2="12" y2="12" />
      </svg>
    ),
  },
  {
    category: 'COMPLIANCE & PRIVACY',
    title: 'Customer Consent Management',
    desc: 'Record customer opt-in before a WhatsApp restock notification is queued.',
    highlight: 'Explicit opt-in records',
    icon: (
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
        <path d="m9 12 2 2 4-4" />
      </svg>
    ),
  },
  {
    category: 'RETAIL INTELLIGENCE',
    title: 'Actionable Demand Signals',
    desc: 'Quantify unmet customer interest per SKU to prioritize vendor purchase orders and restocking.',
    highlight: 'Variant waitlist demand data',
    icon: (
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <line x1="18" y1="20" x2="18" y2="10" />
        <line x1="12" y1="20" x2="12" y2="4" />
        <line x1="6" y1="20" x2="6" y2="14" />
      </svg>
    ),
  },
  {
    category: 'DISPATCH AUTOMATION',
    title: 'Automated WhatsApp Restock Alerts',
    desc: 'Process eligible restock notifications after a variant changes from out of stock to available.',
    highlight: 'Event-driven alert queue',
    icon: (
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z" />
      </svg>
    ),
  },
  {
    category: 'OPERATIONAL AI',
    title: 'AI-Assisted Customer Inquiries',
    desc: 'Parse unstructured customer messages and suggest matching catalog variants for merchant confirmation.',
    highlight: 'Human-in-the-loop matching',
    icon: (
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
      </svg>
    ),
  },
  {
    category: 'OPERATIONAL GOVERNANCE',
    title: 'Merchant Control & Dispatch Safety',
    desc: 'Review notification job history and manage product variants and stock quantities in the merchant console.',
    highlight: 'Merchant workflow visibility',
    icon: (
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="3" />
        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" />
      </svg>
    ),
  },
];

// Workflow Steps — 5 exact operational steps, shortened to one clear sentence each
const steps = [
  {
    step: '01',
    phase: 'CATALOG CONFIGURATION',
    title: 'Manage catalog & stock',
    desc: 'Add products and define specific variants with distinct SKUs, sizes, colors, and opening inventory levels.',
    meta: 'SKU-level indexing',
  },
  {
    step: '02',
    phase: 'WAITLIST CAPTURE',
    title: 'Capture customer interest',
    desc: 'Record shopper requests against the exact out-of-stock variant in seconds when items sell out.',
    meta: 'Fast waitlist entry',
  },
  {
    step: '03',
    phase: 'CUSTOMER CONSENT',
    title: 'Record explicit consent',
    desc: 'Record explicit customer opt-in before a WhatsApp notification can be queued.',
    meta: 'Recorded customer consent',
  },
  {
    step: '04',
    phase: 'INVENTORY UPDATE',
    title: 'Restock the product',
    desc: 'Update variant stock quantities upon shipment arrival to trigger automatic restock detection.',
    meta: 'Automatic restock detection',
  },
  {
    step: '05',
    phase: 'CUSTOMER NOTIFICATION',
    title: 'Process WhatsApp alerts',
    desc: 'Queue authorized WhatsApp restock alerts for eligible waiting shoppers.',
    meta: 'Event-driven alert queue',
  },
];

// Grounded operational feature highlights
const featureHighlights = [
  {
    highlight: 'SKU-Level',
    label: 'Variant Granularity',
    sub: 'Track waitlist demand for exact sizes, colors, and models rather than generic product pages',
  },
  {
    highlight: 'Opt-In First',
    label: 'Recorded Consent',
    sub: 'Record customer opt-in before queueing WhatsApp notification jobs',
  },
  {
    highlight: 'Automated',
    label: 'Event-Driven Restock',
    sub: 'A zero-to-positive stock update starts notification processing for eligible waitlist requests',
  },
  {
    highlight: 'WhatsApp',
    label: 'Direct Channel Alerts',
    sub: 'Reach shoppers directly via WhatsApp messaging with straightforward customer opt-out handling',
  },
];

export default function LandingPage() {
  return (
    <Box sx={{ bgcolor: tokens.offWhite, minHeight: '100vh', color: tokens.ink }}>
      {/* Sticky Navigation */}
      <Box
        component="nav"
        sx={{
          bgcolor: 'rgba(255, 255, 255, 0.95)',
          backdropFilter: 'blur(8px)',
          borderBottom: `1px solid ${tokens.divider}`,
          position: 'sticky',
          top: 0,
          zIndex: 100,
        }}
      >
        <Container maxWidth="lg">
          <Box sx={{ py: 2, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <Link component={RouterLink} to="/" underline="none" sx={{ display: 'flex', alignItems: 'center' }}>
              <StockPingWordmark height={30} color={tokens.ink} accentColor={tokens.teal} />
            </Link>

            <Box sx={{ display: { xs: 'none', md: 'flex' }, alignItems: 'center', gap: 4 }}>
              <Link
                href="#features"
                underline="none"
                sx={{
                  color: tokens.inkLight,
                  fontSize: '0.875rem',
                  fontWeight: 600,
                  transition: 'color 0.15s',
                  '&:hover': { color: tokens.ink },
                }}
              >
                Features
              </Link>
              <Link
                href="#how-it-works"
                underline="none"
                sx={{
                  color: tokens.inkLight,
                  fontSize: '0.875rem',
                  fontWeight: 600,
                  transition: 'color 0.15s',
                  '&:hover': { color: tokens.ink },
                }}
              >
                How it works
              </Link>
              <Link
                href="#why-stockping"
                underline="none"
                sx={{
                  color: tokens.inkLight,
                  fontSize: '0.875rem',
                  fontWeight: 600,
                  transition: 'color 0.15s',
                  '&:hover': { color: tokens.ink },
                }}
              >
                Why StockPing
              </Link>
            </Box>

            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
              <Button
                variant="contained"
                component={RouterLink}
                to="/login"
                size="small"
                sx={{
                  px: 2.4,
                  py: 0.9,
                  fontSize: '0.85rem',
                  fontWeight: 600,
                }}
              >
                Merchant sign in
              </Button>
            </Box>
          </Box>
        </Container>
      </Box>

      {/* Hero Section */}
      <Box
        component="section"
        sx={{
          bgcolor: tokens.white,
          borderBottom: `1px solid ${tokens.divider}`,
          pt: { xs: 6, md: 9 },
          pb: { xs: 8, md: 10 },
          position: 'relative',
        }}
      >
        <Container maxWidth="lg">
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', lg: '1fr 1.05fr' },
              gap: { xs: 5, lg: 6 },
              alignItems: 'center',
            }}
          >
            {/* Hero Left: Value Proposition */}
            <Box>
              {/* Product Category Badge */}
              <Box
                sx={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 1,
                  px: 1.5,
                  py: 0.5,
                  bgcolor: tokens.parchment,
                  border: `1px solid ${tokens.divider}`,
                  borderRadius: 4,
                  mb: 2.5,
                }}
              >
                <Box sx={{ width: 6, height: 6, borderRadius: '50%', bgcolor: tokens.teal }} />
                <Typography
                  sx={{
                    fontSize: '0.72rem',
                    fontWeight: 700,
                    letterSpacing: '0.08em',
                    textTransform: 'uppercase',
                    color: tokens.inkMid,
                  }}
                >
                  Retail Inventory Demand & Restock Platform
                </Typography>
              </Box>

              {/* Main Headline */}
              <Typography
                component="h1"
                sx={{
                  fontSize: { xs: '2.5rem', sm: '3.1rem', md: '3.65rem' },
                  fontWeight: 800,
                  letterSpacing: '-0.04em',
                  color: tokens.ink,
                  lineHeight: 1.08,
                  mb: 2.5,
                }}
              >
                Out of stock shouldn't mean{' '}
                <Box
                  component="span"
                  sx={{
                    color: tokens.teal,
                    display: { xs: 'inline', sm: 'inline-block' },
                    position: 'relative',
                    '&::after': {
                      content: '""',
                      position: 'absolute',
                      left: 0,
                      bottom: { xs: 2, sm: 4 },
                      width: '100%',
                      height: { xs: 4, sm: 6 },
                      bgcolor: tokens.tealBg,
                      zIndex: -1,
                      borderRadius: 1,
                    },
                  }}
                >
                  out of touch.
                </Box>
              </Typography>

              {/* Supporting Copy */}
              <Typography
                sx={{
                  fontSize: { xs: '1.05rem', md: '1.18rem' },
                  color: tokens.inkLight,
                  lineHeight: 1.65,
                  mb: 4,
                  maxWidth: 520,
                }}
              >
                Capture customer interest when items sell out. StockPing records waitlists
                with explicit consent, tracks demand per variant SKU, and automatically
                starts restock notification processing when an out-of-stock variant becomes available and notifications are enabled.
              </Typography>

              {/* Primary & Secondary CTAs */}
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mb: 4 }}>
                <Button
                  variant="contained"
                  component={RouterLink}
                  to="/login"
                  size="large"
                  sx={{
                    px: 3.5,
                    py: 1.35,
                    fontSize: '0.95rem',
                    fontWeight: 650,
                  }}
                >
                  Merchant sign in
                </Button>
                <Button
                  variant="outlined"
                  href="#how-it-works"
                  size="large"
                  sx={{
                    px: 3.5,
                    py: 1.35,
                    fontSize: '0.95rem',
                    fontWeight: 650,
                  }}
                >
                  See how it works
                </Button>
              </Stack>

              {/* Feature Highlights */}
              <Box
                sx={{
                  pt: 2.5,
                  borderTop: `1px solid ${tokens.divider}`,
                  display: 'flex',
                  flexWrap: 'wrap',
                  gap: { xs: 2, sm: 3 },
                  alignItems: 'center',
                }}
              >
                <Typography sx={{ fontSize: '0.8rem', color: tokens.inkLight, fontWeight: 500 }}>
                  • WhatsApp Cloud API messaging
                </Typography>
                <Typography sx={{ fontSize: '0.8rem', color: tokens.inkLight, fontWeight: 500 }}>
                  • Explicit customer consent
                </Typography>
                <Typography sx={{ fontSize: '0.8rem', color: tokens.inkLight, fontWeight: 500 }}>
                  • SKU & variant granularity
                </Typography>
              </Box>
            </Box>

            {/* Hero Right: Product Visual / Mockup */}
            <Box sx={{ display: 'flex', justifyContent: 'center', width: '100%' }}>
              <Paper
                variant="outlined"
                sx={{
                  width: '100%',
                  maxWidth: { xs: '100%', lg: 580 },
                  borderRadius: 2.5,
                  overflow: 'hidden',
                  borderColor: tokens.divider,
                  bgcolor: tokens.white,
                  boxShadow: '0 16px 40px -10px rgba(15, 23, 42, 0.08), 0 4px 14px -2px rgba(15, 23, 42, 0.04)',
                }}
              >
                {/* Mockup Window Titlebar */}
                <Box
                  sx={{
                    px: 2,
                    py: 1.25,
                    bgcolor: tokens.ink,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                  }}
                >
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                    <Box sx={{ width: 9, height: 9, borderRadius: '50%', bgcolor: '#ef4444', opacity: 0.8 }} />
                    <Box sx={{ width: 9, height: 9, borderRadius: '50%', bgcolor: '#f59e0b', opacity: 0.8 }} />
                    <Box sx={{ width: 9, height: 9, borderRadius: '50%', bgcolor: '#10b981', opacity: 0.8 }} />
                    <Typography
                      sx={{
                        ml: 1,
                        fontSize: '0.72rem',
                        fontWeight: 600,
                        color: 'rgba(255,255,255,0.7)',
                        letterSpacing: '0.02em',
                      }}
                    >
                      StockPing Console — Sample Store #014
                    </Typography>
                  </Box>

                  {/* Explicit Demo Label */}
                  <Box
                    sx={{
                      px: 1,
                      py: 0.25,
                      bgcolor: 'rgba(20, 184, 166, 0.15)',
                      border: '1px solid rgba(20, 184, 166, 0.3)',
                      borderRadius: 1,
                    }}
                  >
                    <Typography
                      sx={{
                        fontSize: '0.65rem',
                        fontWeight: 700,
                        letterSpacing: '0.06em',
                        color: '#2dd4bf',
                      }}
                    >
                      DEMO DATA • SAMPLE PREVIEW
                    </Typography>
                  </Box>
                </Box>

                {/* Mockup Interior: Demand & Restock Dashboard */}
                <Box sx={{ p: { xs: 2, sm: 2.5 }, bgcolor: tokens.offWhite }}>
                  {/* Top Stats Overview */}
                  <Box
                    sx={{
                      display: 'grid',
                      gridTemplateColumns: '1fr 1fr',
                      gap: 1.5,
                      mb: 2,
                    }}
                  >
                    <Box
                      sx={{
                        p: 1.5,
                        bgcolor: tokens.white,
                        borderRadius: 1.5,
                        border: `1px solid ${tokens.divider}`,
                      }}
                    >
                      <Typography sx={{ fontSize: '0.68rem', fontWeight: 700, color: tokens.inkLight, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                        Waitlist Requests (Demo)
                      </Typography>
                      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1, mt: 0.5 }}>
                        <Typography sx={{ fontSize: '1.4rem', fontWeight: 800, color: tokens.ink, lineHeight: 1 }}>
                          38
                        </Typography>
                        <Typography sx={{ fontSize: '0.72rem', color: tokens.teal, fontWeight: 600 }}>
                          across 4 SKUs (sample)
                        </Typography>
                      </Box>
                    </Box>

                    <Box
                      sx={{
                        p: 1.5,
                        bgcolor: tokens.white,
                        borderRadius: 1.5,
                        border: `1px solid ${tokens.divider}`,
                      }}
                    >
                      <Typography sx={{ fontSize: '0.68rem', fontWeight: 700, color: tokens.inkLight, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                        Opt-In Consent (Demo)
                      </Typography>
                      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1, mt: 0.5 }}>
                        <Typography sx={{ fontSize: '1.4rem', fontWeight: 800, color: tokens.teal, lineHeight: 1 }}>
                          100%
                        </Typography>
                        <Typography sx={{ fontSize: '0.72rem', color: tokens.inkLight }}>
                          explicit opt-in (sample)
                        </Typography>
                      </Box>
                    </Box>
                  </Box>

                  {/* SKU Inventory List Preview */}
                  <Typography
                    sx={{
                      fontSize: '0.68rem',
                      fontWeight: 700,
                      color: tokens.inkMid,
                      letterSpacing: '0.08em',
                      textTransform: 'uppercase',
                      mb: 1,
                    }}
                  >
                    Catalog Status & Waitlist Queues (Demo Preview)
                  </Typography>

                  <Stack spacing={1.25} sx={{ mb: 2 }}>
                    {/* Item 1: Restocked with queued alert */}
                    <Box
                      sx={{
                        p: 1.5,
                        borderRadius: 1.5,
                        bgcolor: tokens.white,
                        border: `1px solid ${tokens.tealLight}`,
                        borderLeft: `4px solid ${tokens.teal}`,
                      }}
                    >
                      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 0.5, flexWrap: 'wrap', gap: 0.5 }}>
                        <Typography sx={{ fontSize: '0.84rem', fontWeight: 700, color: tokens.ink }}>
                          Sony WH-1000XM5 Headphones (Sample)
                        </Typography>
                        <Box sx={{ px: 0.85, py: 0.25, bgcolor: tokens.tealBg, borderRadius: 0.75 }}>
                          <Typography sx={{ fontSize: '0.66rem', fontWeight: 700, color: tokens.teal }}>
                            RESTOCKED (+12 UNITS) (Demo)
                          </Typography>
                        </Box>
                      </Box>
                      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '0.72rem', flexWrap: 'wrap', gap: 0.5 }}>
                        <Typography sx={{ color: tokens.inkLight, fontFamily: 'monospace', fontSize: '0.72rem' }}>
                          SKU: WHXM5-SLV-01 • Silver (Demo)
                        </Typography>
                        <Typography sx={{ fontWeight: 600, color: tokens.teal }}>
                          14 restock notification jobs queued (sample)
                        </Typography>
                      </Box>
                    </Box>

                    {/* Item 2: High Demand Out of Stock */}
                    <Box
                      sx={{
                        p: 1.5,
                        borderRadius: 1.5,
                        bgcolor: tokens.white,
                        border: `1px solid ${tokens.divider}`,
                      }}
                    >
                      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 0.5, flexWrap: 'wrap', gap: 0.5 }}>
                        <Typography sx={{ fontSize: '0.84rem', fontWeight: 700, color: tokens.ink }}>
                          Baratza Encore ESP Grinder (Sample)
                        </Typography>
                        <Box sx={{ px: 0.85, py: 0.25, bgcolor: tokens.redBg, borderRadius: 0.75 }}>
                          <Typography sx={{ fontSize: '0.66rem', fontWeight: 700, color: tokens.red }}>
                            OUT OF STOCK (Demo)
                          </Typography>
                        </Box>
                      </Box>
                      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '0.72rem', flexWrap: 'wrap', gap: 0.5 }}>
                        <Typography sx={{ color: tokens.inkLight, fontFamily: 'monospace', fontSize: '0.72rem' }}>
                          SKU: BAR-ESP-BLK • Matte Black (Demo)
                        </Typography>
                        <Typography sx={{ fontWeight: 700, color: tokens.amber }}>
                          16 waiting shoppers (sample)
                        </Typography>
                      </Box>
                    </Box>
                  </Stack>

                  {/* Example WhatsApp Notification Preview */}
                  <Box
                    sx={{
                      p: 1.75,
                      bgcolor: tokens.parchment,
                      borderRadius: 1.5,
                      border: `1px solid ${tokens.divider}`,
                    }}
                  >
                    <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
                      <Typography sx={{ fontSize: '0.72rem', fontWeight: 700, color: tokens.ink }}>
                        WhatsApp Restock Notification (Demo)
                      </Typography>
                      <Typography sx={{ fontSize: '0.65rem', color: tokens.inkLight, fontWeight: 600 }}>
                        EXAMPLE RESTOCK FLOW (SAMPLE)
                      </Typography>
                    </Box>

                    {/* WhatsApp Chat Bubble */}
                    <Box
                      sx={{
                        p: 1.5,
                        bgcolor: '#e7f8e8',
                        borderRadius: 2,
                        border: '1px solid #c8eecb',
                        fontSize: '0.78rem',
                        lineHeight: 1.5,
                        color: '#064e3b',
                      }}
                    >
                      <Typography sx={{ fontSize: '0.76rem', fontWeight: 700, color: '#065f46', mb: 0.5 }}>
                        Artisan Electronics (Sample Store)
                      </Typography>
                      <Typography sx={{ fontSize: '0.75rem', mb: 0.75 }}>
                        "Hi Sarah, you requested an alert when the <strong>Sony WH-1000XM5 (Silver)</strong> returned to stock.
                        It is now back in stock at Artisan Electronics:
                      </Typography>
                      <Typography sx={{ fontSize: '0.72rem', color: tokens.teal, fontWeight: 600, mb: 0.5 }}>
                        store.example.com/products/whxm5-slv
                      </Typography>
                      <Typography sx={{ fontSize: '0.68rem', color: '#047857' }}>
                        Reply STOP to opt out at any time.
                      </Typography>
                      <Box sx={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', mt: 0.5 }}>
                        <Typography sx={{ fontSize: '0.62rem', color: '#047857' }}>
                          10:42 AM • Notification queued for dispatch (Sample)
                        </Typography>
                      </Box>
                    </Box>
                  </Box>
                </Box>
              </Paper>
            </Box>
          </Box>
        </Container>
      </Box>

      {/* Feature Highlights Row */}
      <Box component="section" sx={{ py: 5, bgcolor: tokens.offWhite, borderBottom: `1px solid ${tokens.divider}` }}>
        <Container maxWidth="lg">
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr 1fr', md: '1fr 1fr 1fr 1fr' },
              gap: { xs: 3, md: 4 },
            }}
          >
            {featureHighlights.map(m => (
              <Box key={m.label} sx={{ borderLeft: `2px solid ${tokens.teal}`, pl: 2.5 }}>
                <Typography sx={{ fontSize: { xs: '1.25rem', md: '1.45rem' }, fontWeight: 800, color: tokens.teal, lineHeight: 1.1, mb: 0.5 }}>
                  {m.highlight}
                </Typography>
                <Typography sx={{ fontSize: '0.85rem', fontWeight: 700, color: tokens.inkMid, mb: 0.5 }}>
                  {m.label}
                </Typography>
                <Typography sx={{ fontSize: '0.78rem', color: tokens.inkLight, lineHeight: 1.45 }}>
                  {m.sub}
                </Typography>
              </Box>
            ))}
          </Box>
        </Container>
      </Box>

      {/* How It Works Section — Shortened 1-sentence descriptions with clean visual process layout */}
      <Box
        component="section"
        id="how-it-works"
        sx={{
          py: { xs: 7, md: 10 },
          bgcolor: tokens.white,
          borderBottom: `1px solid ${tokens.divider}`,
        }}
      >
        <Container maxWidth="lg">
          <Box sx={{ mb: 6, maxWidth: 640 }}>
            <Box
              sx={{
                display: 'inline-flex',
                alignItems: 'center',
                px: 1.25,
                py: 0.4,
                bgcolor: tokens.tealBg,
                borderRadius: 1,
                mb: 1.5,
              }}
            >
              <Typography
                variant="subtitle2"
                sx={{
                  color: tokens.teal,
                  fontWeight: 700,
                  fontSize: '0.72rem',
                  letterSpacing: '0.08em',
                }}
              >
                HOW IT WORKS
              </Typography>
            </Box>
            <Typography
              component="h2"
              sx={{
                fontSize: { xs: '1.75rem', md: '2.3rem' },
                fontWeight: 800,
                letterSpacing: '-0.03em',
                color: tokens.ink,
                mb: 1.5,
              }}
            >
              A complete, consent-first restock workflow
            </Typography>
            <Typography sx={{ fontSize: '0.98rem', color: tokens.inkLight, lineHeight: 1.6 }}>
              From initial out-of-stock customer inquiry to restock notification in five clear operational steps.
            </Typography>
          </Box>

          {/* 5-Step Connected Process Layout */}
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr', lg: 'repeat(5, 1fr)' },
              gap: 2.5,
            }}
          >
            {steps.map(step => (
              <Paper
                key={step.step}
                variant="outlined"
                sx={{
                  p: 2.75,
                  borderRadius: 2,
                  bgcolor: tokens.offWhite,
                  borderColor: tokens.divider,
                  display: 'flex',
                  flexDirection: 'column',
                  justifyContent: 'space-between',
                  transition: 'border-color 0.2s, box-shadow 0.2s',
                  '&:hover': {
                    borderColor: tokens.teal,
                    boxShadow: '0 4px 16px rgba(15, 23, 42, 0.04)',
                  },
                }}
              >
                <Box>
                  <Box
                    sx={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      mb: 2,
                    }}
                  >
                    <Typography
                      sx={{
                        fontSize: '1.25rem',
                        fontWeight: 800,
                        color: tokens.teal,
                        fontFamily: 'monospace',
                        lineHeight: 1,
                      }}
                    >
                      {step.step}
                    </Typography>
                    <Box
                      sx={{
                        width: 8,
                        height: 8,
                        borderRadius: '50%',
                        bgcolor: tokens.tealLight,
                      }}
                    />
                  </Box>
                  <Typography
                    sx={{
                      fontSize: '0.65rem',
                      fontWeight: 700,
                      letterSpacing: '0.08em',
                      textTransform: 'uppercase',
                      color: tokens.inkLight,
                      mb: 0.5,
                    }}
                  >
                    {step.phase}
                  </Typography>
                  <Typography sx={{ fontSize: '1rem', fontWeight: 700, color: tokens.ink, mb: 1, lineHeight: 1.3 }}>
                    {step.title}
                  </Typography>
                  <Typography sx={{ color: tokens.inkLight, fontSize: '0.84rem', lineHeight: 1.55 }}>
                    {step.desc}
                  </Typography>
                </Box>

                <Box
                  sx={{
                    mt: 2.5,
                    pt: 1.75,
                    borderTop: `1px solid ${tokens.divider}`,
                  }}
                >
                  <Typography sx={{ fontSize: '0.72rem', fontWeight: 600, color: tokens.teal }}>
                    {step.meta}
                  </Typography>
                </Box>
              </Paper>
            ))}
          </Box>
        </Container>
      </Box>

      {/* Features Section — Concise heading, one clear sentence description, meaningful visual detail */}
      <Box
        component="section"
        id="features"
        sx={{
          py: { xs: 7, md: 10 },
          bgcolor: tokens.offWhite,
          borderBottom: `1px solid ${tokens.divider}`,
        }}
      >
        <Container maxWidth="lg">
          <Box sx={{ mb: 6, maxWidth: 640 }}>
            <Box
              sx={{
                display: 'inline-flex',
                alignItems: 'center',
                px: 1.25,
                py: 0.4,
                bgcolor: tokens.parchment,
                border: `1px solid ${tokens.divider}`,
                borderRadius: 1,
                mb: 1.5,
              }}
            >
              <Typography
                variant="subtitle2"
                sx={{
                  color: tokens.inkMid,
                  fontWeight: 700,
                  fontSize: '0.72rem',
                  letterSpacing: '0.08em',
                }}
              >
                FEATURES
              </Typography>
            </Box>
            <Typography
              component="h2"
              sx={{
                fontSize: { xs: '1.75rem', md: '2.3rem' },
                fontWeight: 800,
                letterSpacing: '-0.03em',
                color: tokens.ink,
                mb: 1.5,
              }}
            >
              Engineered specifically for store owners
            </Typography>
            <Typography sx={{ fontSize: '0.98rem', color: tokens.inkLight, lineHeight: 1.6 }}>
              Operational tools designed to eliminate retail stockout friction and keep customers informed.
            </Typography>
          </Box>

          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr', lg: '1fr 1fr 1fr' },
              gap: 3,
            }}
          >
            {features.map(f => (
              <Paper
                key={f.title}
                variant="outlined"
                sx={{
                  p: 3,
                  borderRadius: 2,
                  bgcolor: tokens.white,
                  borderColor: tokens.divider,
                  display: 'flex',
                  flexDirection: 'column',
                  justifyContent: 'space-between',
                  transition: 'border-color 0.2s, box-shadow 0.2s',
                  '&:hover': {
                    borderColor: tokens.teal,
                    boxShadow: '0 8px 24px -4px rgba(15, 23, 42, 0.05)',
                  },
                }}
              >
                <Box>
                  <Box
                    sx={{
                      width: 40,
                      height: 40,
                      borderRadius: 1.5,
                      bgcolor: tokens.tealBg,
                      color: tokens.teal,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      mb: 2,
                    }}
                  >
                    {f.icon}
                  </Box>
                  <Typography
                    sx={{
                      fontSize: '0.68rem',
                      fontWeight: 700,
                      letterSpacing: '0.08em',
                      textTransform: 'uppercase',
                      color: tokens.teal,
                      mb: 0.5,
                    }}
                  >
                    {f.category}
                  </Typography>
                  <Typography sx={{ fontSize: '1.05rem', fontWeight: 700, color: tokens.ink, mb: 1, lineHeight: 1.35 }}>
                    {f.title}
                  </Typography>
                  <Typography sx={{ color: tokens.inkLight, fontSize: '0.86rem', lineHeight: 1.6, mb: 2.5 }}>
                    {f.desc}
                  </Typography>
                </Box>
                <Box
                  sx={{
                    pt: 1.75,
                    borderTop: `1px solid ${tokens.divider}`,
                    display: 'flex',
                    alignItems: 'center',
                    gap: 1,
                  }}
                >
                  <Box sx={{ width: 6, height: 6, borderRadius: '50%', bgcolor: tokens.teal }} />
                  <Typography sx={{ fontSize: '0.72rem', fontWeight: 650, color: tokens.inkMid }}>
                    {f.highlight}
                  </Typography>
                </Box>
              </Paper>
            ))}
          </Box>
        </Container>
      </Box>

      {/* Why StockPing / Workflow Comparison Section */}
      <Box
        component="section"
        id="why-stockping"
        sx={{
          py: { xs: 7, md: 10 },
          bgcolor: tokens.white,
          borderBottom: `1px solid ${tokens.divider}`,
        }}
      >
        <Container maxWidth="lg">
          <Box sx={{ textAlign: 'center', mb: 6, maxWidth: 680, mx: 'auto' }}>
            <Typography
              variant="subtitle2"
              sx={{
                color: tokens.teal,
                fontWeight: 700,
                fontSize: '0.72rem',
                letterSpacing: '0.08em',
                mb: 1.5,
              }}
            >
              WORKFLOW COMPARISON
            </Typography>
            <Typography
              component="h2"
              sx={{
                fontSize: { xs: '1.75rem', md: '2.3rem' },
                fontWeight: 800,
                letterSpacing: '-0.03em',
                color: tokens.ink,
                mb: 1.5,
              }}
            >
              Manual tracking vs. Structured waitlist automation
            </Typography>
            <Typography sx={{ fontSize: '0.98rem', color: tokens.inkLight, lineHeight: 1.6 }}>
              Compare ad-hoc customer notes and generic back-in-stock alerts with StockPing's consent-backed, variant-specific workflow.
            </Typography>
          </Box>

          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' },
              gap: 3.5,
              maxWidth: 960,
              mx: 'auto',
            }}
          >
            {/* Ad-Hoc Column */}
            <Paper
              variant="outlined"
              sx={{
                p: { xs: 3, md: 3.5 },
                borderRadius: 2,
                bgcolor: tokens.offWhite,
                borderColor: tokens.divider,
              }}
            >
              <Typography sx={{ fontSize: '0.72rem', fontWeight: 700, color: tokens.red, textTransform: 'uppercase', letterSpacing: '0.08em', mb: 0.75 }}>
                Ad-Hoc / Manual Handling
              </Typography>
              <Typography sx={{ fontSize: '1.15rem', fontWeight: 700, color: tokens.ink, mb: 2 }}>
                Scattered notes & untracked demand
              </Typography>
              <Stack spacing={1.75}>
                <Box sx={{ display: 'flex', gap: 1.5 }}>
                  <Typography sx={{ color: tokens.red, fontWeight: 700 }}>•</Typography>
                  <Typography sx={{ fontSize: '0.86rem', color: tokens.inkLight }}>
                    Customer requests written on paper notes or forgotten during staff shift changes.
                  </Typography>
                </Box>
                <Box sx={{ display: 'flex', gap: 1.5 }}>
                  <Typography sx={{ color: tokens.red, fontWeight: 700 }}>•</Typography>
                  <Typography sx={{ fontSize: '0.86rem', color: tokens.inkLight }}>
                    Broad product-level notifications lack SKU detail, leading to mismatched inquiries.
                  </Typography>
                </Box>
                <Box sx={{ display: 'flex', gap: 1.5 }}>
                  <Typography sx={{ color: tokens.red, fontWeight: 700 }}>•</Typography>
                  <Typography sx={{ fontSize: '0.86rem', color: tokens.inkLight }}>
                    Customer consent is untracked, creating compliance and privacy risks.
                  </Typography>
                </Box>
                <Box sx={{ display: 'flex', gap: 1.5 }}>
                  <Typography sx={{ color: tokens.red, fontWeight: 700 }}>•</Typography>
                  <Typography sx={{ fontSize: '0.86rem', color: tokens.inkLight }}>
                    Inventory reorder quantities rely on manual guesswork rather than recorded waitlists.
                  </Typography>
                </Box>
              </Stack>
            </Paper>

            {/* StockPing Column */}
            <Paper
              variant="outlined"
              sx={{
                p: { xs: 3, md: 3.5 },
                borderRadius: 2,
                bgcolor: tokens.white,
                borderColor: tokens.teal,
                borderWidth: 1.5,
                boxShadow: '0 8px 24px -4px rgba(15, 118, 110, 0.08)',
              }}
            >
              <Typography sx={{ fontSize: '0.72rem', fontWeight: 700, color: tokens.teal, textTransform: 'uppercase', letterSpacing: '0.08em', mb: 0.75 }}>
                StockPing Restock System
              </Typography>
              <Typography sx={{ fontSize: '1.15rem', fontWeight: 700, color: tokens.ink, mb: 2 }}>
                Consent-backed variant notifications
              </Typography>
              <Stack spacing={1.75}>
                <Box sx={{ display: 'flex', gap: 1.5 }}>
                  <Typography sx={{ color: tokens.teal, fontWeight: 700 }}>•</Typography>
                  <Typography sx={{ fontSize: '0.86rem', color: tokens.ink, fontWeight: 500 }}>
                    Store staff record customer waitlist requests in seconds against the exact variant SKU.
                  </Typography>
                </Box>
                <Box sx={{ display: 'flex', gap: 1.5 }}>
                  <Typography sx={{ color: tokens.teal, fontWeight: 700 }}>•</Typography>
                  <Typography sx={{ fontSize: '0.86rem', color: tokens.ink, fontWeight: 500 }}>
                    Exact variant matching ensures shoppers only receive alerts for their requested item.
                  </Typography>
                </Box>
                <Box sx={{ display: 'flex', gap: 1.5 }}>
                  <Typography sx={{ color: tokens.teal, fontWeight: 700 }}>•</Typography>
                  <Typography sx={{ fontSize: '0.86rem', color: tokens.ink, fontWeight: 500 }}>
                    Explicit customer consent is recorded and checked before notifications are queued.
                  </Typography>
                </Box>
                <Box sx={{ display: 'flex', gap: 1.5 }}>
                  <Typography sx={{ color: tokens.teal, fontWeight: 700 }}>•</Typography>
                  <Typography sx={{ fontSize: '0.86rem', color: tokens.ink, fontWeight: 500 }}>
                    Merchants view aggregated waitlist counts per SKU to prioritize vendor reorders.
                  </Typography>
                </Box>
              </Stack>
            </Paper>
          </Box>
        </Container>
      </Box>

      {/* Final CTA — Accurate, natural conclusion without exaggerated claims */}
      <Box
        component="section"
        sx={{
          py: { xs: 9, md: 12 },
          bgcolor: tokens.ink,
          color: tokens.white,
          textAlign: 'center',
          position: 'relative',
        }}
      >
        <Container maxWidth="md">
          <Box
            sx={{
              display: 'inline-flex',
              alignItems: 'center',
              px: 1.5,
              py: 0.5,
              bgcolor: 'rgba(255, 255, 255, 0.1)',
              borderRadius: 4,
              mb: 2.5,
            }}
          >
            <Typography
              sx={{
                fontSize: '0.72rem',
                fontWeight: 700,
                letterSpacing: '0.08em',
                textTransform: 'uppercase',
                color: tokens.tealLight,
              }}
            >
              RETAIL RESTOCK PLATFORM
            </Typography>
          </Box>

          <Typography
            component="h2"
            sx={{
              fontSize: { xs: '1.9rem', sm: '2.4rem', md: '2.95rem' },
              fontWeight: 800,
              letterSpacing: '-0.035em',
              lineHeight: 1.15,
              mb: 2,
              color: tokens.white,
            }}
          >
            When stock returns, keep your customers in the loop.
          </Typography>

          <Typography
            sx={{
              fontSize: { xs: '1rem', md: '1.12rem' },
              color: 'rgba(255, 255, 255, 0.75)',
              lineHeight: 1.65,
              mb: 4.5,
              maxWidth: 580,
              mx: 'auto',
            }}
          >
            Keep track of customer demand and make restock communication simpler with StockPing.
          </Typography>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ justifyContent: 'center', alignItems: 'center', mb: 3.5 }}>
            <Button
              variant="contained"
              component={RouterLink}
              to="/login"
              size="large"
              sx={{
                px: 4,
                py: 1.4,
                bgcolor: tokens.tealLight,
                color: tokens.ink,
                fontWeight: 700,
                fontSize: '0.95rem',
                '&:hover': {
                  bgcolor: '#2dd4bf',
                },
              }}
            >
              Merchant sign in
            </Button>
            <Button
              variant="outlined"
              href="#how-it-works"
              size="large"
              sx={{
                px: 3.5,
                py: 1.4,
                borderColor: 'rgba(255, 255, 255, 0.25)',
                color: tokens.white,
                bgcolor: 'transparent',
                '&:hover': {
                  borderColor: tokens.white,
                  bgcolor: 'rgba(255, 255, 255, 0.05)',
                },
              }}
            >
              See how it works
            </Button>
          </Stack>

          <Typography sx={{ fontSize: '0.76rem', color: 'rgba(255, 255, 255, 0.45)' }}>
            Merchant console access • SKU-level variant architecture • Recorded WhatsApp consent
          </Typography>
        </Container>
      </Box>

      {/* Footer */}
      <Box component="footer" sx={{ bgcolor: '#0b1120', py: 5, px: 3, borderTop: '1px solid rgba(255, 255, 255, 0.1)' }}>
        <Container maxWidth="lg">
          <Box
            sx={{
              display: 'flex',
              flexDirection: { xs: 'column', md: 'row' },
              alignItems: { xs: 'flex-start', md: 'center' },
              justifyContent: 'space-between',
              gap: 4,
              pb: 4,
              borderBottom: '1px solid rgba(255, 255, 255, 0.08)',
            }}
          >
            <Box>
              <StockPingWordmark height={26} color="#ffffff" accentColor="#14b8a6" />
              <Typography sx={{ mt: 1, fontSize: '0.8rem', color: 'rgba(255, 255, 255, 0.55)', maxWidth: 360 }}>
                Inventory waitlists and consent-aware WhatsApp restock notifications for retail storefronts.
              </Typography>
            </Box>

            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 3.5 }}>
              <Link href="#features" underline="none" sx={{ fontSize: '0.85rem', color: 'rgba(255,255,255,0.7)', '&:hover': { color: '#fff' } }}>
                Features
              </Link>
              <Link href="#how-it-works" underline="none" sx={{ fontSize: '0.85rem', color: 'rgba(255,255,255,0.7)', '&:hover': { color: '#fff' } }}>
                How it works
              </Link>
              <Link href="#why-stockping" underline="none" sx={{ fontSize: '0.85rem', color: 'rgba(255,255,255,0.7)', '&:hover': { color: '#fff' } }}>
                Why StockPing
              </Link>
              <Link component={RouterLink} to="/login" underline="none" sx={{ fontSize: '0.85rem', color: '#14b8a6', fontWeight: 600, '&:hover': { color: '#2dd4bf' } }}>
                Merchant sign in
              </Link>
            </Box>
          </Box>

          <Box
            sx={{
              mt: 3,
              display: 'flex',
              flexDirection: { xs: 'column', sm: 'row' },
              justifyContent: 'space-between',
              alignItems: { xs: 'flex-start', sm: 'center' },
              gap: 2,
            }}
          >
            <Typography sx={{ fontSize: '0.72rem', color: 'rgba(255, 255, 255, 0.4)', lineHeight: 1.6 }}>
              StockPing is an independent software platform for retail inventory operations and customer restock notifications.
              WhatsApp is a registered trademark of Meta Platforms, Inc. Not affiliated with or endorsed by Meta Platforms, Inc.
            </Typography>
            <Typography sx={{ fontSize: '0.72rem', color: 'rgba(255, 255, 255, 0.35)', whiteSpace: 'nowrap' }}>
              © {new Date().getFullYear()} StockPing. All rights reserved.
            </Typography>
          </Box>
        </Container>
      </Box>
    </Box>
  );
}

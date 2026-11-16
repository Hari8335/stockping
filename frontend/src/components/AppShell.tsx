import { useState } from 'react';
import { useNavigate, useLocation, Outlet, Link as RouterLink } from 'react-router-dom';
import {
  Box, Drawer, List, ListItemButton, ListItemIcon, ListItemText,
  Typography, IconButton, AppBar, Toolbar, useMediaQuery, useTheme,
  Menu, MenuItem, Avatar, Chip
} from '@mui/material';
import { tokens } from '../theme';
import { StockPingLogo } from './Logo';
import { useAuth } from '../contexts/AuthContext';

const DRAWER_WIDTH = 220;

const navItems = [
  { label: 'Dashboard', path: '/app', icon: 'dashboard', exact: true },
  { label: 'Products', path: '/app/products', icon: 'products' },
  { label: 'Waitlists', path: '/app/waitlists', icon: 'waitlists' },
  { label: 'Notifications', path: '/app/notifications', icon: 'notifications' },
  { label: 'AI Assistant', path: '/app/ai', icon: 'assistant' },
];

function NavIcon({ icon }: { icon: string }) {
  const paths: Record<string, string> = {
    dashboard: 'M4 13h6V4H4v9zm0 7h6v-5H4v5zm10 0h6v-9h-6v9zm0-16v5h6V4h-6z',
    products: 'M4 5h16v14H4V5zm3 3v2h2V8H7zm4 0v2h6V8h-6zm-4 4v2h2v-2H7zm4 0v2h6v-2h-6z',
    waitlists: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm-7 8a7 7 0 0 1 14 0H5z',
    notifications: 'M12 22a2.5 2.5 0 0 0 2.45-2h-4.9A2.5 2.5 0 0 0 12 22zm7-5H5l1.5-2V10a5.5 5.5 0 0 1 11 0v5L19 17z',
    assistant: 'M12 3l2.2 5.8L20 11l-5.8 2.2L12 19l-2.2-5.8L4 11l5.8-2.2L12 3z',
  };
  return (
    <Box component="svg" viewBox="0 0 24 24" aria-hidden="true" focusable="false"
      sx={{ width: 20, height: 20, display: 'block', fill: 'currentColor' }}>
      <path d={paths[icon]} />
    </Box>
  );
}

export default function AppShell() {
  const { session, logout } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const muiTheme = useTheme();
  const isMobile = useMediaQuery(muiTheme.breakpoints.down('md'));
  const [mobileOpen, setMobileOpen] = useState(false);
  const [anchorEl, setAnchorEl] = useState<null | HTMLElement>(null);

  function isActive(path: string, exact = false) {
    if (exact) return location.pathname === path;
    return location.pathname.startsWith(path);
  }

  function handleLogout() {
    setAnchorEl(null);
    logout();
    navigate('/login');
  }

  const drawer = (
    <Box
      sx={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        bgcolor: tokens.ink,
        color: '#fff',
      }}
    >
      {/* Brand */}
      <Box
        sx={{
          px: 2.5,
          py: 2.5,
          display: 'flex',
          alignItems: 'center',
          gap: 1.5,
          borderBottom: `1px solid rgba(255,255,255,0.1)`,
        }}
      >
        <StockPingLogo size={28} color="#fff" accentColor="#14b8a6" />
        <Typography
          sx={{
            fontWeight: 700,
            fontSize: '1.05rem',
            letterSpacing: '-0.02em',
            color: '#fff',
            userSelect: 'none',
          }}
        >
          StockPing
        </Typography>
      </Box>

      {/* Nav items */}
      <Box sx={{ flex: 1, overflowY: 'auto', px: 1.5, pt: 1.5 }}>
        <List disablePadding>
          {navItems.map(item => {
            const active = isActive(item.path, item.exact);
            return (
              <ListItemButton
                key={item.path}
                component={RouterLink}
                to={item.path}
                aria-label={item.label}
                onClick={() => setMobileOpen(false)}
                selected={active}
                aria-current={active ? 'page' : undefined}
                sx={{
                  mb: 0.5,
                  px: 1.5,
                  py: 1,
                  borderRadius: 1,
                  color: active ? '#fff' : 'rgba(255,255,255,0.88)',
                  bgcolor: active ? 'rgba(255,255,255,0.12)' : 'transparent',
                  '&.Mui-selected': {
                    bgcolor: 'rgba(20,184,166,0.18)',
                    color: '#fff',
                    borderLeft: `3px solid #14b8a6`,
                    pl: `${1.5 * 8 - 3}px`,
                  },
                  '&:hover': {
                    bgcolor: 'rgba(255,255,255,0.08)',
                    color: '#fff',
                  },
                  '&:focus-visible': {
                    outline: '2px solid #5eead4',
                    outlineOffset: '-2px',
                  },
                }}
              >
                <ListItemIcon sx={{ minWidth: 32, color: 'inherit' }}>
                  <NavIcon icon={item.icon} />
                </ListItemIcon>
                <ListItemText
                  primary={item.label}
                  slotProps={{
                    primary: {
                      style: {
                        color: 'inherit',
                        fontSize: '0.875rem',
                        fontWeight: active ? 600 : 400,
                      },
                    },
                  }}
                />
              </ListItemButton>
            );
          })}
        </List>
      </Box>

      {/* User section */}
      <Box
        sx={{
          px: 2,
          py: 2,
          borderTop: `1px solid rgba(255,255,255,0.1)`,
        }}
      >
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1.5,
            cursor: 'pointer',
            borderRadius: 1,
            px: 1,
            py: 0.75,
            '&:hover': { bgcolor: 'rgba(255,255,255,0.08)' },
          }}
          onClick={e => setAnchorEl(e.currentTarget as HTMLElement)}
        >
          <Avatar sx={{ width: 28, height: 28, bgcolor: tokens.teal, fontSize: '0.75rem', fontWeight: 700 }}>
            {session?.role?.charAt(0) ?? 'M'}
          </Avatar>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography sx={{ fontSize: '0.8rem', fontWeight: 600, color: '#fff', lineHeight: 1.2 }} noWrap>
              Merchant
            </Typography>
            <Chip
              label={session?.role?.replace('_', ' ') ?? 'Admin'}
              size="small"
              sx={{
                height: 16,
                fontSize: '0.6rem',
                fontWeight: 600,
                bgcolor: 'rgba(255,255,255,0.12)',
                color: 'rgba(255,255,255,0.7)',
                mt: 0.3,
              }}
            />
          </Box>
        </Box>
        <Menu
          anchorEl={anchorEl}
          open={Boolean(anchorEl)}
          onClose={() => setAnchorEl(null)}
          transformOrigin={{ horizontal: 'left', vertical: 'bottom' }}
          anchorOrigin={{ horizontal: 'left', vertical: 'top' }}
        >
          <MenuItem onClick={handleLogout}>Sign out</MenuItem>
        </Menu>
      </Box>
    </Box>
  );

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh', bgcolor: tokens.offWhite }}>
      {/* Sidebar */}
      {isMobile ? (
        <Drawer
          variant="temporary"
          open={mobileOpen}
          onClose={() => setMobileOpen(false)}
          ModalProps={{ keepMounted: true }}
          slotProps={{ paper: { id: 'mobile-navigation', 'aria-label': 'Navigation' } }}
          sx={{
            '& .MuiDrawer-paper': { width: DRAWER_WIDTH, boxSizing: 'border-box', border: 'none' },
          }}
        >
          {drawer}
        </Drawer>
      ) : (
        <Drawer
          variant="permanent"
          sx={{
            width: DRAWER_WIDTH,
            flexShrink: 0,
            '& .MuiDrawer-paper': {
              width: DRAWER_WIDTH,
              boxSizing: 'border-box',
              border: 'none',
              boxShadow: '2px 0 8px rgba(0,0,0,0.06)',
            },
          }}
        >
          {drawer}
        </Drawer>
      )}

      {/* Main content */}
      <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        {/* Mobile top bar */}
        {isMobile && (
          <AppBar
            position="static"
            elevation={0}
            sx={{ bgcolor: tokens.ink, borderBottom: `1px solid rgba(255,255,255,0.1)` }}
          >
            <Toolbar sx={{ gap: 1.5 }}>
              <IconButton
                edge="start"
                color="inherit"
                onClick={() => setMobileOpen(true)}
                aria-label="Open navigation"
                aria-expanded={mobileOpen}
                aria-controls="mobile-navigation"
                sx={{
                  color: '#fff',
                  bgcolor: 'rgba(255,255,255,0.12)',
                  '&:hover': { bgcolor: 'rgba(255,255,255,0.22)', color: '#fff' },
                  '&:focus-visible': { outline: '2px solid #5eead4', outlineOffset: 2 },
                  '&:active': { bgcolor: 'rgba(94,234,212,0.28)', color: '#fff' },
                }}
              >
                <Box component="svg" viewBox="0 0 24 24" aria-hidden="true" sx={{ width: 24, height: 24, stroke: 'currentColor', fill: 'none', strokeWidth: 2.5, strokeLinecap: 'round' }}>
                  <path d="M4 6h16M4 12h16M4 18h16" />
                </Box>
              </IconButton>
              <StockPingLogo size={24} color="#fff" accentColor="#14b8a6" />
              <Typography sx={{ fontWeight: 700, color: '#fff' }}>
                StockPing
              </Typography>
            </Toolbar>
          </AppBar>
        )}

        <Box component="main" sx={{ flex: 1, p: { xs: 2, sm: 3 }, maxWidth: 1200, width: '100%', mx: 'auto' }}>
          <Outlet />
        </Box>
      </Box>
    </Box>
  );
}

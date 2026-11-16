import { createTheme } from '@mui/material/styles';

// StockPing Design Tokens — Editorial & Operational Retail SaaS
export const tokens = {
  ink: '#0f172a',         // primary text, dark shell, obsidian
  inkMid: '#1e293b',      // secondary headings, strong slate
  inkLight: '#475569',    // body text, muted metadata
  inkSubtle: '#64748b',   // tertiary text, captions
  offWhite: '#faf8f5',    // warm architectural linen/paper ground
  parchment: '#f3efe6',   // warm card backgrounds, table headers
  parchmentLight: '#fbf9f4',
  teal: '#0f766e',        // primary brand, restock action, confirmed
  tealDark: '#115e59',    // button hover, deep accents
  tealLight: '#14b8a6',   // signal highlight, active beacons
  tealBg: '#f0fdfa',      // soft teal tint
  tealBorder: '#ccfbf1',
  amber: '#b45309',       // low-stock warnings, waiting count
  amberBg: '#fffbeb',     // amber tint
  amberBorder: '#fef3c7',
  red: '#b91c1c',         // out-of-stock, errors, alerts
  redBg: '#fef2f2',       // red tint
  redBorder: '#fee2e2',
  divider: '#e5e2db',     // crisp architectural hairline border
  dividerDark: '#cbd5e1',
  white: '#ffffff',
  slateDark: '#0b1120',
};

export const theme = createTheme({
  palette: {
    mode: 'light',
    primary: {
      main: tokens.teal,
      dark: tokens.tealDark,
      light: tokens.tealLight,
      contrastText: '#ffffff',
    },
    secondary: {
      main: tokens.ink,
      contrastText: '#ffffff',
    },
    error: {
      main: tokens.red,
      light: tokens.redBg,
    },
    warning: {
      main: tokens.amber,
      light: tokens.amberBg,
    },
    success: {
      main: tokens.teal,
      light: tokens.tealBg,
    },
    text: {
      primary: tokens.ink,
      secondary: tokens.inkLight,
    },
    background: {
      default: tokens.offWhite,
      paper: tokens.white,
    },
    divider: tokens.divider,
  },
  typography: {
    fontFamily: [
      'Inter',
      '-apple-system',
      'BlinkMacSystemFont',
      '"Segoe UI"',
      'Roboto',
      'sans-serif',
    ].join(','),
    h1: {
      fontWeight: 750,
      letterSpacing: '-0.035em',
      color: tokens.ink,
      lineHeight: 1.15,
    },
    h2: {
      fontWeight: 700,
      letterSpacing: '-0.025em',
      color: tokens.ink,
      lineHeight: 1.25,
    },
    h3: {
      fontWeight: 700,
      letterSpacing: '-0.02em',
      color: tokens.ink,
    },
    h4: {
      fontWeight: 650,
      letterSpacing: '-0.015em',
      color: tokens.ink,
    },
    h5: {
      fontWeight: 650,
      letterSpacing: '-0.01em',
      color: tokens.ink,
    },
    h6: {
      fontWeight: 600,
      color: tokens.ink,
    },
    subtitle1: {
      fontWeight: 500,
      color: tokens.inkLight,
      lineHeight: 1.6,
    },
    subtitle2: {
      fontWeight: 700,
      fontSize: '0.72rem',
      letterSpacing: '0.08em',
      textTransform: 'uppercase' as const,
      color: tokens.inkLight,
    },
    body1: {
      color: tokens.ink,
      lineHeight: 1.65,
    },
    body2: {
      color: tokens.inkLight,
      lineHeight: 1.55,
    },
    button: {
      fontWeight: 600,
      letterSpacing: '0.005em',
    },
    caption: {
      color: tokens.inkSubtle,
      fontSize: '0.75rem',
    },
  },
  shape: {
    borderRadius: 6,
  },
  components: {
    MuiButton: {
      defaultProps: {
        disableElevation: true,
      },
      styleOverrides: {
        root: {
          textTransform: 'none',
          fontWeight: 600,
          borderRadius: 6,
          boxShadow: 'none',
          transition: 'all 0.15s ease-in-out',
          '&:hover': {
            boxShadow: 'none',
          },
        },
        contained: {
          backgroundColor: tokens.teal,
          color: '#ffffff',
          '&:hover': {
            backgroundColor: tokens.tealDark,
          },
        },
        outlined: {
          borderColor: tokens.divider,
          color: tokens.ink,
          backgroundColor: tokens.white,
          '&:hover': {
            borderColor: tokens.teal,
            backgroundColor: tokens.tealBg,
          },
        },
      },
    },
    MuiPaper: {
      styleOverrides: {
        root: {
          backgroundImage: 'none',
        },
        outlined: {
          borderColor: tokens.divider,
          boxShadow: '0 1px 3px rgba(15, 23, 42, 0.04), 0 1px 2px rgba(15, 23, 42, 0.02)',
        },
      },
    },
    MuiTableHead: {
      styleOverrides: {
        root: {
          '& .MuiTableCell-root': {
            backgroundColor: tokens.parchment,
            color: tokens.inkLight,
            fontWeight: 700,
            fontSize: '0.72rem',
            letterSpacing: '0.07em',
            textTransform: 'uppercase',
            borderBottom: `2px solid ${tokens.divider}`,
            paddingTop: 11,
            paddingBottom: 11,
          },
        },
      },
    },
    MuiTableCell: {
      styleOverrides: {
        root: {
          borderColor: tokens.divider,
          fontSize: '0.875rem',
        },
      },
    },
    MuiTableRow: {
      styleOverrides: {
        root: {
          '&:hover': {
            backgroundColor: tokens.parchmentLight,
          },
          '&:last-child td': {
            borderBottom: 0,
          },
        },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: {
          fontWeight: 650,
          fontSize: '0.72rem',
          letterSpacing: '0.03em',
          borderRadius: 4,
        },
      },
    },
    MuiTextField: {
      defaultProps: {
        size: 'small',
      },
      styleOverrides: {
        root: {
          '& .MuiOutlinedInput-root': {
            borderRadius: 6,
            backgroundColor: tokens.white,
            '& fieldset': {
              borderColor: tokens.divider,
            },
            '&:hover fieldset': {
              borderColor: tokens.teal,
            },
            '&.Mui-focused fieldset': {
              borderColor: tokens.teal,
              borderWidth: 1.5,
            },
          },
        },
      },
    },
    MuiAlert: {
      styleOverrides: {
        root: {
          borderRadius: 6,
          fontSize: '0.875rem',
          border: `1px solid ${tokens.divider}`,
        },
      },
    },
    MuiLinearProgress: {
      styleOverrides: {
        root: {
          borderRadius: 3,
          height: 3,
          backgroundColor: tokens.parchment,
        },
      },
    },
    MuiDivider: {
      styleOverrides: {
        root: {
          borderColor: tokens.divider,
        },
      },
    },
    MuiListItemButton: {
      styleOverrides: {
        root: {
          borderRadius: 6,
          marginBottom: 3,
        },
      },
    },
  },
});

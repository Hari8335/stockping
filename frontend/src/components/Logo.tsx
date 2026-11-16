// StockPing Brand Identity — Minimal Logo & Wordmark
// Original geometric symbol combining a customer messaging speech bubble silhouette,
// two stacked inventory stock tiles carved in negative space, and a single muted teal restock notification dot.

export interface LogoProps {
  size?: number;
  color?: string;
  accentColor?: string;
  withWordmark?: boolean;
}

export function StockPingLogo({
  size = 32,
  color = '#0f172a',
  accentColor = '#0f766e',
  withWordmark = false,
}: LogoProps) {
  if (withWordmark) {
    return <StockPingWordmark height={size} color={color} accentColor={accentColor} />;
  }

  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-label="StockPing"
      role="img"
    >
      {/* Speech bubble silhouette containing two stacked stock tiles as intentional negative space */}
      <path
        fillRule="evenodd"
        clipRule="evenodd"
        d="M 8 4 H 24 C 26.2 4 28 5.8 28 8 V 18 C 28 20.2 26.2 22 24 22 H 14 L 8 26.8 C 7.35 27.3 6.5 26.85 6.5 26 V 22 C 5.1 22 4 20.2 4 18 V 8 C 4 5.8 5.8 4 8 4 Z M 8.5 8.5 H 18.5 C 19.05 8.5 19.5 8.95 19.5 9.5 V 11.5 C 19.5 12.05 19.05 12.5 18.5 12.5 H 8.5 C 7.95 12.5 7.5 12.05 7.5 11.5 V 9.5 C 7.5 8.95 7.95 8.5 8.5 8.5 Z M 8.5 14.5 H 18.5 C 19.05 14.5 19.5 14.95 19.5 15.5 V 17.5 C 19.5 18.05 19.05 18.5 18.5 18.5 H 8.5 C 7.95 18.5 7.5 18.05 7.5 17.5 V 15.5 C 7.5 14.95 7.95 14.5 8.5 14.5 Z"
        fill={color}
      />
      {/* Restock notification ping dot */}
      <circle cx="23.5" cy="10.5" r="2.25" fill={accentColor} />
    </svg>
  );
}

export function StockPingWordmark({
  height = 28,
  color = '#0f172a',
  accentColor = '#0f766e',
}: {
  height?: number;
  color?: string;
  accentColor?: string;
}) {
  const width = Math.round(height * 4.25);
  return (
    <svg
      width={width}
      height={height}
      viewBox="0 0 136 32"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-label="StockPing"
      role="img"
    >
      {/* Brand Mark */}
      <path
        fillRule="evenodd"
        clipRule="evenodd"
        d="M 8 4 H 24 C 26.2 4 28 5.8 28 8 V 18 C 28 20.2 26.2 22 24 22 H 14 L 8 26.8 C 7.35 27.3 6.5 26.85 6.5 26 V 22 C 5.1 22 4 20.2 4 18 V 8 C 4 5.8 5.8 4 8 4 Z M 8.5 8.5 H 18.5 C 19.05 8.5 19.5 8.95 19.5 9.5 V 11.5 C 19.5 12.05 19.05 12.5 18.5 12.5 H 8.5 C 7.95 12.5 7.5 12.05 7.5 11.5 V 9.5 C 7.5 8.95 7.95 8.5 8.5 8.5 Z M 8.5 14.5 H 18.5 C 19.05 14.5 19.5 14.95 19.5 15.5 V 17.5 C 19.5 18.05 19.05 18.5 18.5 18.5 H 8.5 C 7.95 18.5 7.5 18.05 7.5 17.5 V 15.5 C 7.5 14.95 7.95 14.5 8.5 14.5 Z"
        fill={color}
      />
      <circle cx="23.5" cy="10.5" r="2.25" fill={accentColor} />

      {/* Brand Wordmark */}
      <text
        x="38"
        y="22"
        fontFamily="Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
        fontWeight="700"
        fontSize="19"
        letterSpacing="-0.03em"
        fill={color}
      >
        StockPing
      </text>
    </svg>
  );
}

export default StockPingLogo;

import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { desktopRequest, loadDesktopTheme, type BredTheme } from '../api/desktop';

const STORAGE_KEY = 'bred.react-theme';

const fallbackTheme: BredTheme = {
  appearance: 'MODERN_GLASS',
  dark: true,
  accent: '#5B9CFF',
  accentDark: '#2563EB',
  accentSoft: '#1E2870',
  background: '#060435',
  sidebar: '#1A1C2B',
  header: '#1D2033',
  surface: '#272C42',
  surfaceMuted: '#1D2033',
  text: '#F3F4F6',
  muted: '#A6ABB8',
  border: '#3A405D',
  success: '#218838',
  warning: '#E6A700',
  danger: '#D62828',
  chart1: '#5B9CFF',
  chart2: '#4DE3C2',
  chart3: '#8B7CF6',
  chart4: '#F5B52B',
  chart5: '#66C7F7',
};

function readStoredTheme(): BredTheme | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    return value ? JSON.parse(value) as BredTheme : null;
  } catch {
    return null;
  }
}

function applyTheme(theme: BredTheme): void {
  const root = document.documentElement;
  const values: Record<string, string> = {
    '--page-bg': theme.background,
    '--sidebar': theme.sidebar,
    '--header': theme.header,
    '--surface': theme.surface,
    '--surface-muted': theme.surfaceMuted,
    '--text': theme.text,
    '--muted': theme.muted,
    '--line': theme.border,
    '--border': theme.border,
    '--accent': theme.accent,
    '--accent-dark': theme.accentDark,
    '--accent-soft': theme.accentSoft,
    '--success': theme.success,
    '--warning': theme.warning,
    '--danger': theme.danger,
    '--chart-1': theme.chart1,
    '--chart-2': theme.chart2,
    '--chart-3': theme.chart3,
    '--chart-4': theme.chart4,
    '--chart-5': theme.chart5,
  };
  for (const [name, value] of Object.entries(values)) root.style.setProperty(name, value);
  root.dataset.theme = theme.appearance;
  root.dataset.mode = theme.dark ? 'dark' : 'light';
  root.style.setProperty('color-scheme', theme.dark ? 'dark' : 'light');
  localStorage.setItem(STORAGE_KEY, JSON.stringify(theme));
}

const initialTheme = readStoredTheme() ?? fallbackTheme;
applyTheme(initialTheme);

const ThemeContext = createContext<BredTheme>(initialTheme);

export function BredThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setTheme] = useState<BredTheme>(initialTheme);

  useEffect(() => {
    let active = true;
    const sync = async () => {
      try {
        const nativeTheme = await loadDesktopTheme();
        const next = nativeTheme ?? await desktopRequest<BredTheme>('/api/v1/theme');
        if (!active) return;
        setTheme((current) => {
          if (JSON.stringify(current) === JSON.stringify(next)) return current;
          applyTheme(next);
          return next;
        });
      } catch {
        // Persisted Java theme remains active if the local service is temporarily unavailable.
      }
    };
    void sync();
    const timer = window.setInterval(() => void sync(), 750);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, []);

  const value = useMemo(() => theme, [theme]);
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useBredTheme(): BredTheme {
  return useContext(ThemeContext);
}

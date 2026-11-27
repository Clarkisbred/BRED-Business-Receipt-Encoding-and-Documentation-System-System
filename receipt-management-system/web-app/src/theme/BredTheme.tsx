import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { desktopRequest, loadDesktopTheme, type BredTheme } from '../api/desktop';

const STORAGE_KEY = 'bred.react-theme';
const removedAppearances = new Set(['MODERN_GLASS', 'MIDNIGHT_BLUE', 'CLEAN_LIGHT', 'GRAPHITE']);

const fallbackTheme: BredTheme = {
  appearance: 'DEFAULT',
  dark: false,
  accent: '#1F5FBF',
  accentDark: '#164687',
  accentSoft: '#E8F0FC',
  background: '#F5F6F8',
  sidebar: '#FFFFFF',
  header: '#FFFFFF',
  surface: '#FFFFFF',
  surfaceMuted: '#F5F6F8',
  text: '#202124',
  muted: '#747B87',
  border: '#E1E3E8',
  success: '#218838',
  warning: '#E6A700',
  danger: '#D62828',
  chart1: '#1F5FBF',
  chart2: '#218838',
  chart3: '#2474C7',
  chart4: '#E6A700',
  chart5: '#D62828',
};

function readStoredTheme(): BredTheme | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    if (!value) return null;
    const theme = JSON.parse(value) as BredTheme;
    return removedAppearances.has(theme.appearance) ? null : theme;
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

export interface DesktopBootstrap {
  origin: string;
  token: string;
}

export interface BredTheme {
  appearance: string;
  dark: boolean;
  accent: string;
  accentDark: string;
  accentSoft: string;
  background: string;
  sidebar: string;
  header: string;
  surface: string;
  surfaceMuted: string;
  text: string;
  muted: string;
  border: string;
  success: string;
  warning: string;
  danger: string;
  chart1: string;
  chart2: string;
  chart3: string;
  chart4: string;
  chart5: string;
}

interface DesktopBridge {
  getBootstrap: () => Promise<DesktopBootstrap>;
  getTheme?: () => Promise<unknown>;
}

declare global {
  interface Window {
    bredDesktop?: DesktopBridge;
    bredDesktopReadyCallback?: () => void;
  }
}

export class DesktopApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string,
  ) {
    super(message);
    this.name = 'DesktopApiError';
  }
}

export function desktopBridgeError(): string | null {
  if (typeof window === 'undefined' || typeof window.bredDesktop?.getBootstrap !== 'function') {
    return 'Open this screen from the BRED desktop application to connect to the Java service.';
  }
  return null;
}

export async function loadDesktopTheme(): Promise<BredTheme | null> {
  if (typeof window === 'undefined' || typeof window.bredDesktop?.getTheme !== 'function') {
    return null;
  }
  return await window.bredDesktop.getTheme() as BredTheme;
}

export async function desktopRequest<T>(
  path: string,
  options: { method?: 'GET' | 'POST' | 'DELETE'; body?: Record<string, unknown> } = {},
): Promise<T> {
  const bootstrap = await readBootstrap();
  const method = options.method ?? 'GET';
  const headers = new Headers({
    Authorization: `Bearer ${bootstrap.token}`,
    Accept: 'application/json',
  });
  let body: string | undefined;
  if (options.body) {
    headers.set('Content-Type', 'application/json');
    body = JSON.stringify(options.body);
  }

  const request: RequestInit = {
    method,
    headers,
    mode: 'cors',
    credentials: 'omit',
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
  };
  if (body !== undefined) request.body = body;

  let response: Response;
  try {
    response = await fetch(new URL(path, bootstrap.origin), request);
  } catch {
    throw new DesktopApiError(
      'BRED could not reach its local service. Close and reopen the desktop app.',
      0,
      'connection_failed',
    );
  }

  if (!response.ok) {
    let message = 'BRED could not complete this request.';
    let code = 'request_failed';
    try {
      const error = await response.json() as { code?: unknown; message?: unknown };
      if (typeof error.message === 'string') message = error.message;
      if (typeof error.code === 'string') code = error.code;
    } catch {
      // Keep a useful error when the local service returns a non-JSON response.
    }
    throw new DesktopApiError(message, response.status, code);
  }

  if (response.status === 204) return undefined as T;
  return await response.json() as T;
}

async function readBootstrap(): Promise<DesktopBootstrap> {
  if (typeof window === 'undefined' || typeof window.bredDesktop?.getBootstrap !== 'function') {
    throw new DesktopApiError(
      'Open this screen from the BRED desktop application to connect to the Java service.',
      0,
      'desktop_host_unavailable',
    );
  }

  let bootstrap: DesktopBootstrap;
  try {
    bootstrap = await window.bredDesktop.getBootstrap();
  } catch {
    throw new DesktopApiError(
      'BRED could not connect to its trusted desktop host. Close and reopen the desktop app.',
      0,
      'desktop_bridge_failed',
    );
  }
  if (!bootstrap || typeof bootstrap.origin !== 'string' || typeof bootstrap.token !== 'string') {
    throw new DesktopApiError('The BRED desktop service configuration is invalid.', 0, 'invalid_desktop_host');
  }

  const { origin, token } = bootstrap;
  let parsedOrigin: URL;
  try {
    parsedOrigin = new URL(origin);
  } catch {
    throw new DesktopApiError('The BRED desktop service address is invalid.', 0, 'invalid_desktop_host');
  }
  if (parsedOrigin.protocol !== 'http:' || parsedOrigin.hostname !== '127.0.0.1'
      || !parsedOrigin.port || parsedOrigin.pathname !== '/'
      || parsedOrigin.search || parsedOrigin.hash || parsedOrigin.username || parsedOrigin.password
      || parsedOrigin.origin !== origin
      || !/^[A-Za-z0-9_-]{43}$/.test(token)) {
    throw new DesktopApiError('The BRED desktop service configuration is invalid.', 0, 'invalid_desktop_host');
  }

  return { origin, token };
}

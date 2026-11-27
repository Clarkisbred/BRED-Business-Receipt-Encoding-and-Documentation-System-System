import { afterEach, describe, expect, it, vi } from 'vitest';
import { desktopBridgeError, desktopRequest } from './desktop';

const TOKEN = 'A'.repeat(43);

afterEach(() => {
  delete window.bredDesktop;
  vi.unstubAllGlobals();
});

describe('trusted desktop API client', () => {
  it('requires an injected loopback renderer bootstrap', async () => {
    expect(desktopBridgeError()).toMatch(/Open this screen from the BRED desktop application/);
    await expect(desktopRequest('/api/v1/session')).rejects.toMatchObject({
      code: 'desktop_host_unavailable',
    });
  });

  it('uses the injected token without sending cookies or caching responses', async () => {
    window.bredDesktop = {
      getBootstrap: async () => ({ origin: 'http://127.0.0.1:43210', token: TOKEN }),
    };
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ user: { id: 'user-id' } }),
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(desktopRequest<{ user: { id: string } }>('/api/v1/session'))
      .resolves.toEqual({ user: { id: 'user-id' } });

    const [url, options] = fetchMock.mock.calls[0] as [URL, RequestInit];
    expect(url.toString()).toBe('http://127.0.0.1:43210/api/v1/session');
    expect(new Headers(options.headers).get('Authorization')).toBe(`Bearer ${TOKEN}`);
    expect(options.credentials).toBe('omit');
    expect(options.cache).toBe('no-store');
    expect(options.mode).toBe('cors');
  });

  it('rejects non-loopback or non-HTTP bootstrap origins before fetching', async () => {
    window.bredDesktop = {
      getBootstrap: async () => ({ origin: 'http://localhost:43210', token: TOKEN }),
    };
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(desktopRequest('/api/v1/session')).rejects.toMatchObject({
      code: 'invalid_desktop_host',
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('surfaces a native bridge failure instead of issuing an unauthenticated request', async () => {
    window.bredDesktop = { getBootstrap: async () => { throw new Error('native bridge unavailable'); } };
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(desktopRequest('/api/v1/session')).rejects.toMatchObject({
      code: 'desktop_bridge_failed',
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

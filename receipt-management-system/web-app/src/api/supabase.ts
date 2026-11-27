import { createClient, type SupabaseClient } from '@supabase/supabase-js';

interface PublicConfig {
  url: string;
  anonKey: string;
}

export function isPrivilegedSupabaseKey(key: string): boolean {
  if (/^sb_secret_/i.test(key)) return true;

  const payload = key.split('.')[1];
  if (!payload) return false;

  try {
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const claims = JSON.parse(atob(base64)) as { role?: unknown };
    return claims.role === 'service_role' || claims.role === 'supabase_admin';
  } catch {
    return false;
  }
}

function readPublicConfig(): { config: PublicConfig | null; error: string | null } {
  const url = import.meta.env.VITE_SUPABASE_URL?.trim();
  const anonKey = import.meta.env.VITE_SUPABASE_ANON_KEY?.trim();

  if (!url && !anonKey) {
    return {
      config: null,
      error: 'Set VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY in web-app/.env.local to connect this shell.',
    };
  }
  if (!url || !anonKey) {
    return {
      config: null,
      error: 'Both VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY are required.',
    };
  }
  if (isPrivilegedSupabaseKey(anonKey)) {
    return {
      config: null,
      error: 'A privileged Supabase key was detected. Use the public anon/publishable key instead.',
    };
  }

  try {
    const parsedUrl = new URL(url);
    const localHost = ['localhost', '127.0.0.1', '::1'].includes(parsedUrl.hostname);
    if (parsedUrl.protocol !== 'https:' && !(localHost && parsedUrl.protocol === 'http:')) {
      return {
        config: null,
        error: 'Supabase must use HTTPS except for a local development instance.',
      };
    }
    return { config: { url: parsedUrl.toString().replace(/\/$/, ''), anonKey }, error: null };
  } catch {
    return { config: null, error: 'VITE_SUPABASE_URL must be a valid HTTPS URL.' };
  }
}

function createMemoryStorage() {
  const values = new Map<string, string>();
  return {
    getItem: async (key: string) => values.get(key) ?? null,
    setItem: async (key: string, value: string) => {
      values.set(key, value);
    },
    removeItem: async (key: string) => {
      values.delete(key);
    },
  };
}

const { config, error } = readPublicConfig();

export const supabaseConfigError = error;
export const supabaseClient: SupabaseClient | null = config
  ? createClient(config.url, config.anonKey, {
      auth: {
        autoRefreshToken: true,
        detectSessionInUrl: false,
        persistSession: true,
        storage: createMemoryStorage(),
        storageKey: 'bred-web-session',
      },
    })
  : null;

export function requireSupabaseClient(): SupabaseClient {
  if (!supabaseClient) {
    throw new Error(supabaseConfigError ?? 'Supabase is not configured.');
  }
  return supabaseClient;
}

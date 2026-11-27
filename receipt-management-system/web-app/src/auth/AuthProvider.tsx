import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import type { Session, SupabaseClient } from '@supabase/supabase-js';
import { supabaseClient, supabaseConfigError } from '../api/supabase';
import type { AppRole, UserProfile } from '../types';

type AuthStatus = 'initializing' | 'signed-out' | 'signed-in';

interface ProfileRow {
  id: string;
  full_name: string;
  email: string;
  role: string;
  is_active: boolean;
  account_status: string;
  company_id: string | null;
  companies: { name: string } | { name: string }[] | null;
}

interface AuthContextValue {
  status: AuthStatus;
  profile: UserProfile | null;
  message: string | null;
  configurationError: string | null;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
  clearMessage: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function safeRole(value: string): AppRole {
  if (value === 'creator' || value === 'admin') return value;
  return 'encoder';
}

function profileStatusMessage(profile: ProfileRow): string | null {
  if (profile.account_status === 'PENDING') {
    return 'Your registration is pending administrator approval.';
  }
  if (profile.account_status === 'REJECTED') {
    return 'Your registration was rejected. Please contact an administrator.';
  }
  if (!profile.is_active || profile.account_status !== 'APPROVED') {
    return 'This account is inactive or not approved.';
  }
  return null;
}

function readableAuthError(error: unknown): string {
  if (error instanceof Error && /invalid login credentials/i.test(error.message)) {
    return 'Email or password is incorrect.';
  }
  return 'Sign-in could not be completed. Check your connection and try again.';
}

async function readProfile(client: SupabaseClient, userId: string): Promise<UserProfile> {
  const { data, error } = await client
    .from('profiles')
    .select('id,full_name,email,role,is_active,account_status,company_id,companies(name)')
    .eq('id', userId)
    .maybeSingle();

  if (error) throw new Error('Your BRED profile could not be loaded.');
  if (!data) throw new Error('This account has no BRED profile. Contact an administrator.');

  const row = data as unknown as ProfileRow;
  const statusMessage = profileStatusMessage(row);
  if (statusMessage) throw new Error(statusMessage);

  const relation = Array.isArray(row.companies) ? row.companies[0] : row.companies;
  return {
    id: row.id,
    fullName: row.full_name,
    email: row.email,
    role: safeRole(row.role),
    companyName: relation?.name ?? '',
  };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('initializing');
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const generation = useRef(0);

  const applySession = useCallback(async (session: Session | null): Promise<void> => {
    const currentGeneration = ++generation.current;
    if (!session || !supabaseClient) {
      setProfile(null);
      setStatus('signed-out');
      return;
    }

    setStatus('initializing');
    try {
      const nextProfile = await readProfile(supabaseClient, session.user.id);
      if (currentGeneration !== generation.current) return;
      setProfile(nextProfile);
      setMessage(null);
      setStatus('signed-in');
    } catch (error) {
      if (currentGeneration !== generation.current) return;
      setProfile(null);
      setMessage(error instanceof Error ? error.message : 'Your BRED account could not be verified.');
      setStatus('signed-out');
      try {
        await supabaseClient.auth.signOut();
      } catch {
        // The application stays signed out even if the remote sign-out request cannot reach Supabase.
      }
    }
  }, []);

  useEffect(() => {
    if (!supabaseClient) {
      setStatus('signed-out');
      return;
    }

    let active = true;
    const { data: { subscription } } = supabaseClient.auth.onAuthStateChange((_event, session) => {
      // Supabase auth callbacks are kept synchronous; profile I/O is deferred outside the callback.
      globalThis.setTimeout(() => {
        if (active) void applySession(session);
      }, 0);
    });

    void supabaseClient.auth.getSession()
      .then(({ data, error }) => {
        if (!active) return;
        if (error) {
          setMessage('Your saved sign-in session could not be checked. Sign in again.');
          setStatus('signed-out');
          return;
        }
        void applySession(data.session);
      })
      .catch(() => {
        if (!active) return;
        setMessage('Your saved sign-in session could not be checked. Sign in again.');
        setStatus('signed-out');
      });

    return () => {
      active = false;
      generation.current += 1;
      subscription.unsubscribe();
    };
  }, [applySession]);

  const signIn = useCallback(async (email: string, password: string) => {
    if (!supabaseClient) {
      setMessage(supabaseConfigError ?? 'Supabase is not configured.');
      return;
    }

    setMessage(null);
    setStatus('initializing');
    try {
      const { data, error } = await supabaseClient.auth.signInWithPassword({
        email: email.trim(),
        password,
      });

      if (error || !data.session) {
        setStatus('signed-out');
        setMessage(readableAuthError(error ?? new Error('No session returned.')));
        return;
      }

      await applySession(data.session);
    } catch (error) {
      setStatus('signed-out');
      setMessage(readableAuthError(error));
    }
  }, [applySession]);

  const signOut = useCallback(async () => {
    try {
      if (supabaseClient) await supabaseClient.auth.signOut();
    } finally {
      generation.current += 1;
      setProfile(null);
      setMessage(null);
      setStatus('signed-out');
    }
  }, []);

  const clearMessage = useCallback(() => setMessage(null), []);

  return (
    <AuthContext.Provider
      value={{ status, profile, message, configurationError: supabaseConfigError, signIn, signOut, clearMessage }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside AuthProvider.');
  return context;
}

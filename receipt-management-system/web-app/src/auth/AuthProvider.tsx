import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { desktopBridgeError, desktopRequest, DesktopApiError } from '../api/desktop';
import type { AppRole, UserProfile } from '../types';

type AuthStatus = 'initializing' | 'signed-out' | 'signed-in';

interface SessionResponse {
  user: UserProfile;
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

function isAppRole(value: string): value is AppRole {
  return value === 'creator' || value === 'admin' || value === 'encoder';
}

function validateProfile(value: UserProfile): UserProfile {
  if (!value || typeof value.id !== 'string' || typeof value.fullName !== 'string'
      || typeof value.email !== 'string' || typeof value.companyName !== 'string'
      || !isAppRole(value.role)) {
    throw new Error('The BRED service returned an invalid account profile.');
  }
  return value;
}

function messageFrom(error: unknown): string {
  return error instanceof Error
    ? error.message
    : 'Sign-in could not be completed. Check the BRED desktop service and try again.';
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('initializing');
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [configurationError, setConfigurationError] = useState<string | null>(desktopBridgeError);
  const generation = useRef(0);

  useEffect(() => {
    const initializeSession = () => {
      const requestGeneration = ++generation.current;
      const bridgeError = desktopBridgeError();
      if (bridgeError) {
        setConfigurationError(bridgeError);
        setStatus('signed-out');
        return;
      }

      void desktopRequest<SessionResponse>('/api/v1/session').then(
        (session) => {
          if (requestGeneration !== generation.current) return;
          try {
            setProfile(validateProfile(session.user));
            setConfigurationError(null);
            setMessage(null);
            setStatus('signed-in');
          } catch (error) {
            setProfile(null);
            setConfigurationError(messageFrom(error));
            setStatus('signed-out');
          }
        },
        (error: unknown) => {
          if (requestGeneration !== generation.current) return;
          if (error instanceof DesktopApiError && error.status === 401) {
            setProfile(null);
            setConfigurationError(null);
            setMessage(null);
          } else {
            setProfile(null);
            setConfigurationError(messageFrom(error));
          }
          setStatus('signed-out');
        },
      );
    };

    window.bredDesktopReadyCallback = initializeSession;
    initializeSession();
    return () => {
      generation.current += 1;
      if (window.bredDesktopReadyCallback === initializeSession) {
        delete window.bredDesktopReadyCallback;
      }
    };
  }, []);

  const signIn = useCallback(async (email: string, password: string) => {
    const requestGeneration = ++generation.current;
    setMessage(null);
    setStatus('initializing');
    try {
      const session = await desktopRequest<SessionResponse>('/api/v1/session', {
        method: 'POST',
        body: { email, password },
      });
      if (requestGeneration !== generation.current) return;
      setProfile(validateProfile(session.user));
      setConfigurationError(null);
      setStatus('signed-in');
    } catch (error) {
      if (requestGeneration !== generation.current) return;
      setProfile(null);
      setStatus('signed-out');
      if (error instanceof DesktopApiError
          && ['desktop_host_unavailable', 'invalid_desktop_host'].includes(error.code)) {
        setConfigurationError(error.message);
      } else {
        setMessage(messageFrom(error));
      }
    }
  }, []);

  const signOut = useCallback(async () => {
    generation.current += 1;
    try {
      await desktopRequest<void>('/api/v1/session', { method: 'DELETE' });
    } catch (error) {
      setMessage(messageFrom(error));
      return;
    }
    generation.current += 1;
    setProfile(null);
    setMessage(null);
    setStatus('signed-out');
  }, []);

  const clearMessage = useCallback(() => setMessage(null), []);

  return (
    <AuthContext.Provider
      value={{ status, profile, message, configurationError, signIn, signOut, clearMessage }}
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

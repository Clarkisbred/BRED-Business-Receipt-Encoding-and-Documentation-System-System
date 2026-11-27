import { describe, expect, it } from 'vitest';
import { isPrivilegedSupabaseKey } from './supabase';

describe('Supabase browser-key guard', () => {
  it('rejects secret and service-role keys while allowing public client keys', () => {
    const serviceRolePayload = btoa(JSON.stringify({ role: 'service_role' }))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '');
    const anonPayload = btoa(JSON.stringify({ role: 'anon' }))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '');

    expect(isPrivilegedSupabaseKey('sb_secret_example')).toBe(true);
    expect(isPrivilegedSupabaseKey(`header.${serviceRolePayload}.signature`)).toBe(true);
    expect(isPrivilegedSupabaseKey(`header.${anonPayload}.signature`)).toBe(false);
    expect(isPrivilegedSupabaseKey('sb_publishable_example')).toBe(false);
  });
});

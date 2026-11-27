const DUCK_PROFILE_EMAIL = 'clarkhilario.bread@gmail.com';

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return 'B';
  const first = parts[0] ?? 'B';
  const last = parts[parts.length - 1] ?? first;
  return parts.length === 1
    ? first.slice(0, 1).toUpperCase()
    : `${first.slice(0, 1)}${last.slice(0, 1)}`.toUpperCase();
}

export function ProfileAvatar({
  className,
  name,
  email,
}: {
  className: string;
  name: string;
  email: string;
}) {
  const profileName = name.trim() || 'BRED user';
  const hasDuckPortrait = email.trim().toLowerCase() === DUCK_PROFILE_EMAIL;

  return (
    <span className={className} role="img" aria-label={`${profileName} profile`}>
      {hasDuckPortrait
        ? <img src="/assets/breducklogo.png" alt="" />
        : initials(profileName)}
    </span>
  );
}

export function DuckyAvatar({ className }: { className: string }) {
  return (
    <span className={className} aria-hidden="true">
      <img src="/assets/duckyai.png" alt="" />
    </span>
  );
}

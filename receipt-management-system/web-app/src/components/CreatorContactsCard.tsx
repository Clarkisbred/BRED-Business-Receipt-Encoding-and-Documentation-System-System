import { useEffect, useState } from 'react';
import { Mail, Phone } from 'lucide-react';
import { DuckyAvatar, ProfileAvatar } from './IdentityAvatar';

const MIN_IDLE_MS = 5 * 60 * 1000;
const MAX_IDLE_MS = 30 * 60 * 1000;

type DuckFlight = {
  duration: number;
  direction: 'normal' | 'reverse';
};

export function CreatorContactsCard() {
  const [duckFlight, setDuckFlight] = useState<DuckFlight | null>(null);

  useEffect(() => {
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;

    let active = false;
    let idleTimer = 0;
    const scheduleNextFlight = () => {
      window.clearTimeout(idleTimer);
      const delay = MIN_IDLE_MS + Math.random() * (MAX_IDLE_MS - MIN_IDLE_MS);
      idleTimer = window.setTimeout(() => {
        active = true;
        setDuckFlight({
          duration: 7 + Math.round(Math.random() * 7),
          direction: Math.random() < 0.5 ? 'normal' : 'reverse',
        });
      }, delay);
    };
    const stopOrResetFlight = () => {
      if (active) {
        active = false;
        setDuckFlight(null);
      }
      scheduleNextFlight();
    };

    scheduleNextFlight();
    window.addEventListener('click', stopOrResetFlight, true);
    window.addEventListener('scroll', stopOrResetFlight, { capture: true, passive: true });
    window.addEventListener('keydown', stopOrResetFlight, true);

    return () => {
      window.clearTimeout(idleTimer);
      window.removeEventListener('click', stopOrResetFlight, true);
      window.removeEventListener('scroll', stopOrResetFlight, true);
      window.removeEventListener('keydown', stopOrResetFlight, true);
    };
  }, []);

  return (
    <section
      className="panel settings-section settings-section--creator-contacts"
      aria-labelledby="settings-contacts-title"
    >
      <div className="settings-section-heading">
        <span className="settings-section-icon"><Mail aria-hidden="true" size={18} /></span>
        <div>
          <h3 id="settings-contacts-title">Creator contacts</h3>
          <p>Contact the BRED Creator for account or product support.</p>
        </div>
      </div>
      <div className="settings-contact-card">
        <ProfileAvatar className="settings-contact-avatar" name="Clark Hilario" email="clarkhilario.bread@gmail.com" />
        <div className="settings-contact-copy">
          <strong>Clark Hilario</strong>
          <a href="mailto:clarkhilario.bread@gmail.com">clarkhilario.bread@gmail.com</a>
          <a href="tel:+639944826590"><Phone aria-hidden="true" size={14} /> +63 994 482 6590</a>
        </div>
      </div>
      {duckFlight && (
        <span
          className="settings-contact-duck-flight"
          aria-hidden="true"
          style={{
            animationDuration: `${duckFlight.duration}s`,
            animationDirection: duckFlight.direction,
          }}
        >
          <DuckyAvatar className="settings-contact-duck" />
        </span>
      )}
    </section>
  );
}

import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CreatorContactsCard } from './CreatorContactsCard';

const matchMediaDescriptor = Object.getOwnPropertyDescriptor(window, 'matchMedia');

describe('CreatorContactsCard', () => {
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.restoreAllMocks();
    if (matchMediaDescriptor) {
      Object.defineProperty(window, 'matchMedia', matchMediaDescriptor);
    } else {
      Reflect.deleteProperty(window, 'matchMedia');
    }
  });

  it('starts a duck flight after an idle delay and stops it on user activity', () => {
    vi.useFakeTimers();
    vi.spyOn(Math, 'random').mockReturnValue(0);
    render(<CreatorContactsCard />);

    act(() => vi.advanceTimersByTime(4 * 60 * 1000));
    fireEvent.click(screen.getByRole('region', { name: 'Creator contacts' }));
    act(() => vi.advanceTimersByTime(5 * 60 * 1000 - 1));
    expect(document.querySelector('.settings-contact-duck-flight')).not.toBeInTheDocument();

    act(() => vi.advanceTimersByTime(1));
    expect(document.querySelector('.settings-contact-duck-flight')).toBeInTheDocument();

    fireEvent.scroll(window);
    expect(document.querySelector('.settings-contact-duck-flight')).not.toBeInTheDocument();
    act(() => vi.advanceTimersByTime(5 * 60 * 1000 - 1));
    expect(document.querySelector('.settings-contact-duck-flight')).not.toBeInTheDocument();
    act(() => vi.advanceTimersByTime(1));
    expect(document.querySelector('.settings-contact-duck-flight')).toBeInTheDocument();
  });

  it('renders the Creator profile duck portrait as the idle avatar', () => {
    render(<CreatorContactsCard />);

    const avatar = screen.getByRole('img', { name: 'Clark Hilario profile' });
    expect(avatar).toHaveClass('settings-contact-avatar');
    expect(avatar.querySelector('img')).toHaveAttribute('src', '/assets/breducklogo.png');
  });

  it('does not start the duck animation when reduced motion is enabled', () => {
    vi.useFakeTimers();
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: vi.fn().mockReturnValue({ matches: true }),
    });
    render(<CreatorContactsCard />);

    act(() => vi.advanceTimersByTime(30 * 60 * 1000));
    expect(document.querySelector('.settings-contact-duck-flight')).not.toBeInTheDocument();
  });
});

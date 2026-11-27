import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { DuckyAvatar, ProfileAvatar } from './IdentityAvatar';

describe('IdentityAvatar', () => {
  afterEach(() => cleanup());

  it('uses the Swing profile portrait for the Creator account', () => {
    render(<ProfileAvatar className="avatar" name="Creator Name" email=" CLARKHILARIO.BREAD@GMAIL.COM " />);

    expect(screen.getByRole('img', { name: 'Creator Name profile' })
      .querySelector('img')).toHaveAttribute('src', '/assets/breducklogo.png');
  });

  it('uses initials when no Swing profile portrait exists', () => {
    render(<ProfileAvatar className="avatar" name="Bre Duck" email="bre@example.test" />);

    expect(screen.getByRole('img', { name: 'Bre Duck profile' })).toHaveTextContent('BD');
    expect(screen.queryByRole('img', { name: 'Bre Duck profile' })?.querySelector('img')).toBeNull();
  });

  it('uses the packaged Ducky AI image', () => {
    const { container } = render(<DuckyAvatar className="ducky-avatar" />);

    expect(container.querySelector('.ducky-avatar img')).toHaveAttribute('src', '/assets/duckyai.png');
  });
});

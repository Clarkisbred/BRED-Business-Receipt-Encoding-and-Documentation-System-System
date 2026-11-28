import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Sidebar, SIDEBAR_WIDTH_PX } from './Sidebar';

describe('Sidebar', () => {
  afterEach(() => cleanup());

  it('uses the source sidebar width and filters navigation by the signed-in role', () => {
    const onNavigate = vi.fn();
    const { container } = render(
      <Sidebar
        role="encoder"
        fullName="Riley Encoder"
        email="riley@example.test"
        companyName="BRED Company"
        activeModule="DASHBOARD"
        onNavigate={onNavigate}
      />,
    );

    const sidebar = container.querySelector('aside.sidebar');
    expect(sidebar).not.toBeNull();
    expect(sidebar).toHaveStyle(`--sidebar-width: ${SIDEBAR_WIDTH_PX}px`);
    expect(SIDEBAR_WIDTH_PX).toBe(252);
    expect(screen.getByRole('button', { name: 'Dashboard' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('button', { name: 'Receipts' })).toBeInTheDocument();
    const duckyItem = screen.getByRole('button', { name: 'Ducky' });
    expect(duckyItem.querySelector('svg')).not.toBeNull();
    expect(duckyItem.querySelector('img')).toBeNull();
    expect(container.querySelector('.profile-mark')).toHaveTextContent('RE');
    expect(screen.queryByRole('button', { name: 'Users' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Companies' })).not.toBeInTheDocument();

    const financialGroup = within(screen.getByRole('navigation', { name: 'Main navigation' }))
      .getByRole('region', { name: 'FINANCIAL & OPERATIONS' });
    expect(within(financialGroup).getAllByRole('button').map((button) => button.textContent?.trim()))
      .toEqual(['Billing & Finance', 'Royalties', 'Suppliers', 'Reports']);

    fireEvent.click(screen.getByRole('button', { name: 'Projects' }));
    expect(onNavigate).toHaveBeenCalledWith('PROJECTS');
    expect(within(screen.getByRole('navigation', { name: 'Main navigation' }))
      .getByRole('heading', { name: 'PROJECT MANAGEMENT' })).toBeInTheDocument();
  });

  it('marks direct financial destinations as the active page', () => {
    render(
      <Sidebar
        role="admin"
        fullName="Riley Admin"
        email="riley@example.test"
        companyName="BRED Company"
        activeModule="ROYALTIES"
        onNavigate={vi.fn()}
      />,
    );

    expect(screen.getByRole('button', { name: 'Royalties' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('button', { name: 'Billing & Finance' })).toBeInTheDocument();
  });
});

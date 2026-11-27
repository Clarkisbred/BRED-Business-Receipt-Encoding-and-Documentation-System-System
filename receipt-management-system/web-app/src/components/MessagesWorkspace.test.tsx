import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MessagesWorkspace } from './MessagesWorkspace';
import type { ModulePageData } from '../api/modules';

const { loadModulePage, saveModuleRecord } = vi.hoisted(() => ({
  loadModulePage: vi.fn(),
  saveModuleRecord: vi.fn(),
}));

vi.mock('../api/modules', () => ({ loadModulePage, saveModuleRecord }));

const messagesPage: ModulePageData = {
  key: 'MESSAGES',
  title: 'Custom Messages',
  description: 'Read company chat and send direct or company messages.',
  metrics: [],
  columns: [],
  fields: [
    {
      key: 'recipientId',
      label: 'Teammate',
      type: 'select',
      options: [{ label: 'Bob Builder', value: 'bob-id' }],
    },
    { key: 'companyChat', label: 'Send to company chat', type: 'checkbox' },
    { key: 'urgent', label: 'Mark as urgent', type: 'checkbox' },
  ],
  rows: [
    {
      id: 'group-1',
      values: {
        sentAt: '2025-03-01T10:00:00Z',
        senderId: 'bob-id',
        recipientId: null,
        companyId: 'company-id',
        broadcast: false,
        urgent: false,
        sender: 'Bob Builder',
        type: 'Company',
        body: 'Company update',
      },
    },
    {
      id: 'direct-1',
      values: {
        sentAt: '2025-03-01T11:00:00Z',
        senderId: 'bob-id',
        recipientId: 'current-id',
        companyId: null,
        broadcast: false,
        urgent: false,
        sender: 'Bob Builder',
        type: 'Direct',
        body: 'Private hello',
      },
    },
    {
      id: 'direct-2',
      values: {
        sentAt: '2025-03-01T11:05:00Z',
        senderId: 'current-id',
        recipientId: 'bob-id',
        companyId: null,
        broadcast: false,
        urgent: false,
        sender: 'Current User',
        type: 'Direct',
        body: 'Private reply',
      },
    },
  ],
  canCreate: true,
  canEdit: false,
  canDelete: false,
  primaryActionLabel: 'New message',
  submitLabel: 'Send message',
  createAction: 'send',
};

describe('MessagesWorkspace', () => {
  afterEach(() => cleanup());

  beforeEach(() => {
    loadModulePage.mockReset().mockResolvedValue(messagesPage);
    saveModuleRecord.mockReset().mockResolvedValue(messagesPage);
  });

  it('switches between company and private threads using Java message identifiers', async () => {
    render(<MessagesWorkspace currentUserId="current-id" currentUserRole="admin" />);

    expect(await within(screen.getByRole('log')).findByText('Company update')).toBeInTheDocument();
    expect(screen.queryByText('Private hello')).not.toBeInTheDocument();

    fireEvent.click(await screen.findByRole('button', { name: /Bob Builder/ }));

    expect(await within(screen.getByRole('log')).findByText('Private hello')).toBeInTheDocument();
    expect(within(screen.getByRole('log')).getByText('Private reply')).toBeInTheDocument();
    expect(within(screen.getByRole('log')).queryByText('Company update')).not.toBeInTheDocument();
  });

  it('sends direct and urgent company messages through Java actions', async () => {
    render(<MessagesWorkspace currentUserId="current-id" currentUserRole="creator" />);
    await screen.findByText('Company update');

    fireEvent.click(screen.getByRole('button', { name: /Bob Builder/ }));
    fireEvent.change(screen.getByRole('textbox', { name: 'Write a message' }), {
      target: { value: 'A private follow-up' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));

    await waitFor(() => expect(saveModuleRecord).toHaveBeenLastCalledWith(
      'MESSAGES',
      'send',
      { body: 'A private follow-up', recipientId: 'bob-id' },
    ));

    fireEvent.click(screen.getByRole('button', { name: /Company chat/ }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Mark as urgent' }));
    fireEvent.change(screen.getByRole('textbox', { name: 'Write a message' }), {
      target: { value: 'Urgent team update' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));

    await waitFor(() => expect(saveModuleRecord).toHaveBeenLastCalledWith(
      'MESSAGES',
      'send',
      { body: 'Urgent team update', companyChat: true, urgent: true },
    ));
  });

  it('lets only Creator compose a broadcast with selected active teammates', async () => {
    render(<MessagesWorkspace currentUserId="current-id" currentUserRole="creator" />);
    await screen.findByText('Company update');

    fireEvent.click(screen.getByRole('button', { name: 'Broadcast' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Bob Builder' }));
    fireEvent.change(screen.getByRole('textbox', { name: 'Write a message' }), {
      target: { value: 'Please review the new policy.' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));

    await waitFor(() => expect(saveModuleRecord).toHaveBeenLastCalledWith(
      'MESSAGES',
      'send',
      {
        body: 'Please review the new policy.',
        broadcast: true,
        recipientIds: ['bob-id'],
      },
    ));

    cleanup();
    render(<MessagesWorkspace currentUserId="current-id" currentUserRole="admin" />);
    expect(await screen.findByText('Company update')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Broadcast' })).not.toBeInTheDocument();
  });

  it('shows a recoverable initial load error', async () => {
    loadModulePage.mockRejectedValueOnce(new Error('Messages service unavailable.'));
    render(<MessagesWorkspace currentUserId="current-id" currentUserRole="admin" />);

    expect(await screen.findByText('Messages service unavailable.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

    expect(await screen.findByText('Company update')).toBeInTheDocument();
    expect(loadModulePage).toHaveBeenCalledTimes(2);
  });
});

import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DuckyWorkspace } from './DuckyWorkspace';
import type { ModulePageData } from '../api/modules';

const { saveModuleRecord } = vi.hoisted(() => ({
  saveModuleRecord: vi.fn(),
}));

vi.mock('../api/modules', () => ({ saveModuleRecord }));

function answerPage(answer: string): ModulePageData {
  return {
    key: 'DUCKY',
    title: 'Ducky',
    description: 'Ask BRED’s AI assistant.',
    metrics: [],
    columns: [{ key: 'answer', label: 'Ducky response' }],
    fields: [],
    rows: [{ id: 'answer-1', values: { answer } }],
    canCreate: true,
    canEdit: false,
    canDelete: false,
    primaryActionLabel: 'Ask Ducky',
    submitLabel: 'Send question',
    createAction: 'ask',
  };
}

describe('DuckyWorkspace', () => {
  afterEach(() => cleanup());

  beforeEach(() => {
    saveModuleRecord.mockReset().mockResolvedValue(answerPage('Budget is $100.'));
  });

  it('starts conversations, preserves session history, and sends prior turns to Java', async () => {
    render(<DuckyWorkspace active userId="user-1" userName="BRED User" userEmail="user@example.test" />);

    fireEvent.change(screen.getByRole('textbox', { name: 'Message Ducky' }), {
      target: { value: 'What is my budget?' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));

    expect(await screen.findByText('Budget is $100.')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'BRED User profile' })).toHaveTextContent('BU');
    expect(saveModuleRecord).toHaveBeenLastCalledWith('DUCKY', 'ask', {
      question: 'What is my budget?',
      history: [],
    });

    fireEvent.change(screen.getByRole('textbox', { name: 'Message Ducky' }), {
      target: { value: 'Can you explain that?' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));

    await waitFor(() => expect(saveModuleRecord).toHaveBeenLastCalledWith('DUCKY', 'ask', {
      question: 'Can you explain that?',
      history: ['User: What is my budget?', 'Ducky: Budget is $100.'],
    }));

    fireEvent.click(screen.getByRole('button', { name: /New chat/ }));
    expect(screen.getByRole('heading', { name: 'How can I help?' })).toBeInTheDocument();
    expect(screen.queryByText('Budget is $100.')).not.toBeInTheDocument();
  });

  it('restores the prompt and shows an error when Ducky cannot respond', async () => {
    saveModuleRecord.mockRejectedValueOnce(new Error('AI provider unavailable.'));
    render(<DuckyWorkspace active userId="user-1" userName="BRED User" userEmail="user@example.test" />);

    const composer = screen.getByRole('textbox', { name: 'Message Ducky' });
    fireEvent.change(composer, { target: { value: 'Help me plan this project.' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));

    expect(await screen.findByText('AI provider unavailable.')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Message Ducky' })).toHaveValue('Help me plan this project.');
    expect(within(screen.getByRole('log')).queryByText('Help me plan this project.')).not.toBeInTheDocument();
  });
});

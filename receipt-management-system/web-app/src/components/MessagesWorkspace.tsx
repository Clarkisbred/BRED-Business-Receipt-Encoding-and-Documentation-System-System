import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { Building2, CircleAlert, MessageCircle, RefreshCw, Search, Send, Users } from 'lucide-react';
import { loadModulePage, saveModuleRecord } from '../api/modules';
import type { ModuleField, ModulePageData, ModuleRecord } from '../api/modules';
import type { AppRole } from '../types';

interface MessagesWorkspaceProps {
  currentUserId: string;
  currentUserRole: AppRole;
}

interface Contact {
  id: string;
  name: string;
}

type ThreadSelection = 'company' | 'broadcasts' | 'compose-broadcast' | `direct:${string}`;

function valueAsText(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function valueAsBoolean(value: unknown): boolean {
  return value === true;
}

function initials(name: string): string {
  return name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0] ?? '')
    .join('')
    .toUpperCase();
}

function messageDate(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'Date unavailable';
  return date.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' });
}

function messageTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
}

function messageSentAt(message: ModuleRecord): string {
  return valueAsText(message.values.sentAt);
}

function mostRecent(messages: ModuleRecord[]): ModuleRecord | undefined {
  return [...messages].sort((a, b) => messageSentAt(b).localeCompare(messageSentAt(a)))[0];
}

function messagesForThread(
  rows: ModuleRecord[],
  selection: ThreadSelection | null,
  currentUserId: string,
): ModuleRecord[] {
  if (!selection || selection === 'compose-broadcast') return [];
  const messages = rows.filter((message) => {
    const values = message.values;
    if (selection === 'company') {
      return Boolean(valueAsText(values.companyId)) || valueAsText(values.type).startsWith('Company');
    }
    if (selection === 'broadcasts') return valueAsBoolean(values.broadcast);
    const contactId = selection.slice('direct:'.length);
    return (valueAsText(values.senderId) === currentUserId && valueAsText(values.recipientId) === contactId)
      || (valueAsText(values.senderId) === contactId && valueAsText(values.recipientId) === currentUserId);
  });
  return messages.sort((a, b) => messageSentAt(a).localeCompare(messageSentAt(b)));
}

function recipientOptions(fields: ModuleField[]): Contact[] {
  return fields
    .find((field) => field.key === 'recipientId')
    ?.options?.map((option) => ({ id: option.value, name: option.label })) ?? [];
}

function latestPreview(messages: ModuleRecord[], currentUserId: string): string {
  const latest = mostRecent(messages);
  if (!latest) return 'Start a conversation';
  const prefix = valueAsText(latest.values.senderId) === currentUserId ? 'You: ' : '';
  return `${prefix}${valueAsText(latest.values.body)}`;
}

export function MessagesWorkspace({ currentUserId, currentUserRole }: MessagesWorkspaceProps) {
  const [page, setPage] = useState<ModulePageData | null>(null);
  const [selectedThread, setSelectedThread] = useState<ThreadSelection | null>(null);
  const [search, setSearch] = useState('');
  const [body, setBody] = useState('');
  const [urgent, setUrgent] = useState(false);
  const [broadcastRecipientIds, setBroadcastRecipientIds] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const pageRef = useRef<ModulePageData | null>(null);
  const requestInFlight = useRef(false);
  const threadSelectedByUser = useRef(false);
  const transcriptRef = useRef<HTMLDivElement>(null);
  const shouldFollowRef = useRef(true);

  const load = useCallback(async (background = false) => {
    if (requestInFlight.current) return;
    requestInFlight.current = true;
    if (background && pageRef.current) setRefreshing(true);
    else setLoading(true);
    try {
      const next = await loadModulePage('MESSAGES');
      pageRef.current = next;
      setPage(next);
      setError(null);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : 'Messages could not be loaded.');
    } finally {
      requestInFlight.current = false;
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    void load();
    const interval = window.setInterval(() => void load(true), 5000);
    return () => window.clearInterval(interval);
  }, [load]);

  const contacts = useMemo(() => page ? recipientOptions(page.fields) : [], [page]);
  const hasCompanyChat = page?.fields.some((field) => field.key === 'companyChat') ?? false;
  const broadcastMessages = useMemo(
    () => page?.rows.filter((row) => valueAsBoolean(row.values.broadcast)) ?? [],
    [page],
  );
  const filteredContacts = useMemo(() => {
    const term = search.trim().toLocaleLowerCase();
    return term ? contacts.filter((contact) => contact.name.toLocaleLowerCase().includes(term)) : contacts;
  }, [contacts, search]);

  useEffect(() => {
    if (selectedThread || threadSelectedByUser.current) return;
    if (hasCompanyChat) setSelectedThread('company');
    else if (contacts[0]) setSelectedThread(`direct:${contacts[0].id}`);
    else if (broadcastMessages.length) setSelectedThread('broadcasts');
  }, [broadcastMessages.length, contacts, hasCompanyChat, selectedThread]);

  function selectThread(selection: ThreadSelection) {
    threadSelectedByUser.current = true;
    setSelectedThread(selection);
    setError(null);
    setSuccessMessage(null);
  }

  function startBroadcast() {
    selectThread('compose-broadcast');
    setBroadcastRecipientIds([]);
  }

  const activeContact = selectedThread?.startsWith('direct:')
    ? contacts.find((contact) => contact.id === selectedThread.slice('direct:'.length))
    : undefined;
  const threadMessages = useMemo(
    () => messagesForThread(page?.rows ?? [], selectedThread, currentUserId),
    [currentUserId, page, selectedThread],
  );
  const activeTitle = selectedThread === 'company'
    ? 'Company chat'
    : selectedThread === 'broadcasts'
      ? 'Broadcasts'
      : selectedThread === 'compose-broadcast'
        ? 'New broadcast'
        : activeContact?.name ?? 'Select a conversation';
  const canSend = Boolean(
    selectedThread
      && selectedThread !== 'broadcasts'
      && page?.canCreate
      && (selectedThread !== 'compose-broadcast' || currentUserRole === 'creator'),
  );

  useEffect(() => {
    const transcript = transcriptRef.current;
    if (shouldFollowRef.current && transcript) transcript.scrollTop = transcript.scrollHeight;
  }, [threadMessages]);

  async function sendMessage(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const message = body.trim();
    if (!message || !selectedThread || !canSend || sending) return;
    if (selectedThread === 'compose-broadcast' && broadcastRecipientIds.length === 0) {
      setError('Choose at least one recipient for the broadcast.');
      return;
    }

    setSending(true);
    setError(null);
    setSuccessMessage(null);
    try {
      const values = selectedThread === 'company'
        ? { body: message, companyChat: true, urgent }
        : selectedThread === 'compose-broadcast'
          ? { body: message, broadcast: true, recipientIds: broadcastRecipientIds }
          : { body: message, recipientId: selectedThread.slice('direct:'.length) };
      const next = await saveModuleRecord('MESSAGES', 'send', values);
      pageRef.current = next;
      setPage(next);
      setBody('');
      setUrgent(false);
      if (selectedThread === 'compose-broadcast') {
        setBroadcastRecipientIds([]);
        setSuccessMessage(`Broadcast sent to ${broadcastRecipientIds.length} ${broadcastRecipientIds.length === 1 ? 'teammate' : 'teammates'}.`);
      }
      shouldFollowRef.current = true;
    } catch (sendError) {
      setError(sendError instanceof Error ? sendError.message : 'The message could not be sent.');
    } finally {
      setSending(false);
    }
  }

  return (
    <section className="messenger-page" aria-label="Custom Messages">
      <aside className="messenger-sidebar panel">
        <div className="messenger-sidebar-heading">
          <div>
            <h2>Messages</h2>
            <p>{contacts.length} {contacts.length === 1 ? 'teammate' : 'teammates'}</p>
          </div>
          <button
            className="icon-button"
            type="button"
            aria-label="Refresh messages"
            onClick={() => void load(true)}
            disabled={loading || refreshing}
          >
            <RefreshCw size={17} aria-hidden="true" />
          </button>
          {currentUserRole === 'creator' && (
            <button className="button button-quiet messenger-broadcast-button" type="button" onClick={startBroadcast}>
              <Send size={14} aria-hidden="true" />
              Broadcast
            </button>
          )}
        </div>
        <label className="messenger-search">
          <Search size={16} aria-hidden="true" />
          <span className="visually-hidden">Search teammates</span>
          <input
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search teammates"
          />
        </label>
        <div className="messenger-contact-list" aria-label="Conversations">
          {hasCompanyChat && (
            <button
              className={`messenger-contact${selectedThread === 'company' ? ' is-selected' : ''}`}
              type="button"
              aria-pressed={selectedThread === 'company'}
              onClick={() => selectThread('company')}
            >
              <span className="messenger-avatar messenger-avatar-company"><Users size={17} aria-hidden="true" /></span>
              <span className="messenger-contact-copy">
                <strong>Company chat</strong>
                <small>{latestPreview(page?.rows.filter((row) => Boolean(valueAsText(row.values.companyId))) ?? [], currentUserId)}</small>
              </span>
            </button>
          )}
          {contacts.length > 0 && <p className="messenger-list-label">DIRECT MESSAGES</p>}
          {filteredContacts.map((contact) => {
            const contactMessages = page?.rows.filter((row) => (
              !valueAsBoolean(row.values.broadcast)
              && !valueAsText(row.values.companyId)
              && ((valueAsText(row.values.senderId) === currentUserId && valueAsText(row.values.recipientId) === contact.id)
                || (valueAsText(row.values.senderId) === contact.id && valueAsText(row.values.recipientId) === currentUserId))
            )) ?? [];
            const selection: ThreadSelection = `direct:${contact.id}`;
            return (
              <button
                className={`messenger-contact${selectedThread === selection ? ' is-selected' : ''}`}
                type="button"
                key={contact.id}
                aria-pressed={selectedThread === selection}
                onClick={() => selectThread(selection)}
              >
                <span className="messenger-avatar">{initials(contact.name)}</span>
                <span className="messenger-contact-copy">
                  <strong>{contact.name}</strong>
                  <small>{latestPreview(contactMessages, currentUserId)}</small>
                </span>
              </button>
            );
          })}
          {search && filteredContacts.length === 0 && (
            <p className="messenger-list-empty">No teammates match “{search}”.</p>
          )}
          {broadcastMessages.length > 0 && (
            <>
              <p className="messenger-list-label">ANNOUNCEMENTS</p>
              <button
                className={`messenger-contact${selectedThread === 'broadcasts' ? ' is-selected' : ''}`}
                type="button"
                aria-pressed={selectedThread === 'broadcasts'}
                onClick={() => selectThread('broadcasts')}
              >
                <span className="messenger-avatar messenger-avatar-company"><MessageCircle size={16} aria-hidden="true" /></span>
                <span className="messenger-contact-copy">
                  <strong>Broadcasts</strong>
                  <small>{latestPreview(broadcastMessages, currentUserId)}</small>
                </span>
              </button>
            </>
          )}
          {!loading && contacts.length === 0 && !hasCompanyChat && broadcastMessages.length === 0 && (
            <p className="messenger-list-empty">No conversations are available yet.</p>
          )}
        </div>
        <p className="messenger-refresh-note">
          <span
            className={`messenger-status-dot${error ? ' is-error' : refreshing ? ' is-refreshing' : ''}`}
            aria-hidden="true"
          />
          Refreshes every 5 seconds
        </p>
      </aside>

      <section className="messenger-thread panel" aria-label={`${activeTitle} conversation`}>
        <header className="messenger-thread-header">
          <span className={`messenger-avatar${selectedThread === 'company' || selectedThread === 'broadcasts' ? ' messenger-avatar-company' : ''}`}>
            {selectedThread === 'company'
              ? <Users size={18} aria-hidden="true" />
              : selectedThread === 'broadcasts'
                ? <MessageCircle size={17} aria-hidden="true" />
                : initials(activeTitle)}
          </span>
          <div className="messenger-thread-title">
            <h2>{activeTitle}</h2>
            <p>{selectedThread === 'company'
              ? 'Messages for your company'
              : selectedThread === 'broadcasts'
                ? 'Company announcements'
                : selectedThread === 'compose-broadcast'
                  ? 'Select recipients for a company announcement'
                  : 'Private conversation'}</p>
          </div>
          {selectedThread === 'company' && <Building2 size={18} className="messenger-header-icon" aria-hidden="true" />}
        </header>

        <div
          className="messenger-transcript"
          ref={transcriptRef}
          role="log"
          aria-live="polite"
          aria-relevant="additions text"
          onScroll={(event) => {
            const element = event.currentTarget;
            shouldFollowRef.current = element.scrollHeight - element.scrollTop - element.clientHeight < 72;
          }}
        >
          {loading && !page ? (
            <p className="messenger-state" role="status">Loading conversations…</p>
          ) : threadMessages.length === 0 ? (
            <div className="messenger-empty">
              <span className="messenger-empty-icon">
                {selectedThread === 'company' ? <Users size={22} aria-hidden="true" /> : <MessageCircle size={22} aria-hidden="true" />}
              </span>
              <h3>{selectedThread === 'broadcasts' ? 'No announcements yet' : 'Start the conversation'}</h3>
              <p>{selectedThread === 'company'
                ? 'Share an update with your company team.'
                : selectedThread === 'broadcasts'
                  ? 'Company announcements will appear here.'
                  : selectedThread === 'compose-broadcast'
                    ? 'Choose recipients and write your company announcement.'
                  : activeContact
                    ? `Send a message to ${activeContact.name}.`
                    : 'Choose a teammate or company chat to get started.'}</p>
            </div>
          ) : (
            threadMessages.map((message, index) => {
              const sentAt = messageSentAt(message);
              const previousMessage = index > 0 ? threadMessages[index - 1] : undefined;
              const previousDate = previousMessage ? messageSentAt(previousMessage) : '';
              const showDate = !previousDate || messageDate(previousDate) !== messageDate(sentAt);
              const ownMessage = valueAsText(message.values.senderId) === currentUserId;
              return (
                <div className="messenger-message-group" key={message.id}>
                  {showDate && <div className="messenger-date-divider"><span>{messageDate(sentAt)}</span></div>}
                  <article className={`messenger-message${ownMessage ? ' is-own' : ''}`}>
                    {!ownMessage && <span className="messenger-message-sender">{valueAsText(message.values.sender) || 'Deleted user'}</span>}
                    <p className="messenger-bubble">{valueAsText(message.values.body)}</p>
                    <div className="messenger-message-meta">
                      {valueAsBoolean(message.values.urgent) && <span className="messenger-urgent">Urgent</span>}
                      <time dateTime={sentAt}>{messageTime(sentAt)}</time>
                    </div>
                  </article>
                </div>
              );
            })
          )}
        </div>

        {error && (
          <div className="messenger-error" role="alert">
            <CircleAlert size={16} aria-hidden="true" />
            <span>{error}</span>
            <button type="button" className="text-button" onClick={() => void load(true)}>Retry</button>
          </div>
        )}

        {selectedThread === 'broadcasts' ? (
          <div className="messenger-readonly-note">
            <CircleAlert size={16} aria-hidden="true" />
            Broadcast announcements are read-only in this view.
          </div>
        ) : (
          <form className="messenger-composer" onSubmit={(event) => void sendMessage(event)}>
            {successMessage && <p className="messenger-success" role="status">{successMessage}</p>}
            {selectedThread === 'compose-broadcast' && (
              <fieldset className="messenger-broadcast-recipients">
                <legend>Send to</legend>
                {contacts.map((contact) => (
                  <label key={contact.id}>
                    <input
                      type="checkbox"
                      checked={broadcastRecipientIds.includes(contact.id)}
                      disabled={sending}
                      onChange={(event) => {
                        setBroadcastRecipientIds((current) => event.target.checked
                          ? [...current, contact.id]
                          : current.filter((id) => id !== contact.id));
                        setError(null);
                      }}
                    />
                    {contact.name}
                  </label>
                ))}
                {contacts.length === 0 && <p>No active teammates are available to receive a broadcast.</p>}
              </fieldset>
            )}
            {selectedThread === 'company' && (
              <label className="messenger-urgent-toggle">
                <input type="checkbox" checked={urgent} onChange={(event) => setUrgent(event.target.checked)} />
                Mark as urgent
              </label>
            )}
            <div className="messenger-composer-row">
              <textarea
                aria-label="Write a message"
                value={body}
                onChange={(event) => setBody(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                    event.preventDefault();
                    event.currentTarget.form?.requestSubmit();
                  }
                }}
                maxLength={4000}
                rows={2}
                placeholder={selectedThread ? 'Write a message… (Enter to send, Shift+Enter for a new line)' : 'Select a conversation first'}
                disabled={!canSend || sending}
              />
              <button className="button button-primary messenger-send" type="submit" disabled={!canSend || sending || !body.trim()}>
                <Send size={16} aria-hidden="true" />
                <span>{sending ? 'Sending…' : 'Send'}</span>
              </button>
            </div>
          </form>
        )}
      </section>
    </section>
  );
}

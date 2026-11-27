import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { CircleAlert, Plus, Send, ShieldCheck } from 'lucide-react';
import { saveModuleRecord } from '../api/modules';
import { DuckyAvatar, ProfileAvatar } from './IdentityAvatar';

interface DuckyWorkspaceProps {
  active: boolean;
  userId: string;
  userName: string;
  userEmail: string;
}

interface DuckyMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  createdAt: string;
}

interface DuckyConversation {
  id: string;
  title: string;
  messages: DuckyMessage[];
}

function uniqueId(): string {
  return globalThis.crypto?.randomUUID?.()
    ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function createConversation(): DuckyConversation {
  return { id: uniqueId(), title: 'New chat', messages: [] };
}

function titleFromQuestion(question: string): string {
  const title = question.replace(/\s+/g, ' ').trim();
  return title.length > 34 ? `${title.slice(0, 34).trimEnd()}…` : title || 'New chat';
}

function conversationPreview(conversation: DuckyConversation): string {
  const lastUserMessage = [...conversation.messages].reverse().find((message) => message.role === 'user');
  return lastUserMessage?.content ?? 'No messages yet';
}

function promptHistory(messages: DuckyMessage[]): string[] {
  const limit = 12_000;
  let remaining = limit;
  const history: string[] = [];
  for (const message of [...messages].slice(-20).reverse()) {
    if (remaining <= 0) break;
    const prefix = `${message.role === 'user' ? 'User' : 'Ducky'}: `;
    const contentLimit = Math.min(remaining - prefix.length, 4_000 - prefix.length);
    if (contentLimit <= 0) break;
    const included = `${prefix}${message.content.slice(0, contentLimit)}`;
    history.push(included);
    remaining -= included.length;
  }
  return history.reverse();
}

export function DuckyWorkspace({ active, userId, userName, userEmail }: DuckyWorkspaceProps) {
  const [conversations, setConversations] = useState<DuckyConversation[]>(() => [createConversation()]);
  const [activeId, setActiveId] = useState(() => conversations[0]?.id ?? '');
  const [question, setQuestion] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const transcriptRef = useRef<HTMLDivElement>(null);
  const previousUserId = useRef(userId);

  useEffect(() => {
    if (previousUserId.current === userId) return;
    previousUserId.current = userId;
    const next = createConversation();
    setConversations([next]);
    setActiveId(next.id);
    setQuestion('');
    setError(null);
  }, [userId]);

  const activeConversation = useMemo(
    () => conversations.find((conversation) => conversation.id === activeId) ?? conversations[0],
    [activeId, conversations],
  );

  useEffect(() => {
    const transcript = transcriptRef.current;
    if (transcript) transcript.scrollTop = transcript.scrollHeight;
  }, [activeConversation?.messages]);

  function updateConversation(id: string, update: (conversation: DuckyConversation) => DuckyConversation) {
    setConversations((current) => current.map((conversation) => (
      conversation.id === id ? update(conversation) : conversation
    )));
  }

  function startNewChat() {
    const conversation = createConversation();
    setConversations((current) => [conversation, ...current]);
    setActiveId(conversation.id);
    setQuestion('');
    setError(null);
  }

  async function askDucky(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const prompt = question.trim();
    const conversation = activeConversation;
    if (!prompt || !conversation || sending) return;

    setSending(true);
    setError(null);
    const userMessage: DuckyMessage = {
      id: uniqueId(),
      role: 'user',
      content: prompt,
      createdAt: new Date().toISOString(),
    };
    updateConversation(conversation.id, (current) => ({
      ...current,
      title: current.messages.length === 0 ? titleFromQuestion(prompt) : current.title,
      messages: [...current.messages, userMessage],
    }));

    try {
      const response = await saveModuleRecord('DUCKY', 'ask', {
        question: prompt,
        history: promptHistory(conversation.messages),
      });
      const answer = response.rows[0]?.values.answer;
      if (typeof answer !== 'string' || !answer.trim()) {
        throw new Error('Ducky returned an empty response. Please try again.');
      }
      updateConversation(conversation.id, (current) => ({
        ...current,
        messages: [...current.messages, {
          id: uniqueId(),
          role: 'assistant',
          content: answer,
          createdAt: new Date().toISOString(),
        }],
      }));
      if (activeId === conversation.id) setQuestion('');
    } catch (askError) {
      updateConversation(conversation.id, (current) => ({
        ...current,
        messages: current.messages.filter((message) => message.id !== userMessage.id),
      }));
      setError(askError instanceof Error ? askError.message : 'Ducky could not complete that request.');
    } finally {
      setSending(false);
    }
  }

  return (
    <section className="ducky-page" aria-label="Ducky chat" hidden={!active}>
      <aside className="ducky-sidebar panel">
        <div className="ducky-sidebar-heading">
          <div>
            <h2>Chats</h2>
            <p>Your conversations in this session</p>
          </div>
          <button className="button button-quiet ducky-new-chat" type="button" onClick={startNewChat}>
            <Plus size={16} aria-hidden="true" />
            New chat
          </button>
        </div>
        <nav className="ducky-conversation-list" aria-label="Recent chats">
          {conversations.map((conversation) => (
            <button
              className={`ducky-conversation${conversation.id === activeConversation?.id ? ' is-selected' : ''}`}
              type="button"
              key={conversation.id}
              aria-current={conversation.id === activeConversation?.id ? 'page' : undefined}
              onClick={() => {
                setActiveId(conversation.id);
                setError(null);
              }}
            >
              <DuckyAvatar className="ducky-conversation-icon" />
              <span className="ducky-conversation-copy">
                <strong>{conversation.title}</strong>
                <small>{conversationPreview(conversation)}</small>
              </span>
            </button>
          ))}
        </nav>
        <div className="ducky-privacy-note">
          <ShieldCheck size={17} aria-hidden="true" />
          <p>Private BRED records are not shared unless you approve a request.</p>
        </div>
      </aside>

      <section className="ducky-thread panel" aria-label="Ducky conversation">
        <header className="ducky-thread-header">
          <DuckyAvatar className="ducky-avatar" />
          <div>
            <h2>Ducky</h2>
            <p>BRED AI assistant</p>
          </div>
          <span className="ducky-protection"><ShieldCheck size={15} aria-hidden="true" /> Private data protected</span>
        </header>

        <div className="ducky-transcript" ref={transcriptRef} role="log" aria-live="polite" aria-relevant="additions text">
          {activeConversation?.messages.length ? (
            activeConversation.messages.map((message) => (
              <article className={`ducky-message is-${message.role}`} key={message.id}>
                {message.role === 'assistant'
                  ? <DuckyAvatar className="ducky-message-avatar" />
                  : <ProfileAvatar
                      className="ducky-message-avatar ducky-user-avatar"
                      name={userName}
                      email={userEmail}
                    />}
                <div className="ducky-message-content">
                  <p>{message.content}</p>
                  <time dateTime={message.createdAt}>
                    {new Date(message.createdAt).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })}
                  </time>
                </div>
              </article>
            ))
          ) : (
            <div className="ducky-welcome">
              <DuckyAvatar className="ducky-welcome-avatar" />
              <h3>How can I help?</h3>
              <p>Ask a question to get started. Ducky can help with your work, explain BRED features, and think through next steps.</p>
            </div>
          )}
          {sending && (
            <div className="ducky-typing" role="status">
              <DuckyAvatar className="ducky-message-avatar" />
              <span>Ducky is thinking…</span>
            </div>
          )}
        </div>

        {error && (
          <div className="ducky-error" role="alert">
            <CircleAlert size={16} aria-hidden="true" />
            <span>{error}</span>
          </div>
        )}

        <form className="ducky-composer" onSubmit={(event) => void askDucky(event)}>
          <label className="visually-hidden" htmlFor="ducky-question">Message Ducky</label>
          <textarea
            id="ducky-question"
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                event.preventDefault();
                event.currentTarget.form?.requestSubmit();
              }
            }}
            maxLength={4000}
            rows={2}
            placeholder="Message Ducky… (Enter to send, Shift+Enter for a new line)"
            disabled={sending}
          />
          <div className="ducky-composer-footer">
            <p>Your prompt and chat context are sent to the configured AI provider. BRED records are excluded unless you approve their use.</p>
            <button className="button button-primary ducky-send" type="submit" disabled={sending || !question.trim()}>
              <Send size={16} aria-hidden="true" />
              <span>{sending ? 'Thinking…' : 'Send'}</span>
            </button>
          </div>
        </form>
      </section>
    </section>
  );
}

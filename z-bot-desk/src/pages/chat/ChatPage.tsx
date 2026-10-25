import React, {useCallback, useEffect, useRef, useState} from 'react';
import {ChatMessage, SessionInfo, useLocalAgent} from '../../hooks/useLocalAgent';
import {useAgentManager} from '../../hooks/useAgentManager';
import {useSessionStore} from '../../stores/sessionStore';
import {AgentList} from '../../components/AgentList';
import {NewAgentDialog} from '../../components/NewAgentDialog';
import {CollapsibleMessage} from '../../components/CollapsibleMessage';
import type {AgentInfo, CreateAgentRequest} from '../../types/agent';

export const ChatPage: React.FC = () => {
    const {chatStream, listSessions, createSession, deleteSession, streaming} = useLocalAgent();
    const {listAgents, createAgent, stopAgent} = useAgentManager();
    const {
        sessions, currentSessionId, setSessions, setCurrentSession, addSession, removeSession,
        agents, currentAgentId, setAgents, setCurrentAgent, addAgent, removeAgent,
    } = useSessionStore();
    const [messages, setMessages] = useState<ChatMessage[]>([]);
    const [input, setInput] = useState('');
    const [replyBuffer, setReplyBuffer] = useState('');
    const [showNewAgentDialog, setShowNewAgentDialog] = useState(false);
    const [showCommandMenu, setShowCommandMenu] = useState(false);
    const scrollRef = useRef<HTMLDivElement>(null);
    const commandMenuRef = useRef<HTMLDivElement>(null);

    // 加载 agent 列表
    useEffect(() => {
        listAgents().then(setAgents).catch(console.error);
    }, []);

    // 加载 session 列表（仅 JAR agent 支持 session 持久化）
    useEffect(() => {
        if (currentAgentId === 'default') {
            listSessions().then(setSessions).catch(console.error);
        }
    }, [currentAgentId]);

    useEffect(() => {
        if (scrollRef.current) {
            scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
        }
    }, [messages, replyBuffer]);

    const currentAgent = agents.find((a) => a.id === currentAgentId);
    const isCliAgent = currentAgent && (currentAgent.type === 'hermes' || currentAgent.type === 'claude-code');

    const handleSend = useCallback(async () => {
        if (!input.trim() || streaming) return;
        const userMsg: ChatMessage = {role: 'user', content: input.trim()};
        const newMessages = [...messages, userMsg];
        setMessages(newMessages);
        setInput('');
        setReplyBuffer('');

        let sessionId = currentSessionId;
        // CLI agent 不需要 session 持久化，使用临时 ID
        if (isCliAgent) {
            sessionId = 'cli-session-' + Date.now();
        } else if (!sessionId) {
            const session = await createSession();
            sessionId = session.id;
            addSession(session);
            setCurrentSession(sessionId);
        }

        const agentType = isCliAgent ? currentAgent!.type : undefined;
        const agentId = isCliAgent ? currentAgent!.id : undefined;

        await chatStream(
            sessionId!,
            newMessages,
            (delta) => setReplyBuffer((prev) => prev + delta),
            (fullReply) => {
                setMessages((prev) => [...prev, {role: 'assistant', content: fullReply}]);
                setReplyBuffer('');
                if (!isCliAgent) {
                    listSessions().then(setSessions).catch(console.error);
                }
            },
            (err) => {
                setMessages((prev) => [...prev, {role: 'assistant', content: `[Error] ${err}`}]);
                setReplyBuffer('');
            },
            agentType,
            agentId
        );
    }, [input, streaming, messages, currentSessionId, chatStream, createSession, addSession, setCurrentSession, listSessions, setSessions, currentAgent, isCliAgent]);

    const handleNewSession = async () => {
        const session = await createSession();
        addSession(session);
        setCurrentSession(session.id);
        setMessages([]);
    };

    const handleSelectSession = async (id: string) => {
        setCurrentSession(id);
        const detail = await listSessions().then((ss: SessionInfo[]) => ss.find((s: SessionInfo) => s.id === id));
        setMessages(detail?.messages || []);
    };

    const handleDeleteSession = async (id: string) => {
        await deleteSession(id);
        removeSession(id);
        if (currentSessionId === id) {
            setMessages([]);
            setCurrentSession(null);
        }
    };

    const handleSelectAgent = (agent: AgentInfo) => {
        setCurrentAgent(agent.id);
        setMessages([]);
    };

    // 清理主群消息
    const handleClearMessages = () => {
        setMessages([]);
        setReplyBuffer('');
        setShowCommandMenu(false);
    };

    // 点击外部关闭指令下拉
    useEffect(() => {
        const handleClickOutside = (e: MouseEvent) => {
            if (commandMenuRef.current && !commandMenuRef.current.contains(e.target as Node)) {
                setShowCommandMenu(false);
            }
        };
        if (showCommandMenu) {
            document.addEventListener('mousedown', handleClickOutside);
        }
        return () => document.removeEventListener('mousedown', handleClickOutside);
    }, [showCommandMenu]);

    const handleCreateAgent = async (req: CreateAgentRequest) => {
        const created = await createAgent(req);
        addAgent(created);
    };

    const handleDeleteAgent = async (agent: AgentInfo) => {
        try {
            await stopAgent(agent.id);
            removeAgent(agent.id);
        } catch (e) {
            console.error('Failed to stop agent:', e);
        }
    };

    return (
        <div className="chat-container">
            <div className="sidebar">
                <AgentList
                    agents={agents}
                    currentAgentId={currentAgentId}
                    onSelect={handleSelectAgent}
                    onCreate={() => setShowNewAgentDialog(true)}
                    onDelete={handleDeleteAgent}
                />
                {!isCliAgent && (
                    <div className="session-list">
                        <div className="session-list-header">
                            <span className="session-list-title">Sessions</span>
                            <button className="new-session-btn" onClick={handleNewSession}>+</button>
                        </div>
                        {sessions.map((s) => (
                            <div
                                key={s.id}
                                className={`session-item ${s.id === currentSessionId ? 'active' : ''}`}
                                onClick={() => handleSelectSession(s.id)}
                            >
                                <span className="session-title">{s.title}</span>
                                <button className="delete-btn" onClick={(e) => {
                                    e.stopPropagation();
                                    handleDeleteSession(s.id);
                                }}>×
                                </button>
                            </div>
                        ))}
                    </div>
                )}
            </div>
            <div className="chat-area">
                <div className="chat-header">
                    {currentAgent && (
                        <span className="current-agent-name">
              {currentAgent.name} ({currentAgent.type})
            </span>
                    )}
                </div>
                <div className="messages" ref={scrollRef}>
                    {messages.map((msg, i) => (
                        <div key={i} className={`message ${msg.role}`}>
                            {msg.role === 'assistant'
                                ? <CollapsibleMessage content={msg.content}/>
                                : <span className="user-text">{msg.content}</span>
                            }
                        </div>
                    ))}
                    {replyBuffer && (
                        <div className="message assistant streaming">
                            <CollapsibleMessage content={replyBuffer} streaming/>
                        </div>
                    )}
                </div>
                <div className="input-wrapper">
                    <div className="command-bar" ref={commandMenuRef}>
                        <button
                            className="command-trigger"
                            onClick={() => setShowCommandMenu(!showCommandMenu)}
                        >
                            指令
                            <span className={`command-arrow ${showCommandMenu ? 'open' : ''}`}>▾</span>
                        </button>
                        {showCommandMenu && (
                            <div className="command-dropdown">
                                <div
                                    className="command-item"
                                    onClick={handleClearMessages}
                                >
                                    <span className="command-name">/clear</span>
                                    <span className="command-desc">清理主群消息</span>
                                </div>
                            </div>
                        )}
                    </div>
                    <div className="input-area">
            <textarea
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => {
                    if (e.key === 'Enter' && !e.shiftKey) {
                        e.preventDefault();
                        handleSend();
                    }
                }}
                placeholder={`给 ${currentAgent?.name || 'Agent'} 发送消息...`}
                rows={1}
            />
                        <button onClick={handleSend} disabled={streaming || !input.trim()}>
                            {streaming ? '...' : 'Send'}
                        </button>
                    </div>
                </div>
            </div>
            {showNewAgentDialog && (
                <NewAgentDialog
                    onClose={() => setShowNewAgentDialog(false)}
                    onCreate={handleCreateAgent}
                />
            )}
        </div>
    );
};

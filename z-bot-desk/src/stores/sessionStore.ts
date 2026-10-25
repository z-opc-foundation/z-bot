import {create} from 'zustand';
import type {SessionInfo} from '../hooks/useLocalAgent';
import type {AgentInfo} from '../types/agent';

interface SessionState {
    sessions: SessionInfo[];
    currentSessionId: string | null;
    agents: AgentInfo[];
    currentAgentId: string | null;
    setSessions: (sessions: SessionInfo[]) => void;
    setCurrentSession: (id: string | null) => void;
    addSession: (session: SessionInfo) => void;
    removeSession: (id: string) => void;
    setAgents: (agents: AgentInfo[]) => void;
    setCurrentAgent: (id: string | null) => void;
    addAgent: (agent: AgentInfo) => void;
    removeAgent: (id: string) => void;
}

export const useSessionStore = create<SessionState>((set) => ({
    sessions: [],
    currentSessionId: null,
    agents: [],
    currentAgentId: 'default',
    setSessions: (sessions) => set({sessions}),
    setCurrentSession: (id) => set({currentSessionId: id}),
    addSession: (session) =>
        set((state) => ({sessions: [session, ...state.sessions]})),
    removeSession: (id) =>
        set((state) => ({
            sessions: state.sessions.filter((s) => s.id !== id),
            currentSessionId:
                state.currentSessionId === id ? null : state.currentSessionId,
        })),
    setAgents: (agents) => set({agents}),
    setCurrentAgent: (id) => set({currentAgentId: id, currentSessionId: null, sessions: []}),
    addAgent: (agent) =>
        set((state) => ({agents: [...state.agents, agent]})),
    removeAgent: (id) =>
        set((state) => ({
            agents: state.agents.filter((a) => a.id !== id),
            currentAgentId: state.currentAgentId === id ? 'default' : state.currentAgentId,
        })),
}));

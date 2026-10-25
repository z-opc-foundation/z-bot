import {useCallback, useState} from 'react';
import type {AgentInfo, CreateAgentRequest} from '../types/agent';

/**
 * Agent 管理 hook — 封装 /v1/agents CRUD API。
 */
export function useAgentManager() {
    const [loading, setLoading] = useState(false);

    const listAgents = useCallback(async (): Promise<AgentInfo[]> => {
        const resp = await fetch('/v1/agents');
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const data = await resp.json();
        return data.agents || [];
    }, []);

    const createAgent = useCallback(async (req: CreateAgentRequest): Promise<AgentInfo> => {
        setLoading(true);
        try {
            const resp = await fetch('/v1/agents', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(req),
            });
            if (!resp.ok) {
                const err = await resp.json().catch(() => ({error: resp.statusText}));
                throw new Error(err.error || `HTTP ${resp.status}`);
            }
            return await resp.json();
        } finally {
            setLoading(false);
        }
    }, []);

    const stopAgent = useCallback(async (id: string): Promise<void> => {
        const resp = await fetch(`/v1/agents/${id}`, {method: 'DELETE'});
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    }, []);

    return {listAgents, createAgent, stopAgent, loading};
}

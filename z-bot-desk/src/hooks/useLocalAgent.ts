import {useCallback, useRef, useState} from 'react';

export interface ChatMessage {
    role: 'system' | 'user' | 'assistant';
    content: string;
}

export interface SessionInfo {
    id: string;
    title: string;
    model: string;
    createdAt: number;
    updatedAt: number;
    messages?: ChatMessage[];
}

/**
 * 本地 Agent HTTP 调用封装（同源，无需指定端口）。
 */
export function useLocalAgent() {
    const [streaming, setStreaming] = useState(false);
    const abortRef = useRef<AbortController | null>(null);

    /**
     * 流式聊天：POST /v1/chat/completions (SSE)
     * @param agentType agent 类型（jar/hermes/claude-code），默认 jar
     * @param agentId CLI agent ID（agentType 非 jar 时必须）
     */
    const chatStream = useCallback(
        async (
            sessionId: string,
            messages: ChatMessage[],
            onDelta: (text: string) => void,
            onDone: (fullReply: string) => void,
            onError: (err: string) => void,
            agentType?: string,
            agentId?: string
        ) => {
            setStreaming(true);
            const controller = new AbortController();
            abortRef.current = controller;
            let fullReply = '';

            try {
                const body: Record<string, any> = {messages, stream: true, session_id: sessionId};
                if (agentType && agentType !== 'jar') {
                    body.agent_type = agentType;
                    body.agent_id = agentId;
                }
                const response = await fetch('/v1/chat/completions', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify(body),
                    signal: controller.signal,
                });

                if (!response.ok) {
                    onError(`HTTP ${response.status}`);
                    return;
                }

                const reader = response.body!.getReader();
                const decoder = new TextDecoder();
                let buffer = '';

                while (true) {
                    const {done, value} = await reader.read();
                    if (done) break;

                    buffer += decoder.decode(value, {stream: true});
                    const lines = buffer.split('\n');
                    buffer = lines.pop() || '';

                    for (const line of lines) {
                        if (!line.startsWith('data: ')) continue;
                        const data = line.slice(6).trim();
                        if (data === '[DONE]') {
                            onDone(fullReply);
                            return;
                        }
                        try {
                            const json = JSON.parse(data);
                            const delta = json.choices?.[0]?.delta?.content;
                            if (delta) {
                                fullReply += delta;
                                onDelta(delta);
                            }
                        } catch {
                            // ignore parse errors
                        }
                    }
                }
                onDone(fullReply);
            } catch (err: any) {
                if (err.name !== 'AbortError') {
                    onError(err.message || 'Stream error');
                }
            } finally {
                setStreaming(false);
                abortRef.current = null;
            }
        },
        []
    );

    const listSessions = useCallback(
        () => fetch('/v1/sessions').then((r) => r.json()),
        []
    );

    const createSession = useCallback(
        (title?: string, model?: string) =>
            fetch('/v1/sessions', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({title, model}),
            }).then((r) => r.json()),
        []
    );

    const getSession = useCallback(
        (id: string) => fetch(`/v1/sessions/${id}`).then((r) => r.json()),
        []
    );

    const deleteSession = useCallback(
        (id: string) => fetch(`/v1/sessions/${id}`, {method: 'DELETE'}),
        []
    );

    const cancel = useCallback(() => {
        abortRef.current?.abort();
    }, []);

    return {chatStream, listSessions, createSession, getSession, deleteSession, cancel, streaming};
}

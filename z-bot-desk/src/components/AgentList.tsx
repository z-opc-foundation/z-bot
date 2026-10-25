import React from 'react';
import type {AgentInfo} from '../types/agent';
import {AGENT_TYPE_COLORS, AGENT_TYPE_LABELS} from '../types/agent';

interface AgentListProps {
    agents: AgentInfo[];
    currentAgentId: string | null;
    onSelect: (agent: AgentInfo) => void;
    onCreate: () => void;
    onDelete: (agent: AgentInfo) => void;
}

export const AgentList: React.FC<AgentListProps> = ({
                                                        agents,
                                                        currentAgentId,
                                                        onSelect,
                                                        onCreate,
                                                        onDelete,
                                                    }) => {
    return (
        <div className="agent-list">
            <div className="agent-list-header">
                <span className="agent-list-title">Agents</span>
            </div>
            <div className="agent-items">
                {agents.map((agent) => (
                    <div
                        key={agent.id}
                        className={`agent-item ${agent.id === currentAgentId ? 'active' : ''}`}
                        onClick={() => onSelect(agent)}
                    >
            <span
                className="agent-type-badge"
                style={{backgroundColor: AGENT_TYPE_COLORS[agent.type] || '#999'}}
            >
              {AGENT_TYPE_LABELS[agent.type] || agent.type}
            </span>
                        <span className="agent-name">{agent.name}</span>
                        <span className={`agent-status status-${agent.status}`} title={agent.status}/>
                        {agent.type !== 'jar' && (
                            <button
                                className="agent-delete-btn"
                                onClick={(e) => {
                                    e.stopPropagation();
                                    onDelete(agent);
                                }}
                                title="删除"
                            >
                                ×
                            </button>
                        )}
                    </div>
                ))}
            </div>
            <button className="launch-agent-btn" onClick={onCreate}>
                ＋ 拉起 Agent
            </button>
        </div>
    );
};

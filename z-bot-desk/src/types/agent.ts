export type AgentType = 'jar' | 'hermes' | 'claude-code';
export type AgentStatus = 'running' | 'stopped' | 'error';

export interface AgentInfo {
    id: string;
    name: string;
    type: AgentType;
    status: AgentStatus;
    createdAt: number;
    command?: string;
    args?: string[];
    workingDir?: string;
}

export interface CreateAgentRequest {
    name: string;
    type: AgentType;
    command?: string;
    args?: string[];
    workingDir?: string;
}

export const AGENT_TYPE_LABELS: Record<AgentType, string> = {
    'jar': '自研',
    'hermes': 'Hermes',
    'claude-code': 'Claude Code',
};

export const AGENT_TYPE_COLORS: Record<AgentType, string> = {
    'jar': '#2F70F1',
    'hermes': '#9C27B0',
    'claude-code': '#FF6F00',
};

export const AGENT_TYPE_DESCRIPTIONS: Record<AgentType, string> = {
    'jar': '自研 Java Agent，内置 ReAct 推理引擎、工具调用与多轮对话',
    'hermes': '本地 Hermes CLI，支持 tool-calling、多模型切换与技能扩展',
    'claude-code': 'Anthropic Claude CLI 编码助手，擅长代码生成与重构',
};

export const AGENT_TYPE_ICONS: Record<AgentType, string> = {
    'jar': '⚙',
    'hermes': '⚡',
    'claude-code': '✦',
};

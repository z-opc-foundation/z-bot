import React, {useState} from 'react';
import type {AgentType, CreateAgentRequest} from '../types/agent';
import {AGENT_TYPE_COLORS, AGENT_TYPE_DESCRIPTIONS, AGENT_TYPE_ICONS, AGENT_TYPE_LABELS} from '../types/agent';

interface NewAgentDialogProps {
    onClose: () => void;
    onCreate: (req: CreateAgentRequest) => Promise<void>;
}

export const NewAgentDialog: React.FC<NewAgentDialogProps> = ({onClose, onCreate}) => {
    const [step, setStep] = useState<'select' | 'config'>('select');
    const [type, setType] = useState<AgentType | null>(null);
    const [name, setName] = useState('');
    const [command, setCommand] = useState('');
    const [workingDir, setWorkingDir] = useState('');
    const [error, setError] = useState('');
    const [submitting, setSubmitting] = useState(false);

    const isCli = type === 'hermes' || type === 'claude-code';

    const handleSelectType = (t: AgentType) => {
        setType(t);
        setName(t === 'hermes' ? 'my-hermes' : t === 'claude-code' ? 'my-claude' : '');
        setStep('config');
    };

    const handleBack = () => {
        setStep('select');
        setError('');
    };

    const handleSubmit = async () => {
        if (!type || !name.trim()) {
            setError('请输入 Agent 名称');
            return;
        }
        setSubmitting(true);
        setError('');
        try {
            const req: CreateAgentRequest = {name: name.trim(), type};
            if (isCli) {
                if (command.trim()) req.command = command.trim();
                if (workingDir.trim()) req.workingDir = workingDir.trim();
            }
            await onCreate(req);
            onClose();
        } catch (e: any) {
            setError(e.message || '创建失败');
        } finally {
            setSubmitting(false);
        }
    };

    const allTypes: AgentType[] = ['jar', 'hermes', 'claude-code'];

    return (
        <div className="dialog-overlay" onClick={onClose}>
            <div className="dialog-content" onClick={(e) => e.stopPropagation()}>
                {step === 'select' ? (
                    <>
                        <h3>选择 Agent 类型</h3>
                        <p className="dialog-subtitle">选择要拉起的本地 Agent</p>
                        <div className="agent-type-cards">
                            {allTypes.map((t) => (
                                <div
                                    key={t}
                                    className={`agent-type-card ${t === 'jar' ? 'disabled' : ''}`}
                                    onClick={() => t !== 'jar' && handleSelectType(t)}
                                >
                                    <div className="card-icon" style={{color: AGENT_TYPE_COLORS[t]}}>
                                        {AGENT_TYPE_ICONS[t]}
                                    </div>
                                    <div className="card-body">
                                        <div className="card-title">{AGENT_TYPE_LABELS[t]}</div>
                                        <div className="card-desc">{AGENT_TYPE_DESCRIPTIONS[t]}</div>
                                    </div>
                                    {t === 'jar' && <span className="card-badge">已运行</span>}
                                </div>
                            ))}
                        </div>
                        <div className="dialog-actions">
                            <button className="btn-cancel" onClick={onClose}>取消</button>
                        </div>
                    </>
                ) : (
                    <>
                        <h3>配置 {AGENT_TYPE_LABELS[type!]}</h3>
                        <div className="selected-type-bar">
              <span className="selected-type-icon" style={{color: AGENT_TYPE_COLORS[type!]}}>
                {AGENT_TYPE_ICONS[type!]}
              </span>
                            <span>{AGENT_TYPE_DESCRIPTIONS[type!]}</span>
                        </div>

                        <div className="form-group">
                            <label>Agent 名称</label>
                            <input
                                type="text"
                                value={name}
                                onChange={(e) => setName(e.target.value)}
                                placeholder="例如：my-hermes"
                                autoFocus
                            />
                        </div>

                        {isCli && (
                            <>
                                <div className="form-group">
                                    <label>CLI 命令（可选，留空使用默认）</label>
                                    <input
                                        type="text"
                                        value={command}
                                        onChange={(e) => setCommand(e.target.value)}
                                        placeholder={type === 'claude-code' ? 'claude' : 'hermes'}
                                    />
                                </div>
                                <div className="form-group">
                                    <label>工作目录（可选）</label>
                                    <input
                                        type="text"
                                        value={workingDir}
                                        onChange={(e) => setWorkingDir(e.target.value)}
                                        placeholder="~/.z-team/agents/{name}"
                                    />
                                </div>
                            </>
                        )}

                        {error && <div className="dialog-error">{error}</div>}

                        <div className="dialog-actions">
                            <button className="btn-cancel" onClick={handleBack}>← 返回</button>
                            <button className="btn-submit" onClick={handleSubmit} disabled={submitting}>
                                {submitting ? '创建中...' : '拉起'}
                            </button>
                        </div>
                    </>
                )}
            </div>
        </div>
    );
};

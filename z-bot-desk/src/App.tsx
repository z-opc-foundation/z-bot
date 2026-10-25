import React, {useEffect, useState} from 'react';
import {ChatPage} from './pages/chat/ChatPage';

const App: React.FC = () => {
    const [agentStatus, setAgentStatus] = useState<'starting' | 'ready' | 'error'>('starting');

    useEffect(() => {
        const checkHealth = async () => {
            try {
                const resp = await fetch('/health');
                if (resp.ok) {
                    setAgentStatus('ready');
                } else {
                    setAgentStatus('error');
                }
            } catch {
                setAgentStatus('error');
            }
        };
        checkHealth();
        const interval = setInterval(checkHealth, 5000);
        return () => clearInterval(interval);
    }, []);

    if (agentStatus === 'starting') {
        return <div className="loading">Starting local agent...</div>;
    }

    if (agentStatus === 'error') {
        return <div className="error">Failed to connect to local agent. Please check if the agent is running.</div>;
    }

    return <ChatPage/>;
};

export default App;

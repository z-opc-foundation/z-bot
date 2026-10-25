import React, {useLayoutEffect, useRef, useState} from 'react';
import {MarkdownRenderer} from './MarkdownRenderer';

/** 折叠态最大高度（px），约12行正文，占桌面端可视区约41% */
const MAX_COLLAPSED_HEIGHT = 280;

interface CollapsibleMessageProps {
    content: string;
    /** 流式输出中——不折叠，保持完全可见 */
    streaming?: boolean;
}

/**
 * 可折叠的消息容器：
 * - 内容渲染高度超过 MAX_COLLAPSED_HEIGHT 时自动折叠
 * - 折叠态底部显示渐变遮罩 +「展开全文」按钮
 * - 点击展开后显示全部内容，底部切换为「收起」按钮
 * - streaming 期间不折叠
 */
export const CollapsibleMessage: React.FC<CollapsibleMessageProps> = ({content, streaming = false}) => {
    const [expanded, setExpanded] = useState(false);
    const [overflowing, setOverflowing] = useState(false);
    const contentRef = useRef<HTMLDivElement>(null);

    useLayoutEffect(() => {
        const el = contentRef.current;
        if (!el) return;
        if (streaming) {
            setOverflowing(false);
            setExpanded(false);
            return;
        }
        // scrollHeight 返回内容完整高度，不受 max-height 影响
        setOverflowing(el.scrollHeight > MAX_COLLAPSED_HEIGHT);
    }, [content, streaming]);

    const canCollapse = overflowing && !streaming;
    const isCollapsed = canCollapse && !expanded;

    return (
        <div className={`collapsible-msg ${isCollapsed ? 'collapsed' : ''} ${expanded ? 'expanded' : ''}`}>
            <div
                ref={contentRef}
                className="collapsible-msg-content"
                style={isCollapsed ? {maxHeight: `${MAX_COLLAPSED_HEIGHT}px`} : undefined}
            >
                <MarkdownRenderer content={content}/>
            </div>
            {canCollapse && (
                <div
                    className="collapsible-msg-toggle"
                    onClick={() => setExpanded((prev) => !prev)}
                >
                    <span className="toggle-text">{expanded ? '收起' : '展开全文'}</span>
                    <span className={`toggle-arrow ${expanded ? 'up' : ''}`}>▾</span>
                </div>
            )}
        </div>
    );
};

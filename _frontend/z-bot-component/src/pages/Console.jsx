/** z-bot 能力地图：模块清单 + CLI 用法（z-bot-core 为 CLI/TUI 运行时，无 REST 层）。 */
import {Card, Col, Row, Space, Typography} from 'antd'
import {
    ApiOutlined,
    AudioOutlined,
    BellOutlined,
    ClockCircleOutlined,
    DatabaseOutlined,
    MessageOutlined,
    RocketOutlined,
    ToolOutlined,
    UserOutlined,
} from '@ant-design/icons'

const {Title, Paragraph, Text} = Typography

const MODULES = [
    {icon: <MessageOutlined/>, name: 'agent / session', desc: 'Agent 会话运行时：多轮对话、上下文、checkpoint 续跑'},
    {icon: <ApiOutlined/>, name: 'channel', desc: '消息渠道接入：Terminal / HTTP / 飞书 / 钉钉，ChannelBus + Supervisor 配送'},
    {icon: <ToolOutlined/>, name: 'tool / mcp', desc: '工具调用与 MCP 客户端桥接'},
    {icon: <AudioOutlined/>, name: 'llm', desc: '模型接入层（多 provider / 多模型路由）'},
    {icon: <DatabaseOutlined/>, name: 'memory / store', desc: '会话记忆与本地存储'},
    {icon: <ClockCircleOutlined/>, name: 'cron', desc: '定时任务调度'},
    {icon: <UserOutlined/>, name: 'skill / slash', desc: '技能包与 slash 命令扩展'},
    {icon: <RocketOutlined/>, name: 'acp / delegate', desc: 'Agent 通信协议与子代理委派'},
    {icon: <BellOutlined/>, name: 'center / ui / cli', desc: 'CLI 命令面（sessions 等）与 TUI 交互'},
]

export default function Console() {
    return (
        <div>
            <Space style={{marginBottom: 8}}>
                <Title level={4} style={{margin: 0}}>z-bot 能力地图</Title>
            </Space>
            <Paragraph type="secondary">
                z-bot 是 CLI/TUI 形态的 agent 运行时（z-bot-core 无 REST 层），本页为模块地图与上手指引；
                管理面 API 接入预留 configureBot({'{apiBase}'}）。
            </Paragraph>

            <Row gutter={[16, 16]}>
                {MODULES.map((m) => (
                    <Col key={m.name} span={8}>
                        <Card size="small">
                            <Space>
                                <span style={{fontSize: 18}}>{m.icon}</span>
                                <div>
                                    <Text strong>{m.name}</Text>
                                    <br/>
                                    <Text type="secondary" style={{fontSize: 12}}>{m.desc}</Text>
                                </div>
                            </Space>
                        </Card>
                    </Col>
                ))}
            </Row>

            <Card title="CLI 上手" style={{marginTop: 16}}>
                <pre style={{margin: 0, fontSize: 12, lineHeight: 1.9}}>{`# 启动交互（TUI）
zbot

# 会话管理
zbot sessions

# 指定渠道/模型
zbot --channel terminal --model <provider>/<model>

# 技能即 slash 命令
/skill-name [args]`}</pre>
            </Card>
        </div>
    )
}

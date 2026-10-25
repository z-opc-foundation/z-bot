import { Component, useEffect, useState } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AppLayout } from '@yuku123/z-frontend-common'
import { menuItems, routes, HomePage, LoginPage } from '@yuku123/z-bot-component/pages'
import { Result, Button } from 'antd'

class ErrorBoundary extends Component {
    constructor(props) { super(props); this.state = { err: null } }
    static getDerivedStateFromError(err) { return { err } }
    componentDidCatch(err, info) { console.error('[App ErrorBoundary]', err, info) }
    render() {
        if (this.state.err) return <pre style={{ padding: 24, color: 'red', whiteSpace: 'pre-wrap' }}>{String(this.state.err?.stack || this.state.err)}</pre>
        return this.props.children
    }
}

function NotFound() {
    return (
        <Result
            status="404"
            title="页面不存在"
            subTitle="路由表里没有这条路径。"
            extra={<Button type="primary" onClick={() => { window.location.href = '/z-bot/home' }}>去首页</Button>}
        />
    )
}

// mock 账号不写死在仓里（001 凭证规范）：构建期注入 VITE_MOCK_USER / VITE_MOCK_PASSWORD；
// 未注入 ⇒ 不传 mockAuth，登录页走「未配置 mock 账号」提示（SSO 接入后换真实 loginApi）。
const MOCK_AUTH = import.meta.env.VITE_MOCK_USER && import.meta.env.VITE_MOCK_PASSWORD
    ? {
        username: import.meta.env.VITE_MOCK_USER,
        password: import.meta.env.VITE_MOCK_PASSWORD,
        user: { name: import.meta.env.VITE_MOCK_USER, role: '管理员' },
    }
    : null

// lead 008 §16：CTC/SSO 接入前的 mock 登录（账号由构建期 VITE_MOCK_USER / VITE_MOCK_PASSWORD 注入，经 prop 传给组件）
function LoginRoute() {
    return <LoginPage mockAuth={MOCK_AUTH} />
}

function ProtectedShell() {
    const [user, setUser] = useState(null)
    const [ready, setReady] = useState(false)

    useEffect(() => {
        if (!localStorage.getItem('token')) {
            window.location.replace('/z-bot/login')
            return
        }
        const raw = localStorage.getItem('userInfo')
        if (raw) { try { setUser(JSON.parse(raw)) } catch { setUser({ name: raw }) } }
        setReady(true)
    }, [])

    if (!ready) return null

    return (
        <AppLayout
            menuItems={menuItems}
            appTitle="z-bot Agent 运行时"
            appVersion="0.1.0"
            appUser={user}
            appIcon={{ icon: <img src="/icon.png" alt="z-bot Agent 运行时" style={{ width: '100%', height: '100%', objectFit: 'cover', borderRadius: 8 }} />, color: '#ef4444', label: 'z-bot Agent 运行时' }}
        />
    )
}

/** lead 008 §16 suit 一次整合：登录路由 + 鉴权壳 + URL 即状态（§11/§14）。 */
export default function App() {
    return (
        <ErrorBoundary>
            <BrowserRouter>
                <Routes>
                    <Route path="/z-bot/login" element={<LoginRoute />} />
                    <Route element={<ProtectedShell />}>
                        <Route path="/" element={<Navigate to={menuItems[0].key} replace />} />
                        {routes.map((r) => (
                            <Route key={r.path} path={r.path} element={<r.Component />} />
                        ))}
                        <Route path="*" element={<NotFound />} />
                    </Route>
                </Routes>
            </BrowserRouter>
        </ErrorBoundary>
    )
}
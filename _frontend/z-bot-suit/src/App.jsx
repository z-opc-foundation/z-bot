import {Navigate, Route, Routes} from 'react-router-dom'
import {AppLayout} from '../../../../_shared/z-frontend-common-local/dist/z-frontend-common.es.js'
import {menuItems, routeTable} from '@yuku123/z-bot-component/pages'

export default function App() {
    return (
        <Routes>
            <Route path="/" element={<Navigate to="/console" replace/>}/>
            <Route path="/" element={
                <AppLayout menuItems={menuItems} appTitle="z-bot Agent 运行时" appShort="BOT" appIcon={{icon: <img src="/icon.png" alt="BOT" style={{width: "100%", height: "100%", objectFit: "cover", borderRadius: 8}}/>, color: '#ef4444', label: 'BOT'}}/>
            }>
                {routeTable.map((r) => (
                    <Route key={r.path} path={r.path} element={<r.Component/>}/>
                ))}
            </Route>
        </Routes>
    )
}

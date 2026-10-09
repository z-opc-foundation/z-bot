import {Navigate, Route, Routes} from 'react-router-dom'
import {AppLayout} from '@yuku123/z-frontend-common'
import {menuItems, routeTable} from '@yuku123/z-bot-component/pages'

export default function App() {
    return (
        <Routes>
            <Route path="/" element={<Navigate to="/console" replace/>}/>
            <Route path="/" element={
                <AppLayout menuItems={menuItems} appTitle="z-bot Agent 运行时" appShort="BOT"/>
            }>
                {routeTable.map((r) => (
                    <Route key={r.path} path={r.path} element={<r.Component/>}/>
                ))}
            </Route>
        </Routes>
    )
}

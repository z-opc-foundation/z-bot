/**
 * z-bot API client。
 * z-bot 是 CLI/TUI 形态的 agent 运行时（z-bot-core 无 REST 层），
 * 这里不绑定任何后端端点；configureBot 预留 apiBase 以便后续接入管理面。
 */
import {createRequest} from '@yuku123/z-frontend-common'

const request = createRequest({baseURL: '', tokenKey: 'zbot_token'})

export default request

export function configureBot(config) {
    if (config && config.apiBase !== undefined) {
        request.defaults.baseURL = config.apiBase
    }
}

export const botApi = {}

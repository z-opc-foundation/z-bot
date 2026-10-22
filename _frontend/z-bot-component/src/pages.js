// 整份转发，不要手挑名字：原先写死的一组名字漏了 HomePage / LoginPage，
// 而 suit 的 App.jsx 正好 import 这两个，于是 rollup 报
// "HomePage" is not exported by dist/pages.js。再挑一次名字还会重演。
export * from './pages-manifest.jsx'
export {configureBot} from './services/api.js'

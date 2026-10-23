const BASE = 'http://127.0.0.1:18081/api'
let session = ''
let csrf = ''
const key = () => `ln_${Date.now()}_${Math.random().toString(36).slice(2, 12)}`
function request(path, method = 'GET', data, commandKey) {
  return new Promise((resolve, reject) => {
    wx.request({ url: BASE + path, method, data,
      header: { 'content-type': 'application/json', ...(session ? { Cookie: session } : {}), ...(csrf ? { 'X-CSRF-TOKEN': csrf } : {}), ...(commandKey ? { 'Idempotency-Key': commandKey } : {}) },
      success(res) {
        const cookie = res.header['Set-Cookie'] || res.header['set-cookie']
        if (cookie) session = String(cookie).split(';')[0]
        if (res.statusCode >= 200 && res.statusCode < 300) resolve(res.data)
        else { const error = new Error(res.data?.message || '操作失败，请刷新后重试'); error.status = res.statusCode; reject(error) }
      },
      fail() { reject(new Error('暂时连接不到 Lunaria。请检查本机后端；操作结果未知时请用原按钮重试。')) }
    })
  })
}
async function refresh() { const data = await request('/session'); csrf = data.csrf; return data }
async function login(username) {
  session = ''; csrf = ''; await refresh()
  await request('/demo/login', 'POST', { username, password: username === 'owner' ? 'lunaria-owner-demo' : 'lunaria-customer-demo' })
  return refresh()
}
async function mutate(path, data = {}) {
  // Persist a stable intent across network failures. A retry after a lost response must not charge twice.
  const storageKey = `intent:${path}:${JSON.stringify(data)}`
  const intent = wx.getStorageSync(storageKey) || key(); wx.setStorageSync(storageKey, intent)
  try { const result = await request(path, 'POST', data, intent); wx.removeStorageSync(storageKey); return result }
  catch (error) { if (error.status && error.status < 500) wx.removeStorageSync(storageKey); throw error }
}
module.exports = { request, refresh, login, mutate }

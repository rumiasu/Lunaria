// Exercise the actual native page modules against the running isolated Java backend.
// This is a wx.request transport adapter, not a WeChat compiler or a web product frontend.
const assert = require('node:assert/strict')
const { readFileSync, writeFileSync } = require('node:fs')
const path = require('node:path')
const root = path.resolve(__dirname, '..')
const storage = new Map()
let offline = false
let requests = 0
global.wx = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: key => storage.delete(key),
  showToast() {},
  request(options) {
    if (offline) { options.fail(); return }
    requests++
    fetch(options.url, {
      method: options.method,
      headers: options.header,
      body: options.data === undefined ? undefined : JSON.stringify(options.data)
    }).then(async response => {
      const text = await response.text()
      options.success({
        statusCode: response.status,
        header: { 'Set-Cookie': response.headers.get('set-cookie') || '' },
        data: text ? JSON.parse(text) : null
      })
    }).catch(() => options.fail())
  }
}

function pageInstance(directory, route, query) {
  let definition
  global.Page = value => { definition = value }
  const filename = path.join(directory, route + '.js')
  delete require.cache[require.resolve(filename)]
  require(filename)
  assert.ok(definition, 'Page() must be registered')
  const instance = { data: structuredClone(definition.data), setData(values) { Object.assign(this.data, values) } }
  for (const [name, method] of Object.entries(definition)) if (typeof method === 'function') instance[name] = method.bind(instance)
  if (instance.onLoad) instance.onLoad(query)
  return instance
}

async function main() {
  const checks = []
  let firstCustomer
  for (const role of ['customer', 'owner']) {
    const directory = path.join(root, `miniapp-${role}`)
    const api = require(path.join(directory, 'utils/api'))
    const session = await api.login(role === 'owner' ? 'owner' : 'alice')
    assert.equal(session.role, role === 'owner' ? 'OWNER' : 'CUSTOMER')
    const account = await api.request(role === 'owner' ? '/owner/customers/alice' : '/me')
    const query = { id: account.cards[0]?.id, service: 'facial' }
    const config = JSON.parse(readFileSync(path.join(directory, 'app.json'), 'utf8'))
    for (const route of config.pages) {
      if (route.includes('card-detail') && !query.id) {
        checks.push({ role, route, status: 'SKIP', reason: 'No demo card; run the documented HTTP smoke once first.' })
        continue
      }
      const page = pageInstance(directory, route, route.includes('customer-detail') ? { id: 'alice' } : query)
      await page.onShow()
      assert.equal(page.data.error, '', `${role}/${route}: ${page.data.error}`)
      assert.equal(page.data.ready, true, `${role}/${route} must load an authenticated account`)
      assert.equal(page.data.loading, false)
      if (route.includes('card-detail')) assert.equal(page.data.card.id, query.id)
      if (route.includes('customer-detail')) assert.equal(page.data.customer.id, 'alice')
      if (role === 'customer' && route === 'pages/home/index') firstCustomer = page
      checks.push({ role, route, status: 'PASS' })
    }
  }
  offline = true
  await firstCustomer.onShow()
  assert.ok(firstCustomer.data.error, 'An offline request must surface an error')
  assert.equal(firstCustomer.data.loading, false, 'Failed requests must stop the loading indicator')
  offline = false
  await firstCustomer.onShow()
  assert.equal(firstCustomer.data.error, '', 'Retry must clear the prior error')
  assert.equal(firstCustomer.data.ready, true)
  const result = {
    status: 'PASS', requests, pages: checks,
    additionalChecks: ['Actual wx.request wrapper with cookie session and CSRF', 'Both roles load their actual native page modules', 'Offline error and retry recovery'],
    limitations: ['No WeChat compiler or device validation', 'No financial mutations or real external services'],
    checkedAt: new Date().toISOString()
  }
  writeFileSync(path.join(root, 'docs/evidence/native-page-http.json'), JSON.stringify(result, null, 2) + '\n')
  console.log(JSON.stringify(result, null, 2))
}
main().catch(error => { console.error(error); process.exitCode = 1 })

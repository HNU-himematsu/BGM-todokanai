import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'

const [url, output, widthArg = '390', heightArg = '844', dprArg = '2', fullPageArg = '0'] = process.argv.slice(2)
if (!url || !output) {
  throw new Error('Usage: node capture-url.mjs <url> <output.png> [width] [height] [dpr] [fullPage]')
}

const width = Number(widthArg)
const height = Number(heightArg)
const dpr = Number(dprArg)
const fullPage = fullPageArg === '1'

const chromeCandidates = [
  process.env.CHROME_PATH,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Google', 'Chrome', 'Application', 'chrome.exe'),
].filter(Boolean)

const chromePath = chromeCandidates.find((candidate) => existsSync(candidate))
if (!chromePath) throw new Error('Chrome not found. Set CHROME_PATH.')

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function waitForDebugger(port) {
  for (let attempt = 0; attempt < 120; attempt += 1) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/version`)
      if (response.ok) return await response.json()
    } catch {
      // Chrome is still starting.
    }
    await sleep(100)
  }
  throw new Error('Chrome debugger endpoint did not become ready.')
}

function createClient(webSocketUrl) {
  const socket = new WebSocket(webSocketUrl)
  const pending = new Map()
  let sequence = 0

  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    if (!message.id || !pending.has(message.id)) return
    const { resolve, reject } = pending.get(message.id)
    pending.delete(message.id)
    if (message.error) reject(new Error(`${message.error.code}: ${message.error.message}`))
    else resolve(message.result)
  })

  const ready = new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true })
    socket.addEventListener('error', reject, { once: true })
  })

  function send(method, params = {}, sessionId) {
    return new Promise((resolve, reject) => {
      const id = ++sequence
      pending.set(id, { resolve, reject })
      socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }))
    })
  }

  return { socket, ready, send }
}

const port = 9700 + Math.floor(Math.random() * 200)
const profileDir = await mkdtemp(path.join(tmpdir(), 'bgm-live-capture-'))
const chrome = spawn(chromePath, [
  '--headless=new',
  '--disable-gpu',
  '--hide-scrollbars',
  '--no-first-run',
  '--no-default-browser-check',
  '--disable-extensions',
  '--disable-dev-shm-usage',
  `--remote-debugging-port=${port}`,
  `--user-data-dir=${profileDir}`,
  'about:blank',
], { stdio: 'ignore' })

try {
  const version = await waitForDebugger(port)
  const client = createClient(version.webSocketDebuggerUrl)
  await client.ready

  const { targetId } = await client.send('Target.createTarget', { url: 'about:blank' })
  const { sessionId } = await client.send('Target.attachToTarget', { targetId, flatten: true })
  await client.send('Page.enable', {}, sessionId)
  await client.send('Runtime.enable', {}, sessionId)
  await client.send('Emulation.setDeviceMetricsOverride', {
    width,
    height,
    deviceScaleFactor: dpr,
    mobile: true,
    screenWidth: width,
    screenHeight: height,
  }, sessionId)
  await client.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 }, sessionId)
  await client.send('Page.navigate', { url }, sessionId)

  for (let attempt = 0; attempt < 200; attempt += 1) {
    const ready = await client.send('Runtime.evaluate', {
      expression: 'document.readyState',
      returnByValue: true,
    }, sessionId)
    if (ready.result?.value === 'complete') break
    await sleep(50)
  }
  await client.send('Runtime.evaluate', {
    expression: 'document.fonts.ready.then(() => true)',
    awaitPromise: true,
    returnByValue: true,
  }, sessionId)
  await sleep(400)

  const contentSize = await client.send('Runtime.evaluate', {
    expression: '({ width: Math.ceil(document.documentElement.scrollWidth), height: Math.ceil(document.documentElement.scrollHeight) })',
    returnByValue: true,
  }, sessionId)
  const content = contentSize.result.value

  if (fullPage) {
    await client.send('Emulation.setDeviceMetricsOverride', {
      width,
      height: content.height,
      deviceScaleFactor: dpr,
      mobile: true,
      screenWidth: width,
      screenHeight: content.height,
    }, sessionId)
    await sleep(200)
  }

  const shot = await client.send('Page.captureScreenshot', {
    format: 'png',
    fromSurface: true,
  }, sessionId)
  await mkdir(path.dirname(output), { recursive: true })
  await writeFile(output, Buffer.from(shot.data, 'base64'))
  console.log(`${output} ${fullPage ? `${width * dpr} x ${Math.round(content.height * dpr)}` : `${width * dpr} x ${height * dpr}`}`)
  client.socket.close()
} finally {
  chrome.kill('SIGKILL')
  await Promise.race([
    new Promise((resolve) => chrome.once('exit', resolve)),
    sleep(1500),
  ])
  await rm(profileDir, { recursive: true, force: true }).catch(() => {})
}

process.exit(process.exitCode || 0)

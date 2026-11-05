import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(scriptDir, '..', '..')
const buildDir = path.join(scriptDir, '.build')
const baseUrl = (process.env.PREVIEW_BASE_URL || 'http://127.0.0.1:5173').replace(/\/$/, '')
const viewport = { width: 390, height: 844, dpr: 2 }

const participantId = 'p-preview-0001'
const playerId = 'offline-preview-0001'
const nickname = '雨夜听歌人'
const questionSetVersion = 'preview-qsv-1'
const eventName = '旮旯原声猜歌夜'

const chromeCandidates = [
  process.env.CHROME_PATH,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Google', 'Chrome', 'Application', 'chrome.exe'),
].filter(Boolean)

const chromePath = chromeCandidates.find((candidate) => existsSync(candidate))
if (!chromePath) throw new Error('Chrome not found. Set CHROME_PATH.')

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

function buildSilentWav(durationSeconds, sampleRate = 8000) {
  const sampleCount = Math.round(durationSeconds * sampleRate)
  const dataSize = sampleCount * 2
  const buffer = Buffer.alloc(44 + dataSize)
  buffer.write('RIFF', 0)
  buffer.writeUInt32LE(36 + dataSize, 4)
  buffer.write('WAVE', 8)
  buffer.write('fmt ', 12)
  buffer.writeUInt32LE(16, 16)
  buffer.writeUInt16LE(1, 20)
  buffer.writeUInt16LE(1, 22)
  buffer.writeUInt32LE(sampleRate, 24)
  buffer.writeUInt32LE(sampleRate * 2, 28)
  buffer.writeUInt16LE(2, 32)
  buffer.writeUInt16LE(16, 34)
  buffer.write('data', 36)
  buffer.writeUInt32LE(dataSize, 40)
  return buffer
}

const audioWav = buildSilentWav(35)

const onlineQuestions = [
  {
    index: 0,
    id: 'q1',
    prompt: '这段旋律出自哪部作品？',
    type: 'WORK',
    difficulty: 'EASY',
    options: ['CLANNAD', 'Air', 'Kanon', 'Little Busters!'],
    author: '姬松',
    audioStartMs: 0,
    audioEndMs: 8000,
    answerStartMs: 1000,
    answerEndMs: 6000,
  },
  {
    index: 1,
    id: 'q2',
    prompt: '这段氛围音乐来自哪部作品？',
    type: 'ATMOSPHERE',
    difficulty: 'MEDIUM',
    options: ['月姬', 'Fate/stay night', '空之境界', '魔法使之夜'],
    author: '姬松',
    audioStartMs: 6000,
    audioEndMs: 16000,
    answerStartMs: 9000,
    answerEndMs: 14000,
  },
  {
    index: 2,
    id: 'q3',
    prompt: '这段旋律对应哪首主题曲？',
    type: 'WORK',
    difficulty: 'HARD',
    options: ['鸟之诗', '团子大家族', 'Last regrets', 'Little Busters!'],
    author: '姬松',
    audioStartMs: 14000,
    audioEndMs: 32000,
    answerStartMs: 17000,
    answerEndMs: 22000,
  },
]

const questionSet = {
  eventId: 'demo',
  eventName,
  audioUrl: '/api/events/demo/audio',
  questionSetVersion,
  audioStartMs: 0,
  audioEndMs: 35000,
  totalQuestions: onlineQuestions.length,
  questions: onlineQuestions,
}

const offlineQuestion = {
  id: 'offline-q3',
  prompt: '这段旋律最接近哪部作品的氛围？',
  difficulty: 'MEDIUM',
  type: 'ATMOSPHERE',
  options: ['水仙', '星之梦', '沙耶之歌', '白色相簿2'],
  workName: 'Narcissu',
  analysis: '前奏以稀疏钢琴单音铺陈，配合缓慢推进的弦乐，形成水仙标志性的冬日独行氛围。',
  author: '姬松',
  answerIndex: 0,
}

const leaderboard = [
  { rank: 1, participantId: 'p-preview-0008', playerId: 'offline-preview-0008', nickname: '月见坂', score: 80, durationMs: 98200, answeredCount: 8, totalQuestions: 8, joinedAt: '2026-11-05T12:10:00' },
  { rank: 2, participantId: participantId, playerId, nickname, score: 70, durationMs: 110300, answeredCount: 8, totalQuestions: 8, joinedAt: '2026-11-05T12:11:00' },
  { rank: 3, participantId: 'p-preview-0011', playerId: 'offline-preview-0011', nickname: '雨天电台', score: 60, durationMs: 121800, answeredCount: 8, totalQuestions: 8, joinedAt: '2026-11-05T12:12:00' },
  { rank: 4, participantId: 'p-preview-0015', playerId: 'offline-preview-0015', nickname: '晚风信箱', score: 50, durationMs: 133600, answeredCount: 8, totalQuestions: 8, joinedAt: '2026-11-05T12:13:00' },
]

function offlineState(status) {
  const now = Date.now()
  const answered = ['ANSWER_LOCKED', 'ANSWER_REVEALED'].includes(status)
  const finished = status === 'FINISHED'
  const hasQuestion = !finished && status !== 'WAITING'
  const timelineRunning = status === 'ANSWERING'
  return {
    eventId: 'demo',
    eventName,
    status,
    currentQuestionIndex: finished ? 7 : hasQuestion ? 2 : -1,
    totalQuestions: 8,
    questionAudioStartEpochMs: hasQuestion ? now - 2400 : null,
    questionAudioEndEpochMs: hasQuestion ? now + (timelineRunning ? 18600 : 0) : null,
    answerRevealed: status === 'ANSWER_REVEALED',
    timelineRunning,
    audioPositionMs: hasQuestion ? 2400 : null,
    canAnswer: status === 'ANSWERING',
    question: hasQuestion ? offlineQuestion : null,
    hasAnswered: answered,
    selectedOption: answered ? 1 : null,
    playerScore: finished ? 70 : 0,
    playerDurationMs: finished ? 110300 : 0,
    playerRank: finished ? 2 : 0,
    playerCount: finished ? 4 : 27,
    answeredCount: finished ? 8 : 2,
    leaderboard: finished ? leaderboard : [],
    revision: 42,
  }
}

const onlineLockStatus = {
  participantId,
  status: 'NOT_LOCKED',
  score: null,
  answeredCount: null,
  totalQuestions: null,
  durationMs: null,
  onlineRank: null,
  totalRank: null,
  lockedAt: null,
}

const onlineLockedStatus = {
  participantId,
  status: 'LOCKED',
  score: 20,
  answeredCount: 2,
  totalQuestions: 3,
  durationMs: 18400,
  onlineRank: 12,
  totalRank: 37,
  lockedAt: '2026-11-05T12:34:56',
}

const previewResult = {
  score: 20,
  answeredCount: 2,
  totalQuestions: 3,
  durationMs: 21400,
  questionSetVersion,
}

const joinResult = {
  eventId: 'demo',
  eventName,
  mode: 'ONLINE',
  participantId,
  playerId: null,
  nickname,
  onlineLocked: false,
  onlineScore: null,
  lockedAt: null,
}

const activeScenario = {
  eventStatus: 'WAITING',
  onlineStatus: onlineLockStatus,
  preview: previewResult,
  state: null,
}

function setScenario(scenario) {
  activeScenario.eventStatus = scenario.eventStatus || 'WAITING'
  activeScenario.onlineStatus = scenario.onlineStatus || onlineLockStatus
  activeScenario.preview = scenario.preview || previewResult
  activeScenario.state = scenario.state || null
}

function jsonResponse(data) {
  return {
    status: 200,
    headers: [
      { name: 'content-type', value: 'application/json; charset=utf-8' },
      { name: 'cache-control', value: 'no-store' },
    ],
    body: Buffer.from(JSON.stringify({ code: 200, message: 'success', data })).toString('base64'),
  }
}

function responseFor(rawUrl, method) {
  const url = new URL(rawUrl)
  const pathname = url.pathname
  if (pathname === '/api/events/demo/audio') {
    return {
      status: 200,
      headers: [
        { name: 'content-type', value: 'audio/wav' },
        { name: 'cache-control', value: 'no-store' },
      ],
      body: audioWav.toString('base64'),
    }
  }
  if (pathname === '/api/events/demo' && method === 'GET') {
    return jsonResponse({ id: 'demo', name: eventName, totalQuestions: 8, status: activeScenario.eventStatus })
  }
  if (pathname === '/api/events/demo/join' && method === 'POST') {
    return jsonResponse(joinResult)
  }
  if (pathname === '/api/events/demo/online/questions') {
    return jsonResponse(questionSet)
  }
  if (pathname === '/api/events/demo/online/status') {
    return jsonResponse(activeScenario.onlineStatus)
  }
  if (pathname === '/api/events/demo/online/preview' && method === 'POST') {
    return jsonResponse(activeScenario.preview)
  }
  if (pathname === '/api/events/demo/online/lock' && method === 'POST') {
    return jsonResponse({ ...onlineLockedStatus, attemptId: 'preview-attempt-0001', nickname })
  }
  if (pathname === '/api/events/demo/state') {
    const state = typeof activeScenario.state === 'function' ? activeScenario.state() : activeScenario.state
    if (state) return jsonResponse(state)
  }
  if (pathname === '/api/events/demo/answers' && method === 'POST') {
    const state = typeof activeScenario.state === 'function' ? activeScenario.state() : activeScenario.state
    return jsonResponse(state || offlineState('ANSWERING'))
  }
  if (pathname === '/api/events/demo/leaderboard') {
    return jsonResponse(leaderboard)
  }
  if (pathname === '/api/events/demo/leaderboards/history') {
    return jsonResponse([])
  }
  if (pathname === '/api/events/demo/leaderboards/online' || pathname === '/api/events/demo/leaderboards/total') {
    return jsonResponse([])
  }
  return jsonResponse(null)
}

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
  const listeners = new Map()
  let sequence = 0

  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    if (message.id && pending.has(message.id)) {
      const { resolve, reject } = pending.get(message.id)
      pending.delete(message.id)
      if (message.error) reject(new Error(`${message.error.code}: ${message.error.message}`))
      else resolve(message.result)
      return
    }
    if (!message.method) return
    for (const listener of listeners.get(message.method) || []) listener(message.params, message.sessionId)
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

  function on(method, handler) {
    if (!listeners.has(method)) listeners.set(method, new Set())
    listeners.get(method).add(handler)
    return () => listeners.get(method)?.delete(handler)
  }

  return { socket, ready, send, on }
}

const port = 9700 + Math.floor(Math.random() * 200)
const profileDir = await mkdtemp(path.join(tmpdir(), 'bgm-flow-capture-'))
const chrome = spawn(chromePath, [
  '--headless=new',
  '--disable-gpu',
  '--hide-scrollbars',
  '--no-first-run',
  '--no-default-browser-check',
  '--disable-extensions',
  '--disable-dev-shm-usage',
  '--disable-background-timer-throttling',
  '--disable-renderer-backgrounding',
  '--autoplay-policy=no-user-gesture-required',
  '--mute-audio',
  `--remote-debugging-port=${port}`,
  `--user-data-dir=${profileDir}`,
  'about:blank',
], { stdio: 'ignore' })

let client
let sessionId
let injectionId = null
const captures = []

async function evaluate(expression, awaitPromise = false) {
  const result = await client.send('Runtime.evaluate', {
    expression,
    awaitPromise,
    returnByValue: true,
    userGesture: true,
  }, sessionId)
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text)
  }
  return result.result?.value
}

async function waitForSelector(selector, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const found = await evaluate(`Boolean(document.querySelector(${JSON.stringify(selector)}))`)
    if (found) return
    await sleep(80)
  }
  throw new Error(`Timed out waiting for ${selector}`)
}

async function waitForText(text, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const found = await evaluate(`document.body?.innerText.includes(${JSON.stringify(text)})`)
    if (found) return
    await sleep(80)
  }
  throw new Error(`Timed out waiting for text: ${text}`)
}

async function setInjection(source) {
  if (injectionId) {
    await client.send('Page.removeScriptToEvaluateOnNewDocument', { identifier: injectionId }, sessionId)
    injectionId = null
  }
  const result = await client.send('Page.addScriptToEvaluateOnNewDocument', { source }, sessionId)
  injectionId = result.identifier
}

function storageScript({ mode = 'ONLINE', nickname: savedNickname = '', progress = null }) {
  return `(() => {
    localStorage.clear();
    sessionStorage.clear();
    localStorage.setItem('bgm.participant-id.v1', ${JSON.stringify(participantId)});
    sessionStorage.setItem('bgm.mode', ${JSON.stringify(mode)});
    sessionStorage.setItem('bgm.event-id', 'demo');
    ${savedNickname ? `localStorage.setItem('bgm.nickname.v1', ${JSON.stringify(savedNickname)});` : ''}
    ${mode === 'OFFLINE' ? `sessionStorage.setItem('bgm.player-id', ${JSON.stringify(playerId)});` : ''}
    ${progress ? `localStorage.setItem('bgm.online.progress.v1', ${JSON.stringify(JSON.stringify(progress))});` : ''}
  })();`
}

async function navigate(url, selector, scenario, storage) {
  setScenario(scenario)
  await setInjection(storageScript(storage))
  await client.send('Page.navigate', { url }, sessionId)
  for (let attempt = 0; attempt < 200; attempt += 1) {
    const state = await evaluate('document.readyState').catch(() => '')
    if (state === 'complete') break
    await sleep(50)
  }
  await evaluate('document.fonts.ready.then(() => true)', true)
  if (selector) await waitForSelector(selector)
  await evaluate('window.scrollTo(0, 0)')
  await sleep(300)
}

async function capture(fileName, meta) {
  await evaluate('window.scrollTo(0, 0)')
  await sleep(120)
  const shot = await client.send('Page.captureScreenshot', {
    format: 'png',
    fromSurface: true,
    captureBeyondViewport: false,
  }, sessionId)
  await writeFile(path.join(buildDir, fileName), Buffer.from(shot.data, 'base64'))
  captures.push({ ...meta, file: fileName })
  console.log(`captured ${meta.mode} ${meta.order}: ${meta.title}`)
}

function step(mode, order, title, status, note) {
  return { mode, order, title, status, note }
}

try {
  await mkdir(buildDir, { recursive: true })
  const version = await waitForDebugger(port)
  client = createClient(version.webSocketDebuggerUrl)
  await client.ready

  const { targetId } = await client.send('Target.createTarget', { url: 'about:blank' })
  const attached = await client.send('Target.attachToTarget', { targetId, flatten: true })
  sessionId = attached.sessionId

  await client.send('Page.enable', {}, sessionId)
  await client.send('Runtime.enable', {}, sessionId)
  await client.send('Network.enable', {}, sessionId)
  await client.send('Network.setCacheDisabled', { cacheDisabled: true }, sessionId)
  await client.send('Emulation.setDeviceMetricsOverride', {
    width: viewport.width,
    height: viewport.height,
    deviceScaleFactor: viewport.dpr,
    mobile: true,
    screenWidth: viewport.width,
    screenHeight: viewport.height,
  }, sessionId)
  await client.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 }, sessionId)
  await client.send('Fetch.enable', {
    patterns: [{ urlPattern: '*://*/api/*', requestStage: 'Request' }],
  }, sessionId)

  client.on('Fetch.requestPaused', async (params, eventSessionId) => {
    const requestSessionId = eventSessionId || sessionId
    try {
      const response = responseFor(params.request.url, params.request.method)
      await client.send('Fetch.fulfillRequest', {
        requestId: params.requestId,
        responseCode: response.status,
        responseHeaders: response.headers,
        body: response.body,
      }, requestSessionId)
    } catch (error) {
      console.error('mock response failed', error)
      await client.send('Fetch.failRequest', {
        requestId: params.requestId,
        errorReason: 'Failed',
      }, requestSessionId).catch(() => {})
    }
  })

  const base = baseUrl
  const progressReady = {
    version: 1,
    eventId: 'demo',
    participantId,
    attemptId: 'preview-attempt-0001',
    questionSetVersion,
    status: 'RESULT_READY',
    currentIndex: 2,
    answers: { q1: 0, q2: 1, q3: 3 },
    audioPositionMs: 32000,
    activeDurationMs: 21400,
    questionRemainingMs: 0,
    startedAt: '2026-11-05T12:30:00.000Z',
    updatedAt: '2026-11-05T12:31:00.000Z',
    previewScore: null,
    previewAnsweredCount: null,
  }

  await navigate(`${base}/online?event=demo`, '.join-panel', { eventStatus: 'ANSWERING' }, {
    mode: 'ONLINE',
    nickname: '',
  })
  await capture('online-01-join.png', step('online', 1, '入场登记', 'ONLINE · 登记', '线上模式登记页'))

  await evaluate(`(() => {
    const input = document.querySelector('.join-panel input');
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    setter.call(input, ${JSON.stringify(nickname)});
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
  })()`)
  await sleep(180)
  await evaluate(`(() => {
    const button = [...document.querySelectorAll('.join-panel button')].find((item) => item.innerText.includes('进入活动'));
    if (!button || button.disabled) throw new Error('join button unavailable');
    button.click();
  })()`)
  await waitForSelector('.rules-modal .ant-modal-content')
  await sleep(450)
  await capture('online-02-rules.png', step('online', 2, '规则确认', 'ONLINE · 规则弹窗', '开始答题前确认连续播放与锁定规则'))

  await navigate(`${base}/play?event=demo&mode=online`, '.online-start-card', { eventStatus: 'ANSWERING' }, {
    mode: 'ONLINE',
    nickname,
  })
  await capture('online-03-start.png', step('online', 3, '答题准备', 'IN_PROGRESS · IDLE', '题集加载完成，等待开始'))

  await evaluate(`(() => {
    const button = [...document.querySelectorAll('.online-start-card button')].find((item) => item.innerText.includes('开始答题'));
    if (!button) throw new Error('start button missing');
    button.click();
  })()`)
  await waitForSelector('.online-question-card', 12000)
  await sleep(420)
  await evaluate(`document.querySelector('.online-question-card .option-button')?.click()`)
  await sleep(260)
  await capture('online-04-answering.png', step('online', 4, '作答中', 'IN_PROGRESS · 第一题', '真实音频时间轴驱动题目上屏'))

  await waitForSelector('.online-transition-card', 10000)
  await sleep(300)
  await capture('online-05-transition.png', step('online', 5, '题间过渡', 'IN_PROGRESS · 题间', '第一题结束，第二题等待上屏'))

  await evaluate(`document.querySelector('audio')?.pause()`)
  await waitForSelector('.online-pause-overlay')
  await sleep(350)
  await capture('online-06-paused.png', step('online', 6, '后台暂停', 'IN_PROGRESS · 页面隐藏', '页面进入后台后暂停音频并保存进度'))

  await navigate(`${base}/play?event=demo&mode=online`, '.online-result-card', {}, {
    mode: 'ONLINE',
    nickname,
    progress: progressReady,
  })
  await waitForText('20')
  await sleep(300)
  await capture('online-07-result.png', step('online', 7, '结果预览', 'RESULT_READY', '预览分返回后展示重试与锁定入口'))

  await navigate(`${base}/play?event=demo&mode=online`, '.online-result-card', {
    onlineStatus: onlineLockedStatus,
  }, {
    mode: 'ONLINE',
    nickname,
  })
  await waitForText('线上成绩已锁定')
  await sleep(300)
  await capture('online-08-locked.png', step('online', 8, '成绩锁定', 'LOCKED', '成绩写入线上榜与总榜后的锁定结果'))

  await navigate(`${base}/offline?event=demo`, '.join-panel', { eventStatus: 'WAITING' }, {
    mode: 'OFFLINE',
    nickname: '',
  })
  await capture('offline-01-join.png', step('offline', 1, '线下登记', 'OFFLINE · 登记', '现场扫码进入的线下登记页'))

  await evaluate(`(() => {
    const input = document.querySelector('.join-panel input');
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    setter.call(input, ${JSON.stringify(nickname)});
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
  })()`)
  await sleep(180)
  await evaluate(`(() => {
    const button = [...document.querySelectorAll('.join-panel button')].find((item) => item.innerText.includes('加入本轮'));
    if (!button || button.disabled) throw new Error('offline join button unavailable');
    button.click();
  })()`)
  await waitForSelector('.rules-modal .ant-modal-content')
  await sleep(450)
  await capture('offline-02-rules.png', step('offline', 2, '规则确认', 'OFFLINE · 规则弹窗', '现场流程与锁卷、揭晓规则'))

  await navigate(`${base}/play?event=demo&mode=offline`, '.state-card', { eventStatus: 'WAITING', state: () => offlineState('WAITING') }, {
    mode: 'OFFLINE',
    nickname,
  })
  await waitForText('等待活动开始')
  await sleep(250)
  await capture('offline-03-waiting.png', step('offline', 3, '等待开始', 'WAITING', '等待主持人播放本轮音频'))

  await navigate(`${base}/play?event=demo&mode=offline`, '.question-card', { eventStatus: 'ANSWERING', state: () => offlineState('ANSWERING') }, {
    mode: 'OFFLINE',
    nickname,
  })
  await evaluate(`document.querySelector('.question-card .option-button')?.click()`)
  await sleep(260)
  await capture('offline-04-answering.png', step('offline', 4, '作答中', 'ANSWERING', '锁定选项并提交答案'))

  await navigate(`${base}/play?event=demo&mode=offline`, '.question-card', { eventStatus: 'ANSWER_LOCKED', state: () => offlineState('ANSWER_LOCKED') }, {
    mode: 'OFFLINE',
    nickname,
  })
  await waitForText('已锁卷')
  await sleep(250)
  await capture('offline-05-locked.png', step('offline', 5, '锁卷', 'ANSWER_LOCKED', '提交后选项只读，等待答案揭晓'))

  await navigate(`${base}/play?event=demo&mode=offline`, '.explanation-alert', { eventStatus: 'ANSWER_REVEALED', state: () => offlineState('ANSWER_REVEALED') }, {
    mode: 'OFFLINE',
    nickname,
  })
  await sleep(300)
  await capture('offline-06-revealed.png', step('offline', 6, '答案揭晓', 'ANSWER_REVEALED', '正确答案、错选反馈、作品信息与解析'))

  await navigate(`${base}/play?event=demo&mode=offline`, '.score-panel', { eventStatus: 'FINISHED', state: () => offlineState('FINISHED') }, {
    mode: 'OFFLINE',
    nickname,
  })
  await waitForText('本轮排行榜')
  await sleep(300)
  await capture('offline-07-finished.png', step('offline', 7, '本轮结算', 'FINISHED', '最终得分、总用时、名次与本轮排行榜'))

  await writeFile(path.join(buildDir, 'captures.json'), JSON.stringify({
    baseUrl,
    viewport,
    generatedAt: new Date().toISOString(),
    captures,
  }, null, 2))
  console.log(`wrote ${captures.length} captures to ${path.relative(repoRoot, buildDir)}`)
} finally {
  client?.socket.close()
  chrome.kill('SIGKILL')
  await Promise.race([
    new Promise((resolve) => chrome.once('exit', resolve)),
    sleep(1500),
  ])
  await rm(profileDir, { recursive: true, force: true }).catch(() => {})
}

process.exit(process.exitCode || 0)

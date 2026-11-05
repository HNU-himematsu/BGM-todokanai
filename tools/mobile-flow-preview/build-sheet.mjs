import { execFile } from 'node:child_process'
import { createServer } from 'node:http'
import { readFile, rm } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'

const execFileAsync = promisify(execFile)
const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(scriptDir, '..', '..')
const buildDir = path.join(scriptDir, '.build')
const outputDir = path.join(repoRoot, 'docs', 'assets', 'preview')
const captureScript = path.join(scriptDir, 'capture-url.mjs')

const metadata = JSON.parse(await readFile(path.join(buildDir, 'captures.json'), 'utf8'))
const css = await readFile(path.join(scriptDir, 'sheet.css'), 'utf8')
const captures = metadata.captures || []

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
}

function stepMarkup(item, index) {
  const order = String(item.order).padStart(2, '0')
  return `<article class="step">
  <div class="phone"><div class="screen"><img src="/image/${encodeURIComponent(path.basename(item.file))}" alt="${escapeHtml(item.title)}" /></div></div>
  <div class="caption">
    <span class="step-index">${order}</span>
    <div>
      <h3 class="step-title">${escapeHtml(item.title)}</h3>
      <span class="step-status">${escapeHtml(item.status)}</span>
      <p class="step-note">${escapeHtml(item.note)}</p>
    </div>
  </div>
</article>`
}

function modeSection(mode, title, description) {
  const items = captures.filter((item) => item.mode === mode).sort((a, b) => a.order - b.order)
  return `<section class="mode-section" data-mode="${mode}">
  <div class="mode-head"><h2>${escapeHtml(title)}</h2><span>${escapeHtml(description)}</span></div>
  <div class="grid">${items.map(stepMarkup).join('\n')}</div>
</section>`
}

function documentHtml({ title, subtitle, body, single = false }) {
  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>${escapeHtml(title)}</title>
  <style>${css}</style>
</head>
<body>
  <main class="sheet${single ? ' single' : ''}">
    <header class="sheet-head">
      <h1>${escapeHtml(title)}</h1>
      <p>${escapeHtml(subtitle)}</p>
    </header>
    ${body}
  </main>
</body>
</html>`
}

const viewportText = `${metadata.viewport.width} × ${metadata.viewport.height} CSS px · DPR ${metadata.viewport.dpr}`
const pages = {
  'full-flow-mobile.png': documentHtml({
    title: '旮旯原声 · 双模式全流程手机端预览',
    subtitle: `线上与线下真实页面截图 · ${viewportText} · 手机框内不叠加任何页面元素`,
    body: [
      modeSection('online', '线上模式', '音频连续播放 · 自动出题 · 预览后锁定'),
      modeSection('offline', '线下模式', '现场主持推进 · 限时作答 · 即时揭晓'),
    ].join('\n'),
  }),
  'online-flow-mobile.png': documentHtml({
    title: '线上模式全流程手机端预览',
    subtitle: `真实前端页面截图 · ${viewportText}`,
    body: modeSection('online', '线上模式', '音频连续播放 · 自动出题 · 预览后锁定'),
    single: true,
  }),
  'offline-flow-mobile.png': documentHtml({
    title: '线下模式全流程手机端预览',
    subtitle: `真实前端页面截图 · ${viewportText}`,
    body: modeSection('offline', '线下模式', '现场主持推进 · 限时作答 · 即时揭晓'),
    single: true,
  }),
}

let activeHtml = ''
const server = createServer(async (request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1')
  if (url.pathname === '/') {
    response.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' })
    response.end(activeHtml)
    return
  }
  if (url.pathname.startsWith('/image/')) {
    const fileName = path.basename(decodeURIComponent(url.pathname.slice('/image/'.length)))
    try {
      const body = await readFile(path.join(buildDir, fileName))
      response.writeHead(200, { 'content-type': 'image/png', 'cache-control': 'no-store' })
      response.end(body)
    } catch {
      response.writeHead(404)
      response.end('not found')
    }
    return
  }
  response.writeHead(404)
  response.end('not found')
})

await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
const address = server.address()
const baseUrl = `http://127.0.0.1:${address.port}/`

try {
  for (const [fileName, html] of Object.entries(pages)) {
    activeHtml = html
    const output = path.join(outputDir, fileName)
    await execFileAsync(process.execPath, [captureScript, baseUrl, output, '1480', '900', '1.5', '1'], {
      cwd: repoRoot,
      maxBuffer: 10 * 1024 * 1024,
    })
    console.log(`built ${path.relative(repoRoot, output)}`)
  }
  await rm(buildDir, { recursive: true, force: true })
} finally {
  await new Promise((resolve) => server.close(resolve))
}

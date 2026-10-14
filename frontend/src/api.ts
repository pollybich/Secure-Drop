import { t, apiErrorText } from './i18n'
export type Transfer = {
  kind: 'FILE' | 'TEXT'; name: string; bytes: number; createdAt: string; expiresAt: string;
  maxDownloads: number; downloads: number; remaining: number; passwordProtected: boolean;
  state: 'AVAILABLE' | 'IN_PROGRESS' | 'EXPIRED' | 'EXHAUSTED' | 'REVOKED'
}
export type Created = { readToken: string; manageToken: string; transfer: Transfer }
export class ApiError extends Error { constructor(public code: string, message: string) { super(message) } }

async function readError(response: Response): Promise<never> {
  const data = await response.json().catch(() => ({ code: 'NETWORK', message: t("Сервис не ответил. Попробуйте ещё раз.") }))
  throw new ApiError(data.code || "NETWORK", apiErrorText(data.code, data.message) || t("Не удалось выполнить запрос. Попробуйте ещё раз."))
}
const networkError = () => new ApiError('NETWORK', t("Не удалось связаться с сервисом. Проверьте соединение и попробуйте ещё раз."))
async function readJson<T>(url: string, signal?: AbortSignal): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    try {
      const response = await fetch(url, { cache: 'no-store', signal })
      if ([502, 503, 504].includes(response.status) && attempt < 2) throw networkError()
      if (!response.ok) return readError(response)
      return await response.json()
    } catch (error) {
      if (signal?.aborted) throw error
      const transient = error instanceof TypeError || error instanceof ApiError && error.code === 'NETWORK'
      if (!transient) throw error
      if (attempt >= 2) throw networkError()
      await new Promise<void>((resolve) => window.setTimeout(resolve, 400 * (attempt + 1)))
      if (signal?.aborted) throw new DOMException('Cancelled', 'AbortError')
    }
  }
}
export function metadata(token: string, manage = false, signal?: AbortSignal): Promise<Transfer> {
  return readJson(`/api/${manage ? 'manage' : 'transfers'}/${encodeURIComponent(token)}`, signal)
}
export async function downloadFile(token: string, password: string, signal: AbortSignal): Promise<void> {
  let response: Response
  try {
    response = await fetch(`/api/transfers/${encodeURIComponent(token)}/download-ticket`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password }), signal })
  } catch (error) { if (signal.aborted) throw error; throw networkError() }
  if (!response.ok) return readError(response)
  const ticket: { url: string; statusUrl: string } = await response.json()
  if (!/^\/api\/downloads\/[A-Za-z0-9_-]{43}$/.test(ticket.url) || ticket.statusUrl !== `${ticket.url}/status`) throw new Error(t("Не удалось подготовить скачивание."))
  // A normal HTTP attachment uses the browser download manager; no Blob URL or file picker API.
  window.location.assign(ticket.url)
  for (let attempt = 0; attempt < 300; attempt++) {
    if (signal.aborted) throw new DOMException('Cancelled', 'AbortError')
    const { state } = await readJson<{ state: string }>(ticket.statusUrl, signal)
    if (state === 'SENT') return
    if (state === 'FAILED' || state === 'EXPIRED') throw new Error(t("Загрузка не завершилась. Попробуйте получить файл снова."))
    await new Promise<void>((resolve) => window.setTimeout(resolve, 1000))
  }
  throw new Error(t("Браузер ещё обрабатывает загрузку. Проверьте список скачиваний перед повтором."))
}
export function createTransfer(data: FormData, progress: (value: number) => void, signal: AbortSignal): Promise<Created> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', '/api/transfers')
    const abort = () => xhr.abort()
    signal.addEventListener('abort', abort, { once: true })
    const cleanup = () => signal.removeEventListener('abort', abort)
    xhr.upload.onprogress = (event) => { if (event.lengthComputable) progress(Math.min(95, Math.round(event.loaded / event.total * 95))) }
    xhr.onload = () => {
      cleanup()
      let response
      try { response = JSON.parse(xhr.responseText) } catch { reject(new Error(t("Сервис не ответил. Попробуйте ещё раз."))); return }
      if (xhr.status >= 200 && xhr.status < 300) { progress(100); resolve(response) }
      else reject(new ApiError(response.code, apiErrorText(response.code, response.message) || t("Не удалось создать передачу.")))
    }
    xhr.onerror = () => { cleanup(); reject(new Error(t("Нет соединения с сервером. Попробуйте ещё раз."))) }
    xhr.onabort = () => { cleanup(); reject(new DOMException(t("Передача отменена."), 'AbortError')) }
    xhr.send(data)
  })
}
export async function receive(token: string, password: string): Promise<{ blob: Blob; name: string }> {
  const response = await fetch(`/api/transfers/${encodeURIComponent(token)}/download`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password }) })
  if (!response.ok) return readError(response)
  const disposition = response.headers.get('Content-Disposition') || ''
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  const plain = disposition.match(/filename="([^"]+)"/)?.[1]
  const name = encoded ? decodeURIComponent(encoded) : plain || 'pollydrop-file'
  return { blob: await response.blob(), name }
}
export async function revoke(token: string): Promise<void> {
  const response = await fetch(`/api/manage/${encodeURIComponent(token)}`, { method: 'DELETE' })
  if (!response.ok) return readError(response)
}

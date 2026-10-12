export type Transfer = {
  kind: 'FILE' | 'TEXT'; name: string; bytes: number; createdAt: string; expiresAt: string;
  maxDownloads: number; downloads: number; remaining: number; passwordProtected: boolean;
  state: 'AVAILABLE' | 'IN_PROGRESS' | 'EXPIRED' | 'EXHAUSTED' | 'REVOKED'
}
export type Created = { readToken: string; manageToken: string; transfer: Transfer }
export class ApiError extends Error { constructor(public code: string, message: string) { super(message) } }

async function readError(response: Response): Promise<never> {
  const data = await response.json().catch(() => ({ code: 'NETWORK', message: 'Сервис не ответил. Попробуйте ещё раз.' }))
  throw new ApiError(data.code || "NETWORK", data.message || "Не удалось выполнить запрос. Попробуйте ещё раз.")
}
export async function metadata(token: string, manage = false): Promise<Transfer> {
  const response = await fetch(`/api/${manage ? 'manage' : 'transfers'}/${encodeURIComponent(token)}`)
  if (!response.ok) return readError(response)
  return response.json()
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
      try { response = JSON.parse(xhr.responseText) } catch { reject(new Error('Сервис не ответил. Попробуйте ещё раз.')); return }
      if (xhr.status >= 200 && xhr.status < 300) { progress(100); resolve(response) }
      else reject(new ApiError(response.code, response.message || 'Не удалось создать передачу.'))
    }
    xhr.onerror = () => { cleanup(); reject(new Error('Нет соединения с сервером. Попробуйте ещё раз.')) }
    xhr.onabort = () => { cleanup(); reject(new DOMException('Передача отменена.', 'AbortError')) }
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

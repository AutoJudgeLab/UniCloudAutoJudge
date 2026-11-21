import { submissionCopy as text } from '../submissionCopy'
import type { Language } from './types'

type SavedAttempt = { key: string; fingerprint: string }
const memory = new Map<string, SavedAttempt>()
export const attemptSlot = (userId: string, assignmentId: string) =>
  `judge:submission:${userId}:${assignmentId}`
export function savedAttempt(slot: string): SavedAttempt | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(slot) ?? 'null') as SavedAttempt | null
    if (
      value &&
      typeof value.key === 'string' &&
      /^[a-zA-Z0-9._:-]{1,128}$/.test(value.key) &&
      typeof value.fingerprint === 'string'
    )
      return value
  } catch {
    /* Private browser settings may disable storage; in-memory retries still work. */
  }
  return memory.get(slot) ?? null
}
export function forgetAttempt(slot: string) {
  memory.delete(slot)
  try {
    sessionStorage.removeItem(slot)
  } catch {
    /* No persistent storage. */
  }
}

export async function prepareAttempt(
  slot: string,
  file: File,
  language: Language,
  maxBytes: number,
) {
  if (file.size === 0) throw new Error(text.invalidText)
  if (file.size > maxBytes) throw new Error(text.tooLarge)
  if (!file.name.toLowerCase().endsWith(`.${language.extension}`)) throw new Error(text.invalidFile)
  const bytes = await file.arrayBuffer()
  let source: string
  try {
    source = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    throw new Error(text.invalidText)
  }
  for (const c of source) {
    const n = c.charCodeAt(0)
    if ((n < 32 && ![9, 10, 13].includes(n)) || (n >= 127 && n <= 159))
      throw new Error(text.invalidText)
  }
  if (!source.trim()) throw new Error(text.invalidText)
  const digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), (b) =>
    b.toString(16).padStart(2, '0'),
  ).join('')
  const fingerprint = JSON.stringify([language.value, file.name, digest])
  const previous = savedAttempt(slot)
  if (previous && previous.fingerprint !== fingerprint) throw new Error(text.differentAttempt)
  const attempt = previous ?? { key: crypto.randomUUID(), fingerprint }
  memory.set(slot, attempt)
  // Only retry metadata is kept in this tab, never source code or authentication tokens.
  try {
    sessionStorage.setItem(slot, JSON.stringify(attempt))
  } catch {
    /* Retain the in-memory attempt. */
  }
  return attempt
}

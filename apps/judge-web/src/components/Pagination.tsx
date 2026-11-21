import { ChevronLeft, ChevronRight } from 'lucide-react'
import { copy } from '../copy'

export function Pagination({
  page,
  totalPages,
  onPage,
}: {
  page: number
  totalPages: number
  onPage: (page: number) => void
}) {
  if (totalPages <= 1 && page === 0) return null
  return (
    <nav className="pagination" aria-label="Страници">
      <span>
        {copy.page} {page + 1}
        {totalPages > 0 ? ` ${copy.of} ${totalPages}` : ''}
      </span>
      <div>
        <button className="button secondary" disabled={page === 0} onClick={() => onPage(page - 1)}>
          <ChevronLeft size={16} aria-hidden="true" />
          {copy.previous}
        </button>
        <button
          className="button secondary"
          disabled={page + 1 >= totalPages}
          onClick={() => onPage(page + 1)}
        >
          {copy.next}
          <ChevronRight size={16} aria-hidden="true" />
        </button>
      </div>
    </nav>
  )
}

export function pageNumber(value: string | null) {
  const page = Number(value ?? 0)
  return Number.isSafeInteger(page) && page >= 0 && page <= 1_000_000 ? page : 0
}

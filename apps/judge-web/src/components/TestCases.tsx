import { useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { mutate, request } from '../api/client'
import type { Assignment, TestCase, User } from '../api/types'
import { managementCopy as text } from '../managementCopy'
import { score } from '../pages/Assignments'
import { Loading, Failure } from './Feedback'
import { ActionError, canManage, points, refreshAssignments, useAction } from './Management'

function TestForm({
  test,
  nextOrder,
  busy,
  save,
  cancel,
}: {
  test?: TestCase
  nextOrder: number
  busy: boolean
  save: (body: object) => void
  cancel: () => void
}) {
  const [inputData, setInput] = useState(test?.inputData ?? '')
  const [expectedOutput, setOutput] = useState(test?.expectedOutput ?? '')
  const [testPoints, setPoints] = useState(String(test?.points ?? 1))
  const [executionOrder, setOrder] = useState(String(test?.executionOrder ?? nextOrder))
  const [isHidden, setHidden] = useState(test?.isHidden ?? true)
  const [error, setError] = useState<unknown>(null)
  function submit(event: FormEvent) {
    event.preventDefault()
    try {
      const order = Number(executionOrder)
      if (!/^\d+$/.test(executionOrder) || !Number.isSafeInteger(order) || order > 2147483647)
        throw new Error('Редът трябва да е цяло число от 0 до 2147483647.')
      const value = points(testPoints)
      setError(null)
      save({ inputData, expectedOutput, points: value, isHidden, executionOrder: order })
    } catch (failure) {
      setError(failure)
    }
  }
  return (
    <form className="editor-form test-form" onSubmit={submit}>
      <h3>{test ? 'Редакция на тест' : 'Нов тест'}</h3>
      <fieldset disabled={busy}>
        <div className="form-columns">
          <div>
            <label htmlFor="test-input">Входни данни</label>
            <textarea
              id="test-input"
              rows={5}
              value={inputData}
              onChange={(e) => setInput(e.target.value)}
            />
          </div>
          <div>
            <label htmlFor="test-output">Очакван изход</label>
            <textarea
              id="test-output"
              rows={5}
              value={expectedOutput}
              onChange={(e) => setOutput(e.target.value)}
            />
          </div>
        </div>
        <p className="muted">
          Празният вход или изход е допустим. Запазват се интервалите и новите редове.
        </p>
        <div className="form-columns">
          <div>
            <label htmlFor="test-points">Точки за теста</label>
            <input
              id="test-points"
              required
              inputMode="decimal"
              value={testPoints}
              onChange={(e) => setPoints(e.target.value)}
            />
          </div>
          <div>
            <label htmlFor="test-order">Ред на изпълнение (от 0)</label>
            <input
              id="test-order"
              type="number"
              required
              min="0"
              max="2147483647"
              step="1"
              value={executionOrder}
              onChange={(e) => setOrder(e.target.value)}
            />
          </div>
        </div>
        <label className="check-label">
          <input type="checkbox" checked={isHidden} onChange={(e) => setHidden(e.target.checked)} />
          Скрит тест
        </label>
        <ActionError error={error} />
        <div className="action-row">
          <button className="button" type="submit">
            {busy ? text.saving : 'Запази теста'}
          </button>
          <button className="button secondary" type="button" onClick={cancel}>
            Отказ
          </button>
        </div>
      </fieldset>
    </form>
  )
}

export function TestCases({
  assignment,
  user,
  editable = false,
}: {
  assignment: Assignment
  user: User
  editable?: boolean
}) {
  const action = useAction()
  const [editing, setEditing] = useState<TestCase | 'new' | null>(null)
  const [notice, setNotice] = useState('')
  const writable = editable && canManage(user, assignment) && assignment.status !== 'ARCHIVED'
  const path = '/api/assignments/' + assignment.id + '/test-cases'
  const result = useQuery({
    queryKey: ['test-cases', user.id, user.role, assignment.id],
    queryFn: ({ signal }) => request<TestCase[]>(path, { signal }),
    refetchOnWindowFocus: false,
  })
  const tests = result.data ?? []
  function save(body: object) {
    void action.run(
      async () => {
        await mutate(path + (editing && editing !== 'new' ? '/' + editing.id : ''), {
          method: editing === 'new' ? 'POST' : 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        })
        setEditing(null)
        await refreshAssignments()
        setNotice('Тестът е запазен. Текущите тестове са презаредени.')
      },
      assignment.status === 'PUBLISHED' ? text.revision : undefined,
    )
  }
  function remove(test: TestCase) {
    void action.run(
      async () => {
        await mutate(path + '/' + test.id, { method: 'DELETE' })
        setEditing(null)
        await refreshAssignments()
        setNotice('Тестът е изтрит от текущата чернова.')
      },
      'Да изтрия ли този тест?' + (assignment.status === 'PUBLISHED' ? ' ' + text.revision : ''),
    )
  }
  return (
    <section className="content-panel test-management">
      <div className="section-heading">
        <h2>{editable ? 'Тестове за оценяване' : 'Примерни тестове'}</h2>
        {writable && !editing && (
          <button
            className="button secondary"
            disabled={action.busy || !result.isSuccess}
            onClick={() => {
              setNotice('')
              setEditing('new')
            }}
          >
            Добави тест
          </button>
        )}
      </div>
      {result.isPending ? (
        <Loading />
      ) : result.isError ? (
        <Failure error={result.error} retry={() => void result.refetch()} />
      ) : (
        <>
          {editable && (
            <p className="muted">
              {tests.length} теста · Максимум на заданието: {score(assignment.maximumScore)} т.
            </p>
          )}
          {!tests.length && (
            <p className="muted">
              {editable ? 'Все още няма тестове.' : 'Няма публикувани примерни тестове.'}
            </p>
          )}
          {tests.map((test, index) => (
            <details className="test-outcome" key={test.id}>
              <summary>
                <span>Тест {index + 1}</span>
                <span>{test.isHidden ? 'Скрит' : 'Публичен'}</span>
                <strong>{score(test.points)} т.</strong>
              </summary>
              <div className="test-body">
                {editable && <p className="muted">Ред на изпълнение: {test.executionOrder}</p>}
                <div className="test-values">
                  <div>
                    <h3>Входни данни</h3>
                    <pre>{test.inputData === '' ? '(празно)' : test.inputData}</pre>
                  </div>
                  <div>
                    <h3>Очакван изход</h3>
                    <pre>{test.expectedOutput === '' ? '(празно)' : test.expectedOutput}</pre>
                  </div>
                </div>
                {writable && (
                  <div className="action-row">
                    <button
                      className="button secondary"
                      disabled={action.busy || !!editing}
                      onClick={() => {
                        setNotice('')
                        setEditing(test)
                      }}
                    >
                      Редактирай теста
                    </button>
                    <button
                      className="button danger"
                      disabled={action.busy || !!editing}
                      onClick={() => remove(test)}
                    >
                      Изтрий теста
                    </button>
                  </div>
                )}
              </div>
            </details>
          ))}
          {editing && writable && (
            <TestForm
              key={editing === 'new' ? 'new' : editing.id}
              test={editing === 'new' ? undefined : editing}
              nextOrder={tests.reduce((max, test) => Math.max(max, test.executionOrder + 1), 0)}
              busy={action.busy}
              save={save}
              cancel={() => setEditing(null)}
            />
          )}
        </>
      )}
      <ActionError error={action.error} />
      {notice && <p role="status">{notice}</p>}
      {editable && (
        <button
          className="text-button"
          disabled={action.busy}
          onClick={() => {
            if (editing && !window.confirm('Да отхвърля ли незапазените промени по теста?')) return
            setEditing(null)
            void refreshAssignments()
          }}
        >
          Презареди текущите данни
        </button>
      )}
    </section>
  )
}

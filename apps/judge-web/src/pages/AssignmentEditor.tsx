import { useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useOutletContext, useParams } from 'react-router-dom'
import { ApiError, mutate, request } from '../api/client'
import type { Assignment, User } from '../api/types'
import { Failure, Loading } from '../components/Feedback'
import {
  ActionError,
  ActionScope,
  canManage,
  points,
  refreshAssignments,
  useAction,
} from '../components/Management'
import { TestCases } from '../components/TestCases'
import { managementCopy as text } from '../managementCopy'
import { Status } from './Assignments'

function localDate(value: string) {
  const date = new Date(value)
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
}

function AssignmentForm({ assignment }: { assignment?: Assignment }) {
  const navigate = useNavigate()
  const action = useAction()
  const [title, setTitle] = useState(assignment?.title ?? '')
  const [description, setDescription] = useState(assignment?.description ?? '')
  const [maximumScore, setScore] = useState(String(assignment?.maximumScore ?? 100))
  const [deadline, setDeadline] = useState(assignment ? localDate(assignment.deadline) : '')
  function submit(event: FormEvent) {
    event.preventDefault()
    void action.run(
      async () => {
        const instant = new Date(deadline)
        if (
          !title.trim() ||
          !description.trim() ||
          !Number.isFinite(instant.getTime()) ||
          instant.getTime() <= Date.now()
        )
          throw new Error(text.invalid)
        const result = await mutate<Assignment>(
          assignment
            ? '/api/assignments/' +
                assignment.id +
                '?createNewRevision=' +
                (assignment.status === 'PUBLISHED')
            : '/api/assignments',
          {
            method: assignment ? 'PUT' : 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              title: title.trim(),
              description,
              maximumScore: points(maximumScore),
              deadline: instant.toISOString(),
            }),
          },
        )
        await refreshAssignments()
        navigate('/assignments/' + result.id + '/manage', { replace: !assignment })
      },
      assignment?.status === 'PUBLISHED' ? text.revision : undefined,
    )
  }
  return (
    <form className="content-panel editor-form" onSubmit={submit}>
      <h2>{assignment ? 'Условие и срок' : text.create}</h2>
      <fieldset disabled={action.busy}>
        <label htmlFor="assignment-title">{text.title}</label>
        <input
          id="assignment-title"
          required
          maxLength={255}
          value={title}
          onChange={(e) => setTitle(e.target.value)}
        />
        <label htmlFor="assignment-description">{text.description}</label>
        <textarea
          id="assignment-description"
          required
          rows={7}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
        />
        <div className="form-columns">
          <div>
            <label htmlFor="assignment-score">{text.maximumScore}</label>
            <input
              id="assignment-score"
              required
              inputMode="decimal"
              value={maximumScore}
              onChange={(e) => setScore(e.target.value)}
            />
          </div>
          <div>
            <label htmlFor="assignment-deadline">{text.deadline}</label>
            <input
              id="assignment-deadline"
              type="datetime-local"
              required
              value={deadline}
              onChange={(e) => setDeadline(e.target.value)}
            />
            <small>
              Часът е в часовата зона на браузъра:{' '}
              {Intl.DateTimeFormat().resolvedOptions().timeZone}.
            </small>
          </div>
        </div>
        <ActionError error={action.error} />
        <div className="action-row">
          <button className="button" type="submit">
            {action.busy ? text.saving : assignment ? text.save : 'Създай чернова'}
          </button>
          <Link
            className="button secondary"
            to={assignment ? '/assignments/' + assignment.id : '/assignments'}
          >
            {text.cancel}
          </Link>
        </div>
      </fieldset>
    </form>
  )
}

function Lifecycle({ assignment }: { assignment: Assignment }) {
  const action = useAction()
  const navigate = useNavigate()
  const [notice, setNotice] = useState('')
  function change(operation: 'publish' | 'archive' | 'delete') {
    const confirmation =
      operation === 'archive'
        ? 'Да архивирам ли заданието? То ще стане само за преглед и ще изчезне от списъка на студентите.'
        : operation === 'delete'
          ? 'Да изтрия ли заданието? Изтриването е окончателно и е допустимо само без запазена история.'
          : 'Да публикувам ли заданието с текущите тестове и срок?'
    void action.run(async () => {
      await mutate(
        '/api/assignments/' + assignment.id + (operation === 'delete' ? '' : '/' + operation),
        { method: operation === 'delete' ? 'DELETE' : 'POST' },
      )
      await refreshAssignments()
      setNotice(operation === 'publish' ? 'Заданието е публикувано.' : 'Заданието е архивирано.')
      if (operation === 'delete') navigate('/assignments')
    }, confirmation)
  }
  return (
    <section className="content-panel lifecycle-panel">
      <h2>Публикуване и история</h2>
      <p className="muted">
        Публикуването изисква бъдещ срок и поне един тест. Сборът на точките трябва да е равен на
        максимума на заданието.
      </p>
      {assignment.status === 'ARCHIVED' ? (
        <p>Архивирано задание — историята се запазва, редакцията е спряна.</p>
      ) : (
        <div className="action-row">
          {assignment.status === 'DRAFT' && (
            <button className="button" disabled={action.busy} onClick={() => change('publish')}>
              Публикувай
            </button>
          )}
          <button
            className="button secondary"
            disabled={action.busy}
            onClick={() => change('archive')}
          >
            Архивирай
          </button>
          {assignment.status === 'DRAFT' && (
            <button
              className="button danger"
              disabled={action.busy}
              onClick={() => change('delete')}
            >
              Изтрий заданието
            </button>
          )}
        </div>
      )}
      {notice && <p role="status">{notice}</p>}
      <ActionError error={action.error} />
    </section>
  )
}

export function AssignmentEditor() {
  const { id } = useParams()
  const user = useOutletContext<User>()
  const allowed = user.role === 'TEACHER' || user.role === 'ADMIN'
  const result = useQuery({
    queryKey: ['assignment', user.id, user.role, id],
    queryFn: ({ signal }) =>
      request<Assignment>('/api/assignments/' + encodeURIComponent(id!), { signal }),
    enabled: allowed && !!id,
    refetchOnWindowFocus: false,
  })
  if (!allowed) return <Failure error={new ApiError(403)} />
  if (id && result.isPending) return <Loading />
  if (id && result.isError)
    return <Failure error={result.error} retry={() => void result.refetch()} />
  const assignment = id ? result.data : undefined
  if (assignment && !canManage(user, assignment)) return <Failure error={new ApiError(403)} />
  return (
    <ActionScope>
      <Link className="back-link" to={id ? '/assignments/' + id : '/assignments'}>
        ← {id ? 'Към заданието' : 'Към заданията'}
      </Link>
      <div className="page-heading">
        <div>
          <p className="eyebrow">ПРЕПОДАВАТЕЛСКО ПРОСТРАНСТВО</p>
          <h1>{assignment?.title ?? text.create}</h1>
          {assignment && <Status value={assignment.status} />}
        </div>
      </div>
      {assignment?.status === 'PUBLISHED' && (
        <p className="inline-note">
          Редакцията на условието или тестовете създава нова чернова. Старите предавания запазват
          своята ревизия и резултати.
        </p>
      )}
      {assignment?.status !== 'ARCHIVED' && (
        <AssignmentForm
          key={assignment ? assignment.id + ':' + assignment.updatedAt : 'new'}
          assignment={assignment}
        />
      )}
      {assignment && (
        <>
          <TestCases assignment={assignment} user={user} editable />
          <Lifecycle assignment={assignment} />
          <div className="action-row">
            <Link className="button secondary" to={'/assignments/' + id + '/submissions'}>
              История на предаванията
            </Link>
            <Link className="button secondary" to={'/assignments/' + id + '/leaderboard'}>
              Класиране
            </Link>
          </div>
        </>
      )}
    </ActionScope>
  )
}

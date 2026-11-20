# Анализ на текущата система — 19.11.2026

Това е първоначалният анализ преди поправките. Последващите промени и актуалните
граници на проверката са описани в [отчета за поправките](system-fixes-2026-11-19.md).

## Оценка

Основният backend поток работи в проверената локална среда: потребител → задача →
качване → PostgreSQL/MinIO → outbox → Redis → Docker worker → резултат.
Наличният набор от 256 теста преминава изцяло. Има обаче потвърдени пропуски в
оценяването на валиден Java код и в разпознаването на Docker exit status.
Затова успешните тестове не са достатъчно основание системата да бъде приета за
готова за безусловно коректно оценяване на реални студентски решения.

Прегледът обхваща работното дърво на `feature/judge-worker`, включително
некомитнатите промени. Това е оценка на кода и локалните проверки, а не
потвърждение за настроена production среда или успешен вход към реалния Portal staging.

## Проверки и доказателства

Изпълнено:

~~~sh
mvn -o verify -Pintegration-tests
~~~

Резултат: **BUILD SUCCESS**, 4 минути и 18 секунди, завършил на
`2026-11-19T10:57:30+02:00`. Няма failed/error/skipped тестове.
Локален лог: `/private/tmp/unicloud-system-audit-verify.log`.

| Компонент | Unit | Integration | Общо |
| --- | ---: | ---: | ---: |
| Storage | 7 | 7 | 14 |
| Worker | 39 | 54 | 93 |
| API | 11 | 138 | 149 |
| Общо | 57 | 199 | 256 |

Извън стандартните тестове са изпълнени малки диагностични програми срещу
реалния JavaSourceInspector и DockerSandboxRunner. Те потвърдиха F1–F3 по-долу.
Тези гранични случаи все още не са постоянни regression тестове в проекта.
F4–F6 са установени чрез анализ на кода; не е симулиран пълен дълъг многопроцесен
инцидент или загуба на реални Redis данни.

## Дефекти и пропуски по приоритет

### F1 — P1: валиден Java source може да остане без краен резултат

Място: [JavaSourceInspector](../services/judge-worker/src/main/java/org/university/judge/worker/grading/JavaSourceInspector.java),
редове 15–16 и 29–30; [SubmissionProcessor](../services/judge-worker/src/main/java/org/university/judge/worker/processing/SubmissionProcessor.java),
обработката на изключения около `grader.grade`.

Валиден Java клас с текстов литерал от 10 000 символа се компилира успешно,
но `COMMENTS_AND_LITERALS.matcher(...).replaceAll(...)` хвърля `StackOverflowError`.
Същото е възпроизведено с 30 000 символа; с 1000 символа няма проблем в тази среда.
Това са размери под стандартния upload лимит от 1 MB.

Точната граница зависи от стека/JVM. Проблемът е, че regex обработката на недоверен
source се извършва в worker JVM, преди Docker sandbox. Processor и WorkerLoop
прихващат RuntimeException, а StackOverflowError не е RuntimeException. Заявката
остава pending в Redis и RUNNING в API; след reclaim същият source отново може да
предизвика грешката. Executor освобождава слота, така че това не доказва спиране на
целия worker, но конкретното решение може да се повтаря без краен резултат.

Поправка: ограничен по време и памет lexer/parser без рекурсивен regex върху целия
source; ясно поведение при неуспешен анализ. Само прихващане на Error не поправя
неправилната обработка на валиден код.

### F2 — P1: неправилен избор на Java entry point

Място: същият JavaSourceInspector, редове 22 и 49–58.

Следният файл се компилира успешно:

~~~java
class Helper {
    static void main(int n) {}
}
public class Main {
    public static void main(String[] args) {
        System.out.println(42);
    }
}
~~~

Inspector връща `Layout[fileName=Main.java, mainClass=Helper]`. Методът
`main(int)` не е Java launcher entry point, но regex го избира пред правилния
`Main.main(String[])`. Така валидно решение може да получи runtime error и нула точки.

Поправка: проверка на действителната сигнатура, модификатори и обхват на метода;
отделни тестове за overload-и, вложени типове и няколко top-level типа.

### F3 — P2: програмният exit code се бърка с Docker/OOM грешка

Място: [DockerSandboxRunner](../services/judge-worker/src/main/java/org/university/judge/worker/sandbox/DockerSandboxRunner.java),
редове 123–130.

Проверка с реалния runner и `python:3.13-slim`:

| Програма | Получен RunResult | Правилна интерпретация |
| --- | --- | --- |
| `sys.exit(1)` | COMPLETED, exit=1 | Нормално приключил процес с runtime failure |
| `sys.exit(125)` | SANDBOX_ERROR | Процесът е стартирал и сам е върнал 125 |
| `sys.exit(137)` | MEMORY_LIMIT | Кодът сам е върнал 137; това не доказва OOM |

При 125 се правят ненужни повторни оценявания и накрая може да се запише
INTERNAL_ERROR. При 137 се показва неправилна причина за неуспеха.

Поправка: отделяне на create/start/wait грешките от exit status на програмата и
проверка на container state, включително `OOMKilled`, преди премахването на контейнера.
В момента `--rm` изтрива състоянието при приключване.

### F4 — P1 при множество workers: дълга валидна оценка може постоянно да бъде заменяна

Места: [WorkerTransportService](../services/judge-api/src/main/java/org/university/judge/api/worker/WorkerTransportService.java),
редове 59–62; [RedisStreamQueue](../services/judge-worker/src/main/java/org/university/judge/worker/queue/RedisStreamQueue.java),
редове 63–81; worker application.yml.

API lease е 4 минути, Redis reclaim е 5 минути. Няма heartbeat/подновяване и няма
горна граница за броя тестове в една задача. Worker изпълнява всеки тест, включително
след предишен timeout. При 100 теста, достигащи лимита, общата оценка може да е
много над 5 минути.

Свободен втори worker може да reclaim-не съобщението, да получи нов API attempt и
да обезсили още работещия първи опит. Първият резултат правилно се отхвърля като
stale, но замяната може да се повтаря. При един worker неговият inFlight списък
пази собствената му текуща задача; рискът е конкретно при други consumers/instances.

Поправка: heartbeat, свързан с attempt ID, и контролирано обновяване на Redis idle;
или проверим общ бюджет/лимит за тестове и конфигурация на lease над този бюджет.
Само по-голяма произволна константа не решава неограничения брой тестове.

### F5 — P2: загубена Redis consumer group не се възстановява автоматично

Място: RedisStreamQueue, редове 108–116; WorkerLoop обработката на queue exceptions.

След първото създаване `groupReady=true` се пази само в паметта. Ако Redis бъде
възстановен без consumer group, следващите XREADGROUP/XPENDING операции връщат
NOGROUP. Кодът логва и повтаря, но не нулира groupReady и не създава групата наново.
Връщането на Redis connectivity само по себе си не възстановява този сценарий.

Поправка: разпознаване на NOGROUP, безопасно повторно създаване на групата и тест
за загуба/възстановяване на Redis state без рестарт на worker. Временен workaround
е координирано пресъздаване на групата или рестарт след възстановяване на опашката.

### F6 — P2: recovery скриптът не обхваща загубено събитие за RUNNING submission

Място: [replay-outbox.sql](../infrastructure/operations/replay-outbox.sql), редове 30 и 42;
[RedisSubmissionEventPublisher](../services/judge-api/src/main/java/org/university/judge/api/outbox/RedisSubmissionEventPublisher.java).

Redis stream е ограничен с MAXLEN и може да изтрие стари неприключени записи.
Outbox пази събитието, но съществуващият replay скрипт избира само PENDING submissions.
Ако съобщението изчезне след begin, submission вече е RUNNING; при последващо
умиране на worker няма Redis запис за reclaim и стандартният replay го пропуска.
Самото изтичане на API lease не създава ново събитие.

Поправка: възстановяване и на RUNNING jobs с изтекъл lease, със защита от състезание
с жив attempt, или отделен reconciliation процес. Да не се replay-ват сляпо всички
RUNNING записи. Сценарият е частично описан като операторска отговорност в
reliability.md, но няма завършен автоматизиран recovery механизъм.

## Архитектура и собственост на данните

~~~mermaid
flowchart LR
    Portal["Portal: университетска самоличност"] --> Auth["Judge: OIDC / Bearer проверка"]
    Auth --> Users["users: вътрешен UUID, issuer/sub, роля, статус"]
    Users --> API["Задачи, тестове, submissions"]
    API --> PG["PostgreSQL: revisions, submission, outbox"]
    API --> S3["MinIO: частен source"]
    PG --> Redis["Redis Streams"]
    Redis --> Worker["Worker: отделен service token"]
    Worker --> Docker["Компилация и изпълнение в Docker"]
    Worker --> Result["API completion → резултати в PostgreSQL"]
~~~

Portal управлява самоличността и университетските роли. Judge създава локален запис
при първия разрешен достъп и запазва постоянен UUID. Не свързва акаунти по email.
Токените са в сървърната сесия, а не в users. Пароли се пазят само за локалния режим.
При Portal вход няма автоматично наследяване на история от стар локален акаунт;
необходима е проверена миграция на issuer/sub към неговия съществуващ UUID.

PostgreSQL е източникът на бизнес състоянието. Redis е транспорт, не единственото
хранилище за прието решение. Worker получава source и запечатаните тестове през
защитен вътрешен API, а completion е транзакционен и идемпотентен.

## Покрити сценарии

| Област | Реално проверено | Основни integration suites |
| --- | --- | --- |
| Локален вход | Регистрация само като STUDENT, BCrypt, JWT signature/issuer/audience/expiry, блокиране, bootstrap | AuthenticationIT: 11; AdminBootstrapIT: 3 |
| Защита на входа | Redis лимити, отделни login/register counters, конкурентни заявки, expiry | AuthRateLimitIT: 4; допълнителни unit тестове |
| Portal | PKCE, Basic code exchange, state/nonce, callback replay, cookie, CSRF, logout, subject mapping, права, отказ при UserInfo outage | PortalAuthenticationIT: 14 |
| Потребители | Роли за локални акаунти, статус, собствен достъп, последен локален администратор, конкуренция | AdminUserManagementIT: 14 |
| Задачи | Създаване, собственост, редактиране, публикуване, архивиране, immutable revisions | AssignmentManagementIT: 14 |
| Тестове | Видими/скрити тестове, ред, точки, запечатване, автоматична нова revision, история | AssignmentTestCasesIT: 12 |
| Качване | UTF-8, файл/език/размер, срок, idempotency, конкурентни повторения, промяна по време на upload, cleanup | SubmissionManagementIT: 17 |
| Storage | Частен bucket, ограничени credentials, безопасни имена, действителен размер, откази | ObjectStorageIT: 7 |
| Outbox | Redis outage, restart, дублиране след загубен commit/ack, два publisher-а, retention/replay | OutboxIntegrationIT: 7 |
| API ↔ worker | Разделяне на credentials, оригинална revision, един активен attempt, stale резултат, валидиране и replay | WorkerTransportIT: 9 |
| Реален пълен поток | Upload → outbox → Redis → действителен worker/grader → Java Docker → HTTP completion → студентски резултат | realWorkerConsumesRedisGradesInDockerAndStoresAnObservableResult |
| Езици | 10 езика: изпълнение, scoring, compile/runtime failure, timeout; допълнителни memory проверки | LanguageSandboxIT: 44 |
| Sandbox | Java verdicts, output flood, недостъпна мрежа, read-only filesystem, process/thread limits | DockerSandboxRunnerIT: 10 |
| Резултати | Собственост, скрити outputs, неизменна revision, retries, rollback, leaderboard, история | EvaluationManagementIT: 18 |
| База и приложение | Миграции V1–V11, constraints, запазени данни, health, OpenAPI | PersistenceIT: 13; JudgeApiApplicationIT: 2 |

Това е покритие на конкретни сценарии, не измерен процент line/branch coverage.
Пълният worker transport тест използва Java; другите девет езика се проверяват с
реален Grader/Docker, но не всеки с отделен HTTP→queue→API end-to-end тест.
OIDC браузърният поток използва HTTP тестов клиент и тестов доставчик, не реален
браузър срещу университетския staging. Cookie флаговете се проверяват, но това
не заменя проверка в браузър през реалния HTTPS ingress.

## Точни граници на бизнес сценария

| Потребител/действие | Текущо поведение |
| --- | --- |
| STUDENT | Вижда всички PUBLISHED задачи; може да изпраща преди срока; чете собствените submissions/results |
| TEACHER | Управлява своите задачи/тестове и чете submissions за тях |
| ADMIN | В Portal режим: роля ADMIN плюс изрично разрешен sub; управлява Judge ресурсите и локалния статус |
| INSPECTOR / DEAN / DECAN | Няма разрешен Judge mapping; отказ |
| Published leaderboard | Достъпен за удостоверени потребители според текущата политика; показва обобщени класирания за други студенти |
| Чужд Portal module token | Отказ при audience, различна от Judge |
| Token exchange / client_credentials | Предложен бъдещ договор, не реализиран междумодулен достъп |
| Worker | Има само вътрешната evaluation capability; не наследява потребителска роля |

Няма Course, Faculty, Enrollment или връзка assignment→course. Следователно
„студентът вижда само задачите от своя курс“ не е реализирано правило. Portal
COURSE_OFFERING_READ е общ permission, а не доказателство за записване в конкретен курс.
Необходимо е ново домейн свързване за факултетен декан или курсово ограничаване.

Leaderboard избира най-добрия абсолютен score на студент през revisions на задачата.
При променен maximumScore между revisions е необходимо продуктово решение дали
такова сравнение е желано, или трябва отделна/нормализирана класация.

Езиците са JAVA, PYTHON, C, CPP, JAVASCRIPT, TYPESCRIPT, KOTLIN, GO, RUST, CSHARP.
Поддръжката е за единичен source файл и наличните стандартни библиотеки; няма
качване на цял проект, download на зависимости, custom checker, interactive task
или задаване на различни time/memory limits на всяка задача. API връща null limits,
а worker използва собствените глобални настройки.

## Откази и оперативна готовност

| Ситуация | Поведение |
| --- | --- |
| Кратък Redis outage преди публикация | Приемането може да продължи; outbox запазва и повтаря |
| Повторен event или изгубен completion response | Защита чрез event/attempt IDs и идемпотентен completion |
| Worker умира, Redis пази pending записа | След reclaim/lease изтичане е възможен нов attempt; основните части са тествани |
| Продължително валидно изпълнение | Риск F4; няма heartbeat |
| Загуба/trim на Redis данни | Операторски replay; ограничения F5/F6 |
| Невъзстановим worker HTTP 400/404/422 | Dead-letter; няма автоматична административна поправка на бизнес състоянието |
| Липсващ/повреден source | Begin връща 503 и не присвоява attempt; нужда от възстановяване на source |
| Изчерпани grading retries | INTERNAL_ERROR; няма публичен rejudge endpoint |
| Portal UserInfo outage | Удостоверените Portal операции отказват със 503; няма fallback към кеширани права |
| API рестарт при Portal сесия | Сесията се губи; нужен е нов OIDC вход |
| Няколко API реплики | Bearer/local stateless режимът не изисква session sharing; Portal cookies изискват shared sessions или affinity |
| Изтекъл Portal token | Нов OIDC вход; няма refresh token |
| Загуба на PostgreSQL/MinIO | Нужни са съгласувани backups/restore; Redis не е backup |

Допълнителни ограничения, установени при прегледа:

- Всяка защитена Portal заявка прави UserInfo call и синхронизация с общ
  pg_advisory_xact_lock. Това е зависимост от Portal latency и точка на сериализация;
  не е измерен реалният throughput при масов вход/краен срок.
- Reclaim сканира винаги първите 100 pending записа, без cursor. При трайно
  неприключващи първи записи по-късни записи могат да останат без recovery.
- Няма квота за compiler work directory върху host bind mount. Runtime filesystem
  е read-only и /tmp е ограничен, но compile артефактите не получават отделна disk quota.
  Не е извършван тест за изчерпване на host диска.
- Execution time включва Docker wall-clock overhead; това не е измерване на чистото
  CPU време на студентската програма. Трябва да се отчита при строг time-limit policy.
- Има Compose/API Kubernetes конфигурация и toolchain images, но няма готов
  Kubernetes worker deployment. Worker се стартира отделно с достъп до Docker.
- Няма frontend за студент/преподавател в това repository; налични са API и Swagger.
- docs/security.md още описва worker transport като неизграден, а reliability.md
  съдържа по-старо описание на lease automation. Реалният код и по-новите
  worker-transport.md/portal-sso.md са по-актуални за тези части.

Load scripts, health и metrics са налични, но този преглед не доказва SLO за брой
потребители, latency или възстановяване на реална инфраструктура. Съществуващите
SLO стойности са цели, не измерени гаранции.

## Препоръчан ред на следващите промени

1. Поправка на F1/F2 и regression тестове с валидни Java конструкции; това засяга
   директно коректността на оценките и приключването на submissions.
2. Разграничаване на Docker инфраструктурни откази от програмни exit codes (F3).
3. Heartbeat/общ grading budget и тест с повече от един истински worker (F4).
4. Recovery при NOGROUP, загубени RUNNING events и reclaim pagination (F5/F6).
5. Реален staging smoke test: Portal вход → assignment → submission → result,
   както и проверена миграция на старите user UUID-и при реален cutover.
6. Course/enrollment/faculty модел и договор с Portal за делегирани tokens и scopes,
   ако тези университетски сценарии са част от следващия sprint.
7. Shared sessions при повече реплики, реален load test, disk quotas и упражнение
   по backup/restore преди експлоатация с реална оценъчна история.

В рамките на този анализ е добавен този доклад; потвърдените дефекти не са
поправяни автоматично. Зеленият стандартен набор остава полезна отправна точка,
а диагностичните случаи показват точно къде трябва да се разшири.

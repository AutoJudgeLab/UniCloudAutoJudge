# Поправки след системния анализ — 19.11.2026

Обхватът е потвърден от потребителя: техническите дефекти се поправят сега;
нови продуктови функции се планират отделно. Първоначалният анализ остава
исторически документ в system-review-2026-11-19.md.

| Проблем | Промяна | Regression проверка |
| --- | --- | --- |
| F1: StackOverflowError при дълъг Java literal | Премахнат host-side regex parser; JDK parser работи само в ограничения compile контейнер | Валиден literal с 30 000 символа |
| F2: грешен Java файл / main | AST намира top-level public type; изолиран class loader проверява public static void main(String[]) без class initialization | Подвеждащ main(int), вложен public type, package, Unicode име, varargs, наследен main, static initializer |
| F3: exit 125/137 се бъркат с Docker/OOM | Inspect на запазен container state преди cleanup; OOMKilled е източникът за kernel OOM | Самостоятелни exit 125/126/127/137 са runtime errors; memory и timeout regressions |
| F4: жив дълъг attempt се заменя | Persisted evaluation_lease_until, heartbeat endpoint, renewal през compilation/tests/upload; Redis idle renewal само от текущ owner | Две worker инстанции, работа по-дълга от първоначалния API lease и Redis idle; stale heartbeat се отхвърля |
| F5: NOGROUP изисква рестарт | Идемпотентно създаване на consumer group при polling/reclaim | Изтриване на group и stream без рестарт |
| F6: изгубен RUNNING event не се replay-ва | Периодична PostgreSQL reconciliation и обновен операторски SQL за expired RUNNING/PENDING; заключвания срещу heartbeat/completion | Жив attempt не се replay-ва; изтекъл се публикува отново; completed остава непроменен |

Допълнително:

- Pending scan има cursor, така че първите 100 записи не блокират по-късните.
- Dead-letter и ACK са една Redis операция, защитена с проверка на owner.
- Невалиден grading report опитва минимален INTERNAL_ERROR completion за същия
  attempt; невалиден и този report остава за оператор в DLQ.
- Portal browser sessions и OIDC/PKCE state са в Spring Session Redis. Callback,
  authenticated requests и logout работят през две API инстанции.
- Portal identity synchronization заключва само конкретния issuer/sub и неговия
  user row. Непроменен профил не причинява UPDATE. UserInfo остава на всяка заявка,
  за да се запази незабавното отнемане на права и отказът при Portal outage.
- Компилацията има per-file RLIMIT_FSIZE и монитор за общи bytes/entries.
  Linux worker deployment включва отделен ограничен tmpfs за твърда обща квота.
  Мониторът сам по себе си допуска превишаване между проверките през 100 ms.
- Времето за приключил контейнер се взема от Docker timestamps. CLI cleanup не
  влиза в него; това остава wall-clock, не CPU time. Завършил процес над runtime
  лимита получава TIME_LIMIT, независимо от startup grace. Скок на часовника в
  Docker VM не може да завиши времето над локалното монотонно измерване.
- Обновени са worker transport, reliability, security и Portal документацията.

## Окончателна проверка — 20.11.2026

Изпълнено върху окончателния код:

    mvn -o verify -Pintegration-tests

BUILD SUCCESS, 275 теста, 0 failures, 0 errors, 0 skipped; 4 минути и 54 секунди.
Проверката приключи на 20.11.2026 в 10:02:02 Europe/Sofia.

| Модул | Unit | Integration |
| --- | ---: | ---: |
| Storage | 7 | 7 |
| Worker | 37 | 68 |
| API | 11 | 145 |

Интеграциите използват реални Docker компилатори, PostgreSQL, Redis, MinIO и HTTP.
OIDC доставчикът е тестов. Включени са всички десет езика, Java regression случаите,
два workers с продължителна оценка, stale heartbeat, Redis recovery, V12 upgrade
и OIDC callback/session/logout през две API инстанции.

При .NET отделно се проверяват нормален nonzero exit и необработено изключение:
ако аварийният обработчик превиши времевия лимит, правилният резултат е TIME_LIMIT,
а ако приключи навреме — RUNTIME_ERROR. Timeout тестовете запазват лимита от 2 секунди.
Файловият regression тест създава и изтрива временни директории по време на мониторинга.

## Внедряване

1. API прилага V12__evaluation_lease.sql. Внедрете API преди новите workers;
   после рестартирайте старите workers, които не поддържат heartbeat.
2. API репликите споделят защитен Redis и еднаква Portal client конфигурация.
   Старите JVM sessions не се мигрират: при първия rollout е нужен нов вход.
3. За worker хоста приложете infrastructure/systemd/README.md или еквивалентна
   изолирана конфигурация с твърда filesystem quota и уникални consumer names.
   Конфигурацията е подготвена в repository; не е инсталирана на production хост.

## Граници

Не се променя продуктовата политика за абсолютни leaderboard scores през revisions.
Course/faculty/enrollment, DEAN/INSPECTOR, frontend и публичен rejudge endpoint
остават извън тази техническа поправка. Token exchange/client_credentials за
чужди модули изискват договор и поддръжка от Portal; чужда audience не се допуска.

Реален Portal staging вход, production capacity/load, Linux deployment и
съгласуван PostgreSQL/MinIO backup/restore не са удостоверени от локалните тестове.
Липсващ source изисква възстановяване от backup; outbox не може да го пресъздаде.
Не са правени commit, deploy или промени по реални потребители.

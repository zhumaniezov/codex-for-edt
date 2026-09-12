# Исследование Codex App Server

## Уточнение для 0.7 — 12 сентября 2026

Перед semantic-этапом executable обновился до **codex-cli 0.154.0-alpha.6.2**, `%LOCALAPPDATA%\OpenAI\Codex\bin\bffc5354119c8421\codex.exe`. Повторно выполнены `--version`, `app-server --help` и генерация JSON Schema в игнорируемый `.runtime/app-server-schema-v070`. На этой версии выполнены реальные тесты; обратная совместимость новых инструментов с прежним executable 0.153.4 отдельно не заявляется.

`dynamicTools` остаётся experimental и не используется. Native-инструменты EDT подключены через стабильный MCP Streamable HTTP и проверенные `thread/start.config` / `thread/resume.config`: локальный URL, `enabled`, `bearer_token_env_var`, таймауты. `experimentalApi` не включается, собственных RPC Codex не добавлено. Собственный JSON Schema metadata plan относится к MCP-инструменту EDT, а не к App Server. Подробности и границы: [EDT Semantic Tools](edt-semantic-tools.md).

Ниже сохранено исследование предыдущих этапов с версией, актуальной на момент их проверки.

Дата: 11 сентября 2026 года. Первоначальное исследование выполнено до второго этапа; перед третьим этапом команды и schema проверены повторно.

## Проверенная версия и источники

- Установлен **codex-cli 0.153.4**.
- Фактический исполняемый файл: `%LOCALAPPDATA%\OpenAI\Codex\bin\7ac07f4ce733f89a\codex.exe`. Имя пользователя намеренно не сохраняется в документации.
- Выполнены `codex --version`, `codex app-server --help`, `codex app-server generate-json-schema --help`.
- Схема сгенерирована установленным файлом: `codex app-server generate-json-schema --out .runtime/app-server-schema-0.153.4`. Флаг `--experimental` не использован.
- [Актуальная документация OpenAI](https://developers.openai.com/codex/app-server/) перенаправляет на [Codex App Server](https://learn.chatgpt.com/docs/app-server).
- Изучен [официальный репозиторий openai/codex](https://github.com/openai/codex), именно [тег rust-v0.153.4](https://github.com/openai/codex/tree/rust-v0.153.4), коммит `3d2ee51ca2d5db578f328aa75e20aa22c0197c9a`.
- Сверены [версионное руководство app-server](https://github.com/openai/codex/blob/rust-v0.153.4/codex-rs/app-server/README.md), [схема конфигурации](https://github.com/openai/codex/blob/rust-v0.153.4/codex-rs/core/config.schema.json) и [правила объединения настроек](https://github.com/openai/codex/blob/rust-v0.153.4/codex-rs/config/src/merge.rs).

Схемы и копия исходников находятся в игнорируемых `.runtime` и `.research`. В Git они не включаются: это воспроизводимые материалы исследования, а не библиотека плагина. При изменении версии Codex схему нужно сгенерировать повторно.

## Транспорт и запуск

Локальный дочерний процесс `codex app-server --listen stdio://`. Запуск непосредственно через Java `ProcessBuilder`, без командной оболочки. Вход и выход — UTF-8 JSONL: один JSON-объект на строку. Поле `jsonrpc` не требуется. STDERR отделён от STDOUT и предназначен для диагностики, а не разбора протокола.

CLI помечает сам app-server и генератор схем как экспериментальные команды. Плагин использует только методы и поля, присутствующие в схеме без `--experimental`; `capabilities.experimentalApi` не включается. Это не обещание неизменности протокола в будущих версиях.

## Handshake и авторизация

Один запрос `initialize` на соединение:

```json
{"id":1,"method":"initialize","params":{"clientInfo":{"name":"codex_edt","title":"Codex for 1C:EDT","version":"0.4.0"}}}
```

После успешного ответа — уведомление `initialized`, затем `account/read` с `refreshToken:false`. Версия clientInfo в реализации берётся из bundle.

В проверенном ответе `requiresOpenaiAuth:true` и `account.type:chatgpt`: существующая авторизация доступна. Отсутствие входа определяется сочетанием требования авторизации и отсутствующего `account`. Само по себе `requiresOpenaiAuth:true` описывает требование провайдера, а не состояние выхода из учётной записи.

Клиент не читает auth.json, токены и хранилище OAuth. Вход, обновление и сохранение учётных данных принадлежат Codex. При отсутствии входа MVP показывает «Требуется вход в Codex» и предлагает выполнить штатный `codex login` отдельно; браузерный вход внутри EDT не реализуется.

## Обнаружение модели

`model/list` с `includeHidden:false`, с обработкой `nextCursor`. Выбирается модель с `isDefault:true`; если её нет — первая модель без `hidden:true`. В запросы передаётся поле `model` записи каталога.

При реальной проверке обнаружена модель по умолчанию **gpt-6-astra**. Её имя не является константой реализации.

## Методы и события установленной схемы

| Метод / событие | Назначение |
| --- | --- |
| `initialize` → `initialized` | Начало соединения |
| `account/read` | Проверка входа без чтения токенов клиентом |
| `model/list` | Модель и страницы каталога |
| `config/read` | Имена MCP-серверов для запрета их инструментов только в этой сессии |
| `thread/start` | Новый диалог для физического каталога проекта |
| `turn/start` | Сообщение пользователя и компактный контекст |
| `turn/started` | Идентификатор начавшегося выполнения |
| `item/agentMessage/delta` | Фрагмент текста с threadId, turnId и itemId |
| `item/completed` | Завершённое сообщение; восстановление текста, если delta не было |
| `turn/completed` | Завершение, статус и возможная ошибка |
| `error` | Ошибка выполнения с признаком willRetry |

ID запросов сопоставляются с ответами; уведомления не имеют ID. Серверные запросы с ID не принимаются за ответы клиента. Неожиданный запрос действия отклоняется; разрешения не выдаются.

## Режим чтения

Схема 0.153.4 требует **`thread/start.sandbox:"read-only"`**, а для выполнения — **`turn/start.sandboxPolicy:{"type":"readOnly","networkAccess":false}`**. В текущей веб-документации есть дополнительные поля read access; они отсутствуют в сгенерированной стабильной схеме этой версии и не используются.

`approvalPolicy:"never"` при read-only запрещает эскалацию и не разрешает запись. Клиент проверяет фактически возвращённые sandbox, cwd и approvalPolicy до первого turn. При несовпадении с режимом чтения выполнение прекращается. Сетевой доступ инструментов запрещён; соединение самого app-server с OpenAI для получения ответа сохраняется.

MCP-серверы могут выполнять действия вне файловой песочницы. Поэтому для этой сессии они отключаются через `thread/start.config`: берутся только имена из `config/read`, каждому задаётся `enabled:false`. Значения настроек не журналируются. Пустая таблица `mcp_servers:{}` недостаточна: официальная реализация рекурсивно объединяет таблицы.

Для дочернего процесса и диалога отключаются дополнительные средства действий: приложения, плагины, браузер, управление компьютером, hooks, уведомления-команды и субагенты. Это временные параметры процесса/диалога; глобальные config.toml, MCP и skills не изменяются. Локальные команды чтения остаются в песочнице Codex.

## EDT-контекст и жизненный цикл

Физический cwd получается через `IProject.getLocationURI()`; для file URI проверяется существующий каталог. Внутренний путь ресурса `/Проект/src/.../Module.bsl` используется только как описание файла. Внешнее расположение импортированного проекта поддерживается. Проект без доступного файлового каталога не отправляется.

На View выбран один текущий диалог, но процесс обслуживает несколько диалогов. В версии 0.3 они имеют `ephemeral:false`, а история принадлежит Codex. Новый чат отсоединяет предыдущий через `thread/unsubscribe`. Resume сохраняет cwd исходного чата и заново проверяет режим чтения. В prompt включаются модуль, выделение и актуальный dirty-буфер; сохранённый модуль целиком автоматически не отправляется.

Работа процесса, JSONL и ожидание RPC выполняются вне SWT-потока. Обновления View проходят через asyncExec. Закрытие View/остановка bundle закрывают только собственный дочерний процесс: сначала stdin, затем ограниченное ожидание и завершение при необходимости. Чужие процессы Codex не затрагиваются.

## Проверки исследования

Реальный локальный probe подтвердил initialize, account/read, model/list, config/read, создание read-only thread/turn, получение 11 delta, неизменность тестового BSL-файла и выход процесса с кодом 0 после закрытия stdin. Из ограниченной среды агента запуск сначала завершился до handshake из-за недоступности служебных каталогов Codex; штатный запуск с доступом самого Codex к своему хранилищу прошёл успешно. Никаких ручных записей в каталог пользователя не выполнялось.

План реализован: отдельные слои процесса, JSONL/RPC и сессии; минимальная адаптация View; воспроизводимые тесты с дочерним тестовым сервером; отдельный явно включаемый реальный тест в EDT; Maven/Tycho и p2 verification. Настоящий ответ через SWT View получен в полном EDT. Итоговые результаты — в [testing.md](testing.md).

## Повторное исследование третьего этапа

`codex --version` снова вернул **0.153.4**. Выполнены `app-server --help` и генерация stable schema в `.runtime/app-server-schema-stage3`. Сверены `ClientRequest.json` и definitions агрегированного `codex_app_server_protocol.v2.schemas.json`. В Git эти файлы не добавляются. CLI находится по тому же пути в локальной установке Codex, указанному выше.

Официальные UX-страницы и границы реализации перечислены в [ide-parity.md](ide-parity.md). Дополнительно в исходниках версии изучены обработчики thread/resume, каталога и обогащения thread. У parent-owned дочерних thread параметры resume могут игнорироваться; поэтому такие thread отфильтрованы и отвергаются при resume, а effective response проверяется всегда.

| Stable RPC | Используемые поля / результат |
|---|---|
| `thread/list` | limit=15, cursor, updated_at, desc, archived=false; sourceKinds cli/vscode/appServer/unknown; data и nextCursor |
| `thread/read` | threadId, includeTurns=false; cwd и parentThreadId |
| `thread/start` | cwd, model, read-only, approvalPolicy=never, approvalsReviewer=user, ephemeral=false, config |
| `thread/name/set` | threadId, name; название первого запроса |
| `thread/resume` | threadId, cwd, model, sandbox=read-only, approvalPolicy=never, config, excludeTurns=true |
| `thread/turns/list` | threadId, cursor, limit=10, sortDirection=desc, itemsView=full |
| `thread/unsubscribe` | threadId; отключает подписку текущего процесса, не удаляет историю |
| `turn/start` | прежние read-only поля плюс **effort** из каталога |
| `turn/interrupt` | threadId и turnId; после ответа ожидается turn/completed со status=interrupted |
| `account/read` | refreshToken=false; type, email, planType без токенов |
| `account/logout` | пустые params; общий выход из Codex, только по действию пользователя |
| `account/updated` | уведомление, после которого обновляется account/read |

`thread/turns/list` выбран вместо гидратации всех turns через resume: он совместим с серверной пагинацией истории. Клиент показывает userMessage.content[type=text] и agentMessage.text; tool items пока не визуализируются. Ни путь внутреннего хранилища thread, ни undocumented поля не используются.

Также подтверждены, но **не реализованы в UI**: `turn/steer` с expectedTurnId/input; `account/login/start` с type=chatgpt и результатом authUrl/loginId; `account/login/completed`. Авторизация из панели отложена, поскольку существующего `codex login` достаточно и вход общий для нескольких клиентов.

## Фактически обнаруженные модели

Каталог получен реальным `model/list` 11 сентября 2026 года под существующей авторизацией. Это результат проверки, не встроенный список плагина. Доступность зависит от аккаунта и может измениться.

| model (protocol) | displayName (UI) | Default effort | Поддерживаемые effort |
|---|---|---|---|
| gpt-6-astra | GPT-6-Astra | medium | low, medium, high, xhigh, max, ultra |
| gpt-5.6-sol | GPT-5.6-Sol | low | low, medium, high, xhigh, max, ultra |
| gpt-5.6-terra | GPT-5.6-Terra | medium | low, medium, high, xhigh, max, ultra |
| gpt-5.6-luna | GPT-5.6-Luna | medium | low, medium, high, xhigh, max |
| gpt-5.5 | GPT-5.5 | medium | low, medium, high, xhigh |
| gpt-5.3-codex-spark | GPT-5.3-Codex-Spark | high | low, medium, high, xhigh |

`isDefault=true` обнаружен у **gpt-6-astra**. UI переводит reasoning: low — «Лёгкое», medium — «Среднее», high — «Высокое», xhigh — «Очень высокое», max — «Максимальное», ultra — «Ультра». Неизвестные новые значения показываются без подмены. Выбор ultra не включает разрешения записи или субагентов; серверные ограничения не обходятся.

## Ограничения третьего этапа

- Проверена точная версия 0.153.4, experimentalApi выключен.
- Сохранённые thread содержат отправленный IDE-контекст, включая dirty-буфер: историю хранит сам Codex. Плагин не ведёт второй архив и не журналирует prompt.
- Удалённые каталоги, занятые другим клиентом thread и слишком большой ответ истории могут потребовать другого чата; клиент не обходит блокировки. JSONL ограничен 2 МиБ на строку, RPC — 45 секундами.
- Browser login из EDT, очередь/steering, архивирование, полный вывод tool items, редактор глобального Codex config и семантика метаданных EDT отложены.
- Режим записи, shell approvals, file change approvals, diff и Apply / Reject отсутствуют.


## Проверка маршрутизации v0.3.1

11.09.2026 повторно подтверждён `codex-cli 0.153.4`. Сверены definitions сгенерированной схемы этой версии: `AgentMessageDeltaNotification` содержит обязательные `threadId`, `turnId`, `itemId`, `delta`; `ItemCompletedNotification` — `threadId`, `turnId`, `item` и `completedAtMs`; `TurnStartedNotification`/`TurnCompletedNotification` — `threadId` и `turn`; `ErrorNotification` — `threadId`, `turnId`, `error`, `willRetry`.

Новые RPC не добавлены. `turn/interrupt` остаётся штатным способом Stop. Поздние события предыдущего turn отбрасываются по идентификаторам; завершённый item не открывается заново из-за поздней delta. Сгенерированные schema остаются вне Git. Реальное подключение после переноса фабрики из конструктора View проверяется теми же opt-in live-тестами через stdio; модель, auth и sandbox не изменены.


## Stable Settings surface 0.4

Повторно проверен CLI **0.153.4**, stdio transport. Использована сгенерированная этим executable схема и `codex-rs/app-server-protocol/src/protocol/common.rs` из `rust-v0.153.4`. У следующих методов нет experimental gate: `config/read`, `config/value/write`, `config/batchWrite`, `mcpServerStatus/list`, `config/mcpServer/reload`, `mcpServer/oauth/login`, `skills/list`, `skills/config/write`, `account/read`, `account/rateLimits/read`, `account/login/start`, `account/login/cancel`, `account/logout`.

Новые вызовы ограничены enum ManagementRequest. Skills write найден, но не используется. MCP reload и rateLimits/read имеют unit/nullable params, передаётся null; JSON serializer сохраняет явный null для config delete. Config writes используют `expectedVersion` из user layer, `replace`, а удаление — `value:null`; семантика подтверждена `config_manager_service.rs::parse_value/apply_edits`.

`McpServerStatus` имеет name, authStatus, tools/resources/resourceTemplates и необязательные runtimeStatus/serverInfo/pluginId. Transport и enabled получаются из config; runtimeStatus null остаётся неизвестным. Ошибка startup может поступать отдельным notification; детальная история таких ошибок пока не хранится, UI показывает доступное runtime state и ошибки RPC.

`Account` предоставляет email/planType у ChatGPT, не display name. Лимиты: usedPercent, windowDurationMins, resetsAt; предпочтителен rateLimitsByLimitId. Nullable данные не означают нулевой расход. Browser login использует только `type:chatgpt`; auth tokens mode с пометкой INTERNAL не применяется. account/login/completed успешно завершает вход, после чего клиент повторяет соединение.

Settings используют отдельный процесс без threads: один временный клиент на окно Preferences, закрываемый вместе с ним. Management MCP запрещён на клиенте, уже обслуживавшем threads. Shared config остаётся у Codex; readOnly и MCP disable overlay агентского процесса сохраняются.


### Изоляция MCP management в 0.4

Проверены публичные исходники `app-server/src/mcp_refresh.rs` (`reload_mcp_config`, тест `refresh_config_preserves_thread_mcp_overrides`) и `request_processors/thread_processor.rs` (сообщение `thread/resume overrides ignored for loaded thread`). Reload перечитывает MCP для loaded threads; новый сервер мог отсутствовать среди исходных disabled overrides. Поэтому Preferences используют отдельный owned process без threads, а management MCP на агентском клиенте запрещён. Глобальная конфигурация остаётся общей; файл не редактируется нашим Java-кодом. Код VS Code Extension не используется.

Schema повторно сгенерирована установленным `codex-cli 0.153.4` в `.runtime/app-server-schema-stage4` 11.09.2026. Временные schema и сторонние исходники не публикуются.

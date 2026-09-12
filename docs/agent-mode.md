# Агентный режим 0.6

## Проверка протокола

Исходная версия проекта: `20a1e8c` (0.5). На 12 сентября 2026 проверены
`codex --version`, `codex app-server --help` и заново выполнена
`codex app-server generate-json-schema --out .runtime/app-server-schema-v060`.
Обнаружен **codex-cli 0.153.4**, executable в
`%LOCALAPPDATA%/OpenAI/Codex/bin/7ac07f4ce733f89a/codex.exe`.
Schema временная, исключена из Git. Transport остаётся stdio JSONL.

Источники: [официальный App Server](https://learn.chatgpt.com/docs/app-server),
[режимы прав](https://learn.chatgpt.com/docs/permission-modes),
[sandbox](https://learn.chatgpt.com/docs/sandboxing),
[Windows sandbox](https://learn.chatgpt.com/docs/windows/windows-sandbox),
[исходники точной версии](https://github.com/openai/codex/tree/rust-v0.153.4/codex-rs).
Для точной семантики проверены `app-server-protocol/src/protocol/v2/{thread,turn,item,permissions}.rs`
и `core/src/safety.rs`; код OpenAI не копируется в плагин.

Read-only RPC проверка реального процесса: `configRequirements/read` вернул
`requirements: null`. `permissionProfile/list` вернул разрешённые
`:read-only`, `:workspace`, `:danger-full-access`. Через `config/read` обнаружен
`windows.sandbox = unelevated`. Значения могут измениться: плагин должен читать их
заново, а не считать это постоянной характеристикой компьютера.

## Stable и experimental

В schema без experimentalApi есть `sandbox`, `sandboxPolicy`, `approvalPolicy`,
`approvalsReviewer`, `configRequirements/read`, `permissionProfile/list`.
Выбор именованного профиля через `thread/start.permissions` и
`turn/start.permissions` помечен experimental в исходниках этой версии и отсутствует
в stable schema. Поэтому используются legacy sandbox overrides с проверкой
requirements и разрешения соответствующего встроенного профиля. Произвольные
пользовательские permission profiles нельзя незаметно подменять этим отображением.

| UI | sandbox | approvalPolicy | approvalsReviewer |
| --- | --- | --- | --- |
| Только чтение | readOnly | never | user |
| Запрашивать разрешение | workspaceWrite | on-request | user |
| Строгое подтверждение | workspaceWrite | untrusted | user |
| Подтверждать автоматически | workspaceWrite | on-request | auto_review |
| Полный доступ | dangerFullAccess | never | user |

«Запрашивать разрешение» соответствует официальному Ask for approval: обычные
записи внутри sandbox разрешены самим Codex. Это **не** обещание approval на каждый
файл. Дополнительный строгий вариант использует существующий `untrusted`:
`assess_patch_safety` этой версии запрашивает подтверждение patch. Автоматический
review выполняет Codex; клиент не отвечает accept за пользователя. Полный доступ
требует отдельного подтверждения, снимает границу проекта и ограничение сети.
Начальное состояние и восстановление View — только чтение; права не записываются
в общую конфигурацию Codex.

## Граница проекта и IDE

Физический `IProject.getLocationURI()` проверяется через
`Path.toRealPath`; `workspaceWrite.writableRoots` содержит только этот каталог.
`excludeTmpdirEnvVar` и `excludeSlashTmp` включены; networkAccess выключен.
Глобальный config не редактируется. Управление MCP остаётся отдельным процессом;
MCP, hooks и другие обходящие файловую sandbox инструменты не включаются для turns.

Перед write turn проверяются все редакторы проекта во всех окнах. Сохранение
требует явного выбора. На время работы редакторы проекта защищаются от ввода.
Refresh выполняется через публичный Eclipse workspace API, без собственного BSL
validator. Patch выполняет исключительно App Server. Review не является Apply.

## Запросы и события

`item/fileChange/requestApproval` имеет threadId/turnId/itemId и response decision:
accept, acceptForSession, decline, cancel. grantRoot помечен UNSTABLE и не используется
для скрытого расширения writableRoots. Session означает область сервера, не global.

`item/commandExecution/requestApproval` добавляет command/cwd/reason/kind,
networkApprovalContext (host/protocol). availableDecisions отсутствует в stable
schema 0.153.4 (experimental); stable решения берутся из response enum. Постоянные
execpolicy/network amendments на этом этапе не записываются.

`item/permissions/requestApproval`: ответ содержит только разрешённое подмножество
запрошенных permissions и scope turn/session. Отказ — пустые permissions. Никаких
новых путей или более широких glob со стороны клиента.

`item/started`, `item/completed`, `item/fileChange/patchUpdated`,
`item/commandExecution/outputDelta`, `turn/diff/updated`, `serverRequest/resolved`
маршрутизируются по реальным id. Approval response использует исходный JSON-RPC id,
включая различие числа и строки, а не id исходящих клиентских запросов.

## Windows

`windowsSandbox/setupStart` существует, но не вызывается автоматически.
Плагин использует установленный режим Codex. Если sandbox не готова, пользователь
настраивает её через официальный Codex и затем повторяет подключение.
Elevated setup меняет системные права/пользователей и требует явного решения
пользователя; fallback/full access не включается для обхода ошибки setup.

## Проверки

Maven/Tycho: **BUILD SUCCESS**; 118 тестов, 114 выполнены успешно, четыре live
выделены в отдельные запуски. Настоящий агентный сценарий в полной EDT 2026.1.3.25
прошёл: edit/create/delete, шесть обработанных approvals, Java build/run с stdout
и exit code, diff, refresh существующего редактора и Navigator, отказ записи в
соседний sentinel, возврат к чтению с неизменными SHA-256. Модель `gpt-6-astra`,
один thread на все turn. Финальный журнал: `.runtime/edt-smoke-20260912173950`.
Destructive сценарии выполнены только в специально созданном тестовом проекте.
Автоматический Undo/Revert не реализуется: он мог бы отменить изменения пользователя.

Live-проверка использует обычный Text Editor внутри полного продукта EDT;
Save/guard/reconcile конкретного metadata BSL-редактора требует ручного TC-62/63.
Если редактор не предоставляет публичный Control либо ITextViewer, запись
отклоняется с понятным сообщением. Блокировка защищает ввод через редактор, но не
является блокировкой файла от другого процесса или параллельного стороннего плагина.

У ранних live-прогонов обнаружены различия тестовых ожиданий: Codex может заменить
файл delete+add (Eclipse тогда штатно закрывает старый editor); Update File сохраняет
редактор и обновляет document. Команда Set-Content была отклонена самой моделью
из-за инструкции применять patch для изменения исходников. Поэтому command test
выполняет настоящие javac/java над готовой фикстурой, а не меняет эти инструкции.
Пустой aggregatedOutput у завершённого javac допустим; вывод проверяется у java.

Permissions grant и acceptForSession проверены protocol-тестами с тестовым сервером.
В live проверены ручные accept/decline; Full access, auto_review, расширение сети и
Windows elevated setup не выполнялись на компьютере пользователя. Тест не выдавал
прав за пределами своего проекта.


### Нормализация effective sandbox

Реальный `thread/start` 0.153.4 возвращает `writableRoots: []`, если единственный
явно указанный root совпадает с cwd: cwd уже включён в workspaceWrite. Проверено
отдельным RPC без turn и по `protocol/src/permissions.rs` (`get_writable_roots_with_cwd`).
Клиент принимает пустой массив либо повторение точного cwd; любые дополнительные
посторонние roots отклоняет. `excludeTmpdirEnvVar`, `excludeSlashTmp`, network flag,
reviewer, approval policy, cwd и модель проверяются независимо.

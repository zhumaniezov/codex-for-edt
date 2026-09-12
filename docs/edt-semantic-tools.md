# Семантические инструменты EDT — 0.7 и развитие 0.8

Текущий native слой 0.8 описан в [EdtToolPlatform](edt-tool-platform.md), [аудите](edt-capabilities.md) и [матрице](edt-capability-matrix.md). Ниже сохранено исследование исходного 0.7; ограничение на шесть статических инструментов и одну задачу BM относится к той версии. Текущая schema — `.runtime/app-server-schema-v080`, каталог динамический, developerInstructions передаётся только текущей серверной сессии.

Baseline: `e37d291`. Установленная EDT: 2026.1.3.25; Eclipse 4.30, Java 17. Проверка 12 сентября 2026 года обнаружила обновление Codex до `0.154.0-alpha.6.2`. Сохранённая schema 0.153.4 остаётся основой проверки совместимости; новая schema сгенерирована в игнорируемом `.runtime/app-server-schema-v070`.

## Источники и публичные контракты

- [Объекты конфигурации](https://edt.1c.ru/dev/ru/docs/plugins/dev/model/), [интеграционный слой](https://edt.1c.ru/dev/ru/docs/plugins/dev/model/integration/).
- [Публичные OSGi-сервисы](https://edt.1c.ru/dev/ru/docs/plugins/dev/public-services/), [сервисы общего назначения](https://edt.1c.ru/dev/ru/docs/plugins/dev/edt-services/).
- [IModelObjectFactory](https://edt.1c.ru/dev/edt/2024.2/apidocs/com/_1c/g5/v8/dt/core/model/IModelObjectFactory.html), [MdObjectFactory](https://edt.1c.ru/dev/edt/2024.2/apidocs/com/_1c/g5/v8/dt/md/model/MdObjectFactory.html). Онлайн Javadoc относится к 2024.2; сигнатуры отдельно сверяются через публичные декларации установленной 2026.1.3.25, без декомпиляции реализации.
- [Codex App Server](https://learn.chatgpt.com/docs/app-server), официальный исходный код [OpenAI Codex](https://github.com/openai/codex), schema установленного executable.
- [MCP Streamable HTTP](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports).

| Контракт | Классификация | Назначение |
| --- | --- | --- |
| `IV8ProjectManager`, `IConfigurationProvider`, `IConfigurationProjectManager` | PUBLIC, документированные OSGi-сервисы | Проекты и конфигурации |
| `IBmModelManager`, `IBmPlatformTask`, editing contexts | PUBLIC, официальное руководство | Транзакции, сохранение и события |
| `ITopObjectFqnGenerator` | PUBLIC, официальный сервис | FQN без самостоятельной конкатенации |
| `IModelObjectFactory`, `MdObjectFactory`, `md.model` initializers | PUBLIC: Javadoc и экспорт `md.model` 2.1.0 без `x-internal` | Создание объектов с defaults версии платформы проекта |
| `metadata.mdclass`, `mcore` | PUBLIC, экспортируемая EMF-модель | Containment и структурированные типы |
| `*.internal.*`, `*.impl.*` | INTERNAL | Прямые зависимости запрещены |
| Семантика undocumented сервисов, перенос кода MCP-RSV | UNKNOWN | В реализацию не переносится |

## Выбранное редактирование

Один согласованный план выполняется одной `IBmPlatformTask` в отдельном `IBmPlatformLocalEditingContext`, полученном через `IBmModelManager.createLocalEditingContext`. Порядок: `execute` → `save(true)` → `dispose`. Контекст принадлежит только плану и не требует открытого редактора. Синхронное сохранение выбрано после native-проверки: глобальный context корректно менял BM, но его отложенная выгрузка ещё не завершалась к моменту ответа инструмента. Отдельный context позволяет завершать инструмент после штатного сохранения, без циклического refresh всего workspace и без ручного XML.

Исключение внутри `execute` вызывает rollback всей BM-задачи. `executeReadWriteTask` вне editing context не подходит: он не сохраняет ресурсы. `executeImportTask` не используется: он затрагивает несохранённые локальные контексты. После `save(true)` ожидаются ранее поставленные model events и synchronization; читаются стандартные маркеры Problems. Окончательная фоновая валидация EDT может обновить Problems позже.

Чтение выполняется через `executeReadOnlyTask`; EObject актуализируется в текущей транзакции. В мост возвращаются копии JSON, а не объекты BM. Сохранение и события принадлежат EDT. Пользовательский Undo не обещается: история временного контекста не подключена к workbench и освобождается. Ошибка ресурсного сохранения после commit отличается от rollback самой транзакции; плагин не обещает атомарность файловой системы при отказе диска.

Сервис фабрики найден штатным OSGi lookup `IModelObjectFactory`, свойство `service.name=MdObjectFactory`. Он применяет initializers версии `IV8Project.getVersion()`. Корневые объекты прикрепляются к namespace через `attachTopObject`, FQN выдаёт `ITopObjectFqnGenerator`. Реквизиты создаются по реальному EClass containment-ссылки родителя, без вымышленного общего класса `Attribute`.

Примитивы `String`, `Number`, `Boolean`, `Date` разрешаются через публичный Xtext `IResourceServiceProvider` / `IPlatformScopeProvider`; квалификаторы создаёт `McoreFactory`. Ссылки используют `Catalog.getProducedTypes().getRefType().getType()`. Неизвестный ссылочный объект возвращает ошибку и откатывает план. Это версия модели платформы EDT, а не утверждение о точной установленной сборке `1cv8`.

Пути ресурсов выдаёт `IResourceLookup`; путь BSL общего модуля — `IProjectFileSystemSupport`. Пустой BSL EDT может не сохранять отдельным файлом: после native создания общий модуль уже существует, а первое содержимое `Module.bsl` может создать штатный файловый инструмент Codex.

## Мост Codex → EDT

`dynamicTools` и `item/tool/call` остаются experimental. Предпочтён стабильный MCP Streamable HTTP на `127.0.0.1`, случайный порт, авторизация случайным секретом экземпляра. Проверяются Host/Origin и жизненный цикл. Секрет передаётся дочернему процессу через environment; в config указывается имя переменной, а не её значение. Конфигурация собственного MCP передаётся только процессу/потоку клиента, без записи глобального config.toml. Прочие внешние MCP не включаются этим действием.

Авторизация MCP сама по себе не разрешает мутацию: сервис отдельно проверяет живой turn, режим, привязанный проект, dirty guard и решение пользователя. В строгом режиме план предъявляется до выполнения BM-задачи. Read-only отклоняет все изменения.

HTTP обслуживает уже поставляемый с целевой EDT Jetty 10. Не добавляются Browser, Node.js, внешний сервер или отдельный процесс MCP. Случайное имя `codex_edt_native_<идентификатор>` исключает конфликт с пользовательскими MCP. URL/имя переменной передаются в stable `thread/start.config` / `thread/resume.config`; значение секрета существует только в памяти и environment дочернего app-server.

MCP не передаёт Codex thread/turn автоматически. Поэтому наш собственный JSON Schema инструментов требует `turnKey` — идентификатор текущего вызова IDE, добавленный к текущему сообщению. Сервер сопоставляет его с реальным `threadId` и `turnId` из ответа `turn/start`. Это идентификатор корреляции, не HTTP-секрет. Старые ключи немедленно недействительны после Stop, окончания, disconnect или закрытия View. Отмена разблокирует ожидающие approvals; защита редакторов сохраняется до окончания собственных операций.

## Доступные инструменты

| Инструмент | Результат |
| --- | --- |
| `edt_get_project_context` | Проект, физический cwd, конфигурация, версия модели платформы |
| `edt_get_configuration_info` | Имя, синоним, количество справочников и общих модулей |
| `edt_list_metadata_objects` | Корневые объекты, фильтр kind/name, страницы по 100 |
| `edt_find_metadata_object`, `edt_get_metadata_object` | Объект, свойства, UUID/FQN, реквизиты, типы и табличные части |
| `edt_apply_metadata_plan` | Один согласованный план; созданные/обновлённые объекты или `alreadyExists`, Problems |

План поддерживает `createCatalog`, `createCommonModule`, `addAttribute`, `addTabularSection`, `setProperties`. Для реквизита табличной части указывается `catalog` и `tabularSection`. До 32 операций в плане, 64 реквизитов на коллекцию, 32 табличных частей; HTTP request до 512 КиБ. Ограничения явно отражены в schema/ошибках, текст не обрезается скрыто.

Свойства общего модуля: `clientManagedApplication`, `clientOrdinaryApplication`, `server`, `externalConnection`, `serverCall`, `global`, `privileged`. Свойства справочника: `hierarchical`, `codeLength`, `descriptionLength`, `autonumbering`, `checkUnique`. Поддерживаются синонимы. Типы: строка 0…1024, число 1…38 с точностью не выше разрядности, булево, дата/время/дата-время, ссылка на существующий справочник.

## Граница полномочий и UI

Приоритет проекта: cwd существующего thread → активный редактор → Navigator → выбранный проект → единственный открытый проект конфигурации → chooser. Явный выбор в компактном блоке «Проект» закрепляет выбор для нового чата. Смена проекта существующего thread требует подтверждения и нового чата. Пустой startup без editor/project не мешает созданию View.

«Только чтение» отвергает native запись. «Строгое подтверждение» и «Запрашивать разрешение» показывают отдельный native план до изменения BM. Автоматический режим выполняет план в рамках явно выбранного режима и текущего проекта. Даже Full Access не расширяет namespace semantic tools: он остаётся привязанным к проекту. Dirty guard обязателен для записи, как и в файловом режиме; пути ресурса проверяются на принадлежность проекту, linked resources и выход через filesystem links.

Существующие серверные file/command approvals не заменены: они относятся к файловым инструментам. Native approval управляет нашим BM-планом. Второго фиктивного Apply после выполненной операции нет.

## Ограничения первой semantic версии

Не реализованы удаление/переименование метаданных, формы, документы, регистры, роли, подсистемы и глубокое семантическое редактирование BSL AST. Прочие корневые классы доступны для чтения. Существующие имена возвращают `alreadyExists`; дополнительные реквизиты существующему объекту добавляются отдельной явной операцией. Полноценная история native activity старого thread не восстанавливается; серверный ответ остаётся в истории Codex. Файловые инструменты по-прежнему могут изменять BSL по запросу пользователя; агент получает инструкцию использовать native tools для metadata XML.

## План проверки

Сначала проверить фабрики и реальные OSGi регистрации в полной EDT, затем чтение и атомарную запись в одноразовом проекте. Отдельно проверить отсутствие редактора, выбор проекта, типы, дубликаты, rollback, подтверждения, сохранение после restart и реальный вызов Codex через MCP. Результаты фиксируются в `testing.md`; наличие этого документа само по себе не означает готовность версии.
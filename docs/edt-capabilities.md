# Аудит возможностей EDT 2026.1.3

Исследование следующего этапа после `54d9fca`. Целевая установка: **1C:EDT 2026.1.3.25**, Eclipse 4.30, Java 17. Исследованы активные версии из `configuration/org.eclipse.equinox.simpleconfigurator/bundles.info`, manifest, Export-Package, plugin.xml, OSGi declarations и публичные сигнатуры. Закрытый Java-код не декомпилируется и не копируется. Снимок исследования находится в игнорируемом `.research/v080`.

Основной инвентарь (189 bundles) дополнен публичным `com._1c.g5.v8.derived` 18.0.0.v202609041212 для ожидания расчёта производных данных. В сумме исследование включает **190 bundles**, относящихся к проектам, BM, метаданным, формам, BSL, CLI, checks, formatter, навигации и debug/run. Количество классов не является количеством поддержанных возможностей. Таблица операций и подтверждённое покрытие ведутся отдельно в [матрице](edt-capability-matrix.md).

## Классификация

- `PUBLIC_SUPPORTED`: операция основана на документированном публичном контракте; экспорт и сигнатура подтверждены на текущей Target Platform. Для production реализации требуется также integration test.
- `PUBLIC_BUT_EXPERIMENTAL`: контракт публичный, но источник явно предупреждает об экспериментальном статусе. По умолчанию такие возможности не включаются.
- `INTERNAL`: internal/impl package, restricted export или использование требует закрытой реализации. В матрице недоступная операция отмечается `INTERNAL_ONLY`.
- `UNKNOWN`: публичная декларация есть, но назначение, invariants или поддержка production использования ещё не доказаны. Наличие JavaDoc, класса в JAR или Export-Package само по себе недостаточно.

`x-provider-type` означает ограничение на реализацию/наследование интерфейса клиентом и не равнозначен `x-internal`. Используем предоставленные EDT объекты и сервисы, не реализуем provider interfaces самостоятельно.

## Подтверждённые семейства

| Область | Активный bundle / пакет | Основание и решение |
| --- | --- | --- |
| Проекты, resources, BM facade | `com._1c.g5.v8.dt.core` 27.0.2, `core.platform` | IV8ProjectManager, IConfigurationProjectManager, IConfigurationProvider, IResourceLookup, IBmModelManager; публичный integration layer |
| BM transaction/editing/events | `bm.core`, `bm.integration` | Транзакции и editing contexts; прямой executeReadWriteTask не используется для сохраняемых agent changes |
| Metadata | `dt.metadata` 19.0.0, `metadata.mdclass` | Configuration, MdObject, EClass/EStructuralFeature; модель читается напрямую для descriptors |
| Defaults | `dt.md` 27.1.0, `md.model` 2.1.0 | IModelObjectFactory / MdObjectFactory и публичные initializers; версия IV8Project, а не строка совместимости |
| Формы | `dt.form` 32.0.0, `form.generator`, `form.service.*`; `dt.form.model` 16.0.0 | IFormGenerator / IFormFieldGenerator, FormFactory и публичная модель. Доступ через зарегистрированный resource provider `.form`; internal extension factory не импортируется |
| BSL | `dt.bsl` 28.0.2, `bsl.resource`, `bsl.scoping`; `dt.bsl.model` 12.0.1 | Xtext resource/document, Module/Method, NodeModelUtils, EObjectAtOffsetHelper, TypesComputer, IScopeProvider |
| Workspace/редакторы | Eclipse core.resources, ui.ide, Xtext UI | IProject.build, refresh, markers, IDE/editor services и IXtextDocument |
| CLI | `com.e1c.g5.v8.dt.cli.api` 4.0.201; `com._1c.g5.v8.dt.cli.api` 0.2.900 | ICliCommandExecutor описан в разделе тестирования. IValidateApi/IWorkspaceSupport экспортированы; production назначение и side effects проверяются отдельно |
| Checks | `com.e1c.g5.v8.dt.check` 6.0.101 | Экспорт подтверждён. Не делаем custom checks обязательным production API: стандартные Problems/builders доступны без него |
| Formatter | Xtext IFormatter2; `com.e1c.g5.v8.dt.formatter.bsl` 1.1.200 | Конкретный IFormatBslFilesApi виден в JAR, но bundle не экспортирует service package. Не импортируем; исследуем публичный Xtext formatter provider |
| Debug/run | `dt.debug.core` 19.0.1, Eclipse DebugPlugin/ILaunchManager | Публичные модели targets/breakpoints и launch configurations исследуются отдельно; запуск живой базы не входит в автоматические проверки |

Большинство коллекций корневых metadata — **не containment**. Например, `catalogs` и `documents` содержат ссылки на BM top objects; `languages` — containment. `defaultRoles`, `standaloneConfigurationRestrictionRoles`, абстрактные `content` и `additionalFullTextSearchDictionaries` не являются отдельными типами создаваемых объектов. Реестр должен отличать владение от произвольных ссылок.

## Источники

[Модель конфигурации](https://edt.1c.ru/dev/ru/docs/plugins/dev/model/), [integration layer](https://edt.1c.ru/dev/ru/docs/plugins/dev/model/integration/), [публичные сервисы](https://edt.1c.ru/dev/ru/docs/plugins/dev/public-services/), [BSL/Xtext](https://edt.1c.ru/dev/ru/docs/plugins/dev/lang/), [BSL model](https://edt.1c.ru/dev/ru/docs/plugins/dev/lang/bsl-model/), [EDT CLI](https://edt.1c.ru/dev/ru/docs/plugins/dev/cli/), [тестирование](https://edt.1c.ru/dev/ru/docs/plugins/dev/testing/). Старый онлайн JavaDoc используется для контекста; сигнатуры перепроверяются по установленным JAR.

Codex: [App Server](https://learn.chatgpt.com/docs/app-server) и [MCP](https://learn.chatgpt.com/docs/extend/mcp). Повторно проверен CLI **0.154.0-alpha.6.2** и schema `.runtime/app-server-schema-v080`. Stable `developerInstructions` доступен в thread/start и thread/resume. Dynamic client tools остаются experimental; сохраняется session-scoped MCP.

## Подтверждение на runtime

Нормальный public package `com._1c.g5.v8.derived` экспортирован с `x-provider-type:=IDerivedDataManager`: используем предоставленный сервис, не реализуем интерфейс. `IDerivedDataManagerProvider.get(IProject)` получает менеджер именно текущего проекта; `waitAllComputations(60000)` вызывается вне BM-задачи. Ожидание одних model events не гарантирует готовность ссылочных типов и DbView.

Проверены 48 create/update/save/reopen, генерация OBJECT-формы документа с таблицей, дополнение GENERIC-формы, модуль HTTP-сервиса, регистр сведений, Xtext scope/format/document save. `IURIEditorOpener` из BSL resource provider открывает ещё не выгруженный пустой модуль через native editor input. Прямой `FileEditorInput` для такого модуля недостаточен. Для обычного существующего документа применяется document provider; уже открытый редактор переиспользуется.

`IV8ProjectLifecycleEvent.DELETED` используется в тестовом close/open стенде для ожидания остановки контекста. Проверки user-facing restore дополнительно запускаются в двух отдельных процессах EDT. Ни один internal implementation class из диагностических stack traces не импортируется.

## Codex MCP approval

Обнаруженный на live-тесте stable `mcpServer/elicitation/request` проверен по schema 0.154.0-alpha.6.2 и [официальному README App Server](https://github.com/openai/codex/blob/main/codex-rs/app-server/README.md). Поддерживается только approval собственного session MCP: текущие serverName/threadId/turnId, пустая form-schema, `_meta.codex_approval_kind=mcp_tool_call`. Ответ — action accept с пустым content или decline. Ввод произвольных данных, URL и постоянные grants не поддерживаются. UI подтверждает вызов инструмента, затем отдельно показывает native change plan.

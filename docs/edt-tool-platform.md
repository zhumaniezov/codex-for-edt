# Платформа нативных инструментов EDT

Архитектура 0.8: существующие App Server, threads, permissions и SWT UI сохраняются. Расширяется слой возможностей внутри JVM EDT.

```text
Codex App Server
  → один локальный MCP bridge
  → EdtToolRegistry: schema + permission + actual capability
  → EdtToolExecutionContext: project + thread + turn + cancellation
  → metadata / forms / BSL / navigation / diagnostics / tooling
  → публичные сервисы EDT
```

Каталог инструментов определяется фактическими сервисами и типом проекта. Неподдержанный адаптер не объявляется работающим. Extension project никогда не заменяется базовым проектом; сначала доступно исследование его capabilities, а mutation включается только после отдельной проверки invariants расширения.

MetadataTypeRegistry описывает EClass, связь Configuration, безопасные свойства, дочерние коллекции, modules/forms/commands/templates и type-bearing features. Native операции создаются через version-aware factory и FQN generator. Произвольный setEObjectProperty не предоставляется.

Запись проходит через валидируемый Change Plan. Preview → approval → apply → synchronization → diagnostics → результат. Код ошибки машиночитаемый; UI локализован, технические детали DTO предназначены Codex; stack trace остаётся в Error Log. Инструменты не запускают собственный agent/repair loop: Codex получает диагностику и принимает следующее решение в текущем ограниченном turn.

Transport: MCP Streamable HTTP на 127.0.0.1, случайный порт и bearer-секрет экземпляра; immutable project/turn context, dirty guard и lifecycle. Глобальные AGENTS.md, auth и MCP settings не меняются. Правило предпочитать native tools передаётся официальным полем developerInstructions текущей серверной сессии.

Фактическая готовность каждого инструмента и теста фиксируется в [матрице возможностей](edt-capability-matrix.md). Наличие строки в матрице или публичного класса само по себе не означает реализованную capability.


## Каталог инструментов

В конфигурации объявлены **20** инструментов; в расширении — **16**, без четырёх мутаций. Каждая JSON Schema требует текущий `turnKey`; endpoint, thread, turn и project проверяются независимо. Ограниченный read-only режим не даёт право записи даже при наличии описания write-tool.

| Область | Инструменты |
| --- | --- |
| Проект/конфигурация | edt_get_project_context, edt_get_configuration_info |
| Метаданные | edt_list_metadata_objects, edt_find_metadata_object, edt_get_metadata_object, edt_list_metadata_types, edt_describe_type, edt_get_children, edt_get_modules, edt_get_references |
| Платформа/проверки | edt_capabilities, edt_get_problems, edt_validate_project |
| BSL | edt_bsl_read, edt_bsl_context, edt_bsl_edit, edt_bsl_format |
| Navigation/debug | edt_open_resource, edt_debug_state |
| Запись модели | edt_apply_metadata_plan |

`edt_get_references` возвращает прямые metadata references; это не граф всех входящих BSL ссылок. `edt_debug_state` исключает launch configurations без mapped resources текущего проекта и не возвращает конфигурационные attributes.

## Контракт плана

Сначала `edt_describe_type(kind)`: реальные имена children/properties/enums, module slots, ограничения create. `create/update` принимают kind/name, synonym, разрешённые scalar properties, type, children. `addChild/updateChild` — collection и child; path проходит массивом collection/name по дочерней модели. `addReference` поддерживает content подсистемы. `removeChild` отделён от update и всегда требует approval; формы/templates/root не удаляются этим механизмом.

```json
{"operations":[
  {"operation":"create","kind":"Catalog","name":"Номенклатура",
   "children":{"attributes":[{"name":"Артикул","type":{"kind":"String","length":30}}]}},
  {"operation":"create","kind":"Document","name":"ЗаказКлиента",
   "children":{"tabularSections":[{"name":"Товары","children":{"attributes":[
     {"name":"Номенклатура","type":{"kind":"CatalogRef","catalog":"Номенклатура"}}]}}]}},
  {"operation":"addChild","kind":"Document","name":"ЗаказКлиента","collection":"forms",
   "child":{"name":"ФормаДокумента","form":{"template":"OBJECT",
     "commands":[{"name":"Заполнить","handler":"Заполнить"}],
     "items":[{"kind":"button","name":"Заполнить","command":"Заполнить"}]}}}
]}
```

BSL задаётся отдельно: прочитать модуль, получить SHA-256, передать массив непересекающихся offset/length/text в `edt_bsl_edit`. Плагин проверяет revision и dirty state, меняет IXtextDocument и вызывает native save. AST служит для анализа, не для генерации обратного текста. В одном atomic metadata plan не смешиваются BSL/format/build.

## Защита

Собственный bridge обслуживает только localhost с проверками Host/Origin, случайного route и bearer из environment. Максимум тела запроса — 512 KiB; в plan до 32 операций, коллекции до 64 элементов, path до 8 шагов; текст BSL edits до 256000 символов. Paths проверяются относительно физического root, включая symlink/linked resource escape. Сохранение пользовательских dirty buffers остаётся явным действием. Stop/смена thread/dispose инвалидируют permit; поздний approval не начинает mutation.

Ошибки имеют коды OBJECT_NOT_FOUND, OBJECT_ALREADY_EXISTS, INVALID_PROPERTY, INVALID_TYPE, REFERENCE_NOT_FOUND, PROJECT_BOUNDARY, EDITING_CONFLICT, UNSUPPORTED_BY_EDT_VERSION или EDT_OPERATION_FAILED. Неизвестный descriptor не переключает инструмент на shell/XML fallback.

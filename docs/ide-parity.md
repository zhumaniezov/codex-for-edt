# Соответствие Codex IDE: третий этап

Исследование выполнено 11 сентября 2026 года перед реализацией. Протокол проверен по стабильной JSON Schema установленного Codex CLI **0.153.4**, сгенерированной без `--experimental` в игнорируемый `.runtime/app-server-schema-stage3`. Базовая версия плагина — commit `4714953` (0.2).

## Официальные источники

- [Codex IDE](https://learn.chatgpt.com/docs/codex/ide): боковая панель, composer, контекст редактора.
- [Контекст и prompting](https://learn.chatgpt.com/docs/prompting): выделение, открытые файлы, очередь и steering.
- [Настройки IDE](https://learn.chatgpt.com/docs/developer-settings?surface=ide) и [команды](https://learn.chatgpt.com/docs/developer-commands?surface=ide).
- [Модели](https://learn.chatgpt.com/docs/models), [конфигурация](https://learn.chatgpt.com/docs/config-file/config-basic), [проекты и диалоги](https://learn.chatgpt.com/docs/projects).
- [Учётная запись](https://learn.chatgpt.com/docs/auth), [разрешения](https://learn.chatgpt.com/docs/sandboxing), [Skills](https://learn.chatgpt.com/docs/build-skills), [MCP](https://learn.chatgpt.com/docs/extend/mcp).
- [App Server](https://developers.openai.com/codex/app-server/) и [исходники точной версии](https://github.com/openai/codex/tree/rust-v0.153.4/codex-rs/app-server).
- Eclipse: [ITextEditor](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/ui/texteditor/ITextEditor.html), [IDocumentProvider](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/ui/texteditor/IDocumentProvider.html), [StyledText](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/swt/custom/StyledText.html).
- [CommonMark Java 0.30.0](https://github.com/commonmark/commonmark-java/tree/commonmark-parent-0.30.0): небольшой самостоятельный OSGi bundle, Java 11+, BSD-2-Clause. Используется только AST parser, без HTML renderer и браузера.

## Карта функций

В колонке «Уже есть» указан принятый baseline 0.2. Это функциональное сопоставление, а не использование внутренних API расширения VS Code.

| Функция Codex IDE | App Server API | Уже есть в EDT | Третий этап | Будущий этап |
|---|---|---|---|---|
| Sidebar | Локальный UI | Dockable View | Native SWT/JFace | — |
| Новый чат | thread/start | Автоматически при первом Send | Отдельная кнопка, создание при первом сообщении | — |
| Список чатов | thread/list | Нет | Последние чаты, серверная пагинация | Поиск, архивирование |
| Продолжение | thread/resume | Нет | Resume по id с проверкой read-only | — |
| История текущего чата | thread/turns/list | Только последний ответ | Серверные сообщения, подгрузка старых turn | Поиск в сообщениях |
| Название чата | thread/name/set | Нет | Название из первого запроса | Ручное переименование |
| Model selector | model/list, turn/start.model | Default id | displayName, динамический список | Глобальные настройки модели |
| Reasoning | model/list, turn/start.effort | Нет | Возможности и default выбранной модели | — |
| Send | turn/start | Да | Composer | — |
| Stop | turn/interrupt | Нет | Без перезапуска процесса | — |
| Streaming | item/agentMessage/delta | Да | Обновляемый native renderer | Подробные tool items |
| Editor context | Текст input + cwd | Проект, модуль | Сохранить, расширяемый ContextProvider | Метаданные, формы, диагностика |
| Selection | Текст input | Да | Отдельная маркировка диапазона | — |
| Unsaved editor | Текст input, IDE API | Нет | Dirty IDocument, приоритет над диском | Точный diff буфера и диска |
| Большой dirty-модуль | Текст input | Нет | Явное окно вокруг caret/selection | Запрос дополнительных окон |
| Account | account/read, account/logout | Проверка входа | Информация и выход | Browser login при необходимости |
| Status | Локальная машина состояний | Технический текст | Короткий статус, диагностика отдельно | — |
| Composer | Локальный UI | Text + Send | Placeholder, модель, reasoning, read-only | Вложения |
| Queue / steer | turn/steer (stable) | Нет | Только исследование | Очередь и steering |
| Settings | Codex config / Eclipse Preferences | Конфигурация Codex | IDE Enter и диагностика в Eclipse | Дополнительные IDE настройки |
| MCP | config/read, ограничения запуска | Отключены для сессии | Сохранить запрет внешних действий | Отдельная политика инструментов |
| Skills | Codex discovery | Работают | Сохранить под read-only sandbox | UI выбора Skills |
| Approvals | Серверные запросы approval | Отклоняются | Сохранить запрет | Только отдельный согласованный этап |
| Diff / review | События items, review API | Нет | Не реализуется | Отдельный этап |
| Write mode | sandbox и approvals | Нет | Запрещён | Только отдельное решение пользователя |

## Принятые решения

Один дочерний `codex app-server` на открытую View обслуживает несколько диалогов. История хранится самим Codex: новые thread перестают быть ephemeral. Плагин не создаёт собственной базы чатов. При resume заново задаются и проверяются ограничения чтения; дочерние agent thread не предлагаются в списке.

Model и reasoning берутся из `model/list`. Выбор действует в сессии и передаётся в turn; плагин не переписывает `config.toml`. Настройка клавиши Enter и видимость диагностики относятся только к Eclipse Preferences.

`XtextEditor` установленной версии 2.33 наследует публичный `TextEditor`. Для текста достаточно `ITextEditor.getDocumentProvider().getDocument(input)`, `IDocument` и `isDirty()`. Обращения выполняются на SWT-потоке. Сохранение и чтение всего файла с диска не требуются. Для больших буферов используется ограниченное окно с явными координатами и предупреждением о неполноте. Вычисление diff отложено: сравнение требует дополнительных решений об encoding, изменениях диска и стоимости больших документов.

Markdown разбирается в AST и отображается через `StyledText`/`StyleRange`. HTML остаётся текстом; JavaScript не выполняется, изображения не загружаются. Переход по HTTP(S) — только по нажатию пользователя. Файловые ссылки пока остаются текстом; интерфейс renderer отделён от View. Цвета и шрифты берутся из SWT/JFace.

Штатные read-only sandbox, `approvalPolicy=never`, запрет MCP/actions и проверка effective response сохраняются при создании, продолжении и каждом turn. Возобновление чата не является разрешением менять его исходный проект.

## Результат реализации

Версия 0.3 реализует перечисленные в колонке третьего этапа функции: история и resume, New Thread, динамические модель/reasoning, Stop, composer с EDT Preferences, account/read/logout, dirty-буфер и native Markdown. Browser login и queue/steer оставлены будущему этапу. Данные о сборке, настоящем end-to-end и отдельной ручной приёмке BSL — в [testing.md](testing.md). Общая авторизация, настройки и Skills остаются у Codex.


## Четвёртый этап: 0.4

Baseline перед работой — **ea2b186**, принятая автором 0.3.1. Существующая таблица выше сохраняет историю третьего этапа.

| Функция официального IDE | Реализация 0.4 в EDT | Ограничение |
|---|---|---|
| Header, Settings, account | Компактные native кнопки с SVG-derived DPI иконками и tooltip | Собственная оригинальная иконка, не официальный OpenAI asset |
| Локализация | RU/EN resources; override языка в Eclipse Preferences | Закрыть/открыть View и страницы |
| Общие settings | config/read и versioned config writes | Только defaults model/reasoning и MCP; read-only не переключается |
| MCP | Catalog/status/config CRUD/reload/OAuth через stable RPC | MCP tools в диалогах EDT отключены |
| Account | account/read, rateLimits/read, login/start/cancel, logout | Нет управления подпиской и credentials |
| Skills | skills/list | Просмотр, без переключения |
| Темы | Цвета controls из EDT, JFace fonts, theme-aware изображения | Ручная проверка обеих EDT themes обязательна |
| Markdown | Native StyledText, code/inline emphasis, копирование, ссылки | Без HTML/JS/WebView; изображения не загружаются |
| Файловая навигация | public IDE.openEditor, IFile, проверка real path | Только файл текущего проекта, доступный в workspace |
| Чаты / Stop / context | Сохранены model/reasoning/thread/turn/dirty-buffer | Смена проекта требует явного нового чата |
| Auto-open | Opt-in Eclipse startup после появления UI | По умолчанию штатный restore state |
| Запись, diff, approvals | Не реализованы | Следующий этап требует отдельного решения |

UX исследован по [официальному IDE](https://learn.chatgpt.com/docs/codex/ide), [editor context](https://learn.chatgpt.com/docs/prompting), [developer settings](https://learn.chatgpt.com/docs/developer-settings), [models](https://learn.chatgpt.com/docs/models), [projects/threads](https://learn.chatgpt.com/docs/projects), [permissions](https://learn.chatgpt.com/docs/agent-approvals-security) и связанным материалам в [settings.md](settings.md). OpenVSX использован только как metadata/визуальный референс; код VS Code extension не копировался. Исследование других EDT-интеграций: [edt-ai-references.md](edt-ai-references.md).

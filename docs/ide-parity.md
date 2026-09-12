# Соответствие Codex IDE: этапы 0.3–0.7

## Дополнение 0.7

| Возможность | Реализация EDT | Граница |
| --- | --- | --- |
| Проект без редактора | Thread cwd, editor, Navigator, выбранный/единственный проект, chooser | Смена проекта существующего чата явная |
| Native tools IDE | Локальный MCP Streamable HTTP → публичные EDT services | Не experimental `dynamicTools`; не глобальная регистрация MCP |
| Метаданные | Чтение дерева; справочники, общие модули, реквизиты, типы, табличные части | Остальные классы пока только чтение |
| План и разрешение | Native approval до одной транзакционной BM-задачи | Read-only запрещает запись; strict требует каждый план |
| Сохранение | Публичный editing context, синхронное сохранение и model events | Пользовательский Undo не подключён |
| BSL после создания | Существующие file/command tools Codex, resource refresh, diff | Metadata XML не является основным механизмом мутации |

Это EDT-специфичная интеграция, а не копия закрытых инструментов официального расширения. [Подробное исследование](edt-semantic-tools.md).

Исходное исследование третьего этапа выполнено 11 сентября 2026 года перед реализацией. Протокол проверен по стабильной JSON Schema установленного Codex CLI **0.153.4**, сгенерированной без `--experimental` в игнорируемый `.runtime/app-server-schema-stage3`. Базовая версия плагина — commit `4714953` (0.2).

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

## UI/UX 0.5 — baseline 36e9baf

| UX-подход официального IDE | В Codex for EDT 0.5 | Ограничение |
|---|---|---|
| Компактный sidebar header | Native header, Новый чат/обновить/настройки/аккаунт, tooltip, Tab | Собственные иконки; не копия assets OpenAI |
| Список разговоров | Активная строка, hover, относительное время, усечение, полное имя в tooltip | Серверная история остаётся источником истины |
| Выделенный composer | Скруглённый контейнер, input/footer, placeholder, круглая Send/Stop, model/reasoning | SWT/JFace, без Browser |
| Видимый приложенный контекст | До 5 удаляемых ссылок на файлы проекта; +/меню | Не upload; читается сохранённый файл; вне cwd запрещено |
| Permissions control | Read-only indicator и chip «Подтверждения», disabled auto-approve | Foundation без новых approval RPC |
| Читаемый диалог | Роли, фон пользователя, code blocks, межстрочные интервалы | Native Markdown, сложные таблицы не добавлены |
| Темы | Palette из EDT, одноцветные масштабируемые glyphs | Chrome системных Combo/scrollbars зависит от EDT/Windows |

Исследование официального UX и публичных артефактов Напарника: [ui-ux-research.md](ui-ux-research.md). Не реализованы write mode, file changes, approvals flow, queue/steer или Apply/Reject. Один thread обслуживает несколько turn, включая после Stop; lifecycle baseline сохраняется.

## Актуализация 0.6

| Возможность | Реализация EDT 0.6 | Ограничение |
| --- | --- | --- |
| Permissions | Stable sandbox/approvalPolicy/approvalsReviewer; requirements и профильный каталог | Именованные profile overrides experimental, не используются |
| Agent write | Изменение, создание, удаление через Codex | Только явный выбор режима; sandbox текущего проекта |
| Approvals | Нативные блоки file/command/permissions, typed RPC id | Постоянные execpolicy/network rules не записываются |
| Commands | Item lifecycle, потоковый вывод, exit code | Preview вывода ограничен; выполняет App Server |
| Review | Unified diff и Eclipse Compare контекста hunk | Без собственного Apply/Undo/Revert |
| Dirty buffer | Чтение по-прежнему передаёт buffer; запись требует сохранения | На время turn ввод в редакторы проекта защищён |
| Refresh | Eclipse Job/resource change events | Без собственной семантической модели 1С |

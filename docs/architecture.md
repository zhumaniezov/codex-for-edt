# Архитектура 0.7

## Нативная модель EDT

```text
Codex View → ProjectContextResolver → thread cwd = физический EDT-проект
    → CodexSessionService → codex app-server (stdio)
        → session MCP override → LocalMcpBridge (Jetty, 127.0.0.1)
            → SemanticSession (turn, права, dirty guard, approval)
                → MetadataPlan / EdtMetadataService / EdtTypeService
                    → public EDT factory + BM editing context
                        → save(true) → model events / resources / Problems
```

Сервисы не зависят от SWT-контролов. `SemanticActivityComponent` показывает план и результаты; `SemanticApproval` завершает одно решение. Низкоуровневый транспорт не редактирует BM. HTTP-token и текущий turnKey выполняют разные роли: первый аутентифицирует экземпляр app-server, второй связывает инструмент с реальным текущим turn. Все write-вызовы дополнительно требуют живого project guard. Старые ключи и approvals недействительны после Stop/close/disconnect.

`EdtServices` получает публичные OSGi-сервисы через освобождаемые `ServiceSupplier`. Один план — одна задача локального editing context, `save(true)`, затем освобождение контекста. Это позволяет дождаться native persistence, не генерируя XML и не сохраняя чужие dirty contexts. Транзакция откатывается EDT при исключении; ошибки файловой системы после commit не объявляются атомарным rollback.

`ProjectContextResolver` отделяет проект от редактора: thread cwd → editor → Navigator → выбранный/единственный проект → chooser. Явная смена связанного проекта создаёт новый чат. Существующий `EclipseContextProvider` продолжает собирать dirty buffer и selection, когда редактор присутствует. Пустой startup не обращается к семантической модели.

Временный MCP включается через stable `thread/start.config`/`thread/resume.config`; глобальные MCP не включаются автоматически. Процесс получает случайный bearer через environment, а config хранит только имя переменной. View dispose закрывает собственный HTTP endpoint и app-server; завершение ожидает текущие native операции перед освобождением guard. [Исследование и поддержанные API](edt-semantic-tools.md).

## Сохранённый файловый агент 0.6

В этой версии к принятой архитектуре 0.5 добавлен агентный слой. Исторические разделы ниже описывают этапы появления компонентов; актуальная политика прав определяется этим разделом и [agent-mode.md](agent-mode.md).

```text
Codex View / PermissionSelector / AgentActivityComponent
    → DeferredCodexClient → CodexSessionService
    → CodexAppServerClient (stdio JSONL, requests/responses/notifications)
    → собственный codex app-server → Codex
```

- `PermissionOptions` отображает requirements и доступность встроенных профилей; `AgentPolicy` строит stable sandbox overrides и проверяет effective response. `PermissionMode` содержит документированные варианты UI; backend определяет, какие из них разрешены.
- `AgentApproval` хранит исходный RPC id, thread/turn/item и только запрошенные права. Реестр сессии включает generation соединения. Закрытие, завершение turn, crash и resolved делают старые решения недействительными.
- `AgentActivity` собирает fileChange/commandExecution/diff отдельно от текста assistant; поздние события другого turn игнорируются. Окна stdout обновляются из snapshots через UI dispatcher; чтение процесса не блокирует SWT.
- `ProjectWriteGuard` использует публичные `IWorkbench`, `IWorkbenchPage`, `IEditorPart`, `ResourceUtil`, `ITextOperationTarget/ITextViewer`, SWT Control. Явное сохранение вызывается через `saveEditor`; на время записи контролы редакторов отключаются, затем восстанавливают прежнее enabled-состояние. Новые редакторы отслеживаются через part/window listeners. Невозможность защитить редактор останавливает работу.
- `ProjectRefreshService` выполняет `IProject.refreshLocal(DEPTH_INFINITE)` в Job с правилом проекта после write turn, включая ошибки/interrupt. Создание и удаление обновляют родительские ресурсы; standard resource events остаются за Eclipse/EDT.
- `DiffReviewService` использует публичные `PatchParser`, `IHunk`, `DiffNode`, `CompareEditorInput` и `CompareUI`. Берёт исходный/изменённый контекст hunk из серверного diff. Оба края Compare read-only. `IFilePatch2.apply` не вызывается. Отдельное native окно хранит полный полученный unified diff.

Один thread содержит несколько turn. При смене режима серверный thread отписывается и возобновляется с тем же id и новой политикой перед следующим запросом; процесс View не перезапускается. Управление настройками остаётся отдельным процессом без threads, чтобы MCP reload не менял инструменты активного агента.

Если View закрывается во время записи, закрывается её процесс, после termination восстанавливается доступ к редакторам. Глобальные процессы Codex не затрагиваются.

Необратимая ошибка turn, потеря аккаунта во время работы и неопределённый результат
turn/start/interrupt завершают соединение и собственный процесс. UI не освобождает
защиту редакторов, пока процесс ещё может записывать. После явного сохранения
повторно проверяется, что активный проект не сменился. Approval дополнительно
сверяется с уже зарегистрированным file/command item; кнопка review в нём фильтрует
строго этот thread/turn/item. Add/delete Compare строит пустую сторону по kind,
update разбирается публичным Eclipse PatchParser.

## История архитектуры до 0.6

# Архитектура версии 0.5

## Слои

| Компонент | Ответственность |
|---|---|
| `HeaderComponent`, `IconButton` | Раскладка header, геометрические иконки, круглое действие, focus/keyboard/accessibility |
| `ThemePalette`, `PaletteModel` | Цветовые роли из темы, контраст, владение ресурсами и обновление controls |
| `AttachmentSelection`, `AttachmentController`, `AttachmentChipBar` | Валидация read-only ссылок в фоне, лимит/дубликаты, удаляемые chips |
| `ComposerState`, `ApprovalPresenter`, `ApprovalPanel` | Состояния представления; подготовка approvals без выдачи разрешений |
| `ConversationDocument` | Явные границы/роли сообщений и оформление поверх Markdown |
| `CodexView` | Соединяет компоненты панели, передаёт действия клиенту, обновляет SWT |
| `ThreadListComponent` | Таблица серверных чатов, время, пагинация, текущий чат |
| `ChatComponent` | Текущая отображаемая история и потоковый ответ |
| `ComposerComponent` | Ввод, Send/Stop, модель и reasoning |
| `StatusComponent`, `AccountComponent` | Короткие статусы, диагностика и меню аккаунта |
| `ResponseRenderer`, `NativeMarkdownRenderer` | Независимый контракт отображения, native StyledText |
| `MarkdownDocument` | CommonMark AST → текст и диапазоны оформления без HTML |
| `ContextProvider`, `EclipseContextProvider` | Публичные Eclipse API редактора, проекта и выделения |
| `EditorContext`, `EditorBuffer`, `ChatRequest` | Неизменяемые снимки данных, без живых редакторов |
| `SessionData` | Модели, reasoning, аккаунт, история, состояние сессии |
| `DeferredCodexClient` | Создание клиента и вызовы методов вне SWT, обработка синхронных ошибок, отмена при закрытии |
| `CodexSessionService` | Handshake, auth, каталог, threads, turn, interrupt, read-only |
| `ReadOnlyPolicy` | Запросы sandbox, проверка effective response, IDE prompt |
| `CodexAppServerClient` | JSONL, correlation id, ответы, уведомления, ошибки |
| `CodexProcessManager` | ProcessBuilder, отдельные STDOUT/STDERR, завершение своих процессов |
| `CodexExecutable`, `CodexPlugin` | Поиск executable, жизненный цикл bundle, Error Log |

Идентификаторы: `io.github.zhumaniezov.codex.edt`, View — `io.github.zhumaniezov.codex.edt.views.Codex`. Структура `bom / targets / bundles / features / repositories / tests` сохранена по официальному примеру 1С. В p2 одна feature, основной bundle и `org.commonmark` 0.30.0. Gson поступает из EDT/Target Platform. Тесты не устанавливаются.

## Потоки и состояние

SWT-поток читает `IDocument` и обновляет controls; в нём нет ProcessBuilder, RPC wait или разбора JSON. Последовательный фоновый executor сессии управляет командами, отдельные потоки читают stdout и stderr. Futures коррелируются по типизированным id. Уведомления передаются executor сессии, затем immutable snapshot — в `Display.asyncExec`.

Состояния: `DISCONNECTED → CONNECTING → READY → WORKING → READY`; Stop проходит через `STOPPING → STOPPED`, из которого разрешён следующий Send. Ошибки дают `ERROR` либо `DISCONNECTED`, отсутствие входа — `AUTH_REQUIRED`. Повреждение JSONL, серверный запрос действия, EOF и зависание процесса отключают соединение. `error.willRetry:true` оставляет turn активным. Dispose закрывает собственный процесс и завершает pending futures.

Markdown разбирается отдельно от SWT; обновления объединяются с задержкой 45 мс, устаревшая версия результата не применяется. Стили — непересекающиеся `StyleRange`; шрифты JFace, ссылки подчёркнуты, цвета берутся из ThemePalette. Ошибка parser переводит конкретный ответ в простой текст. HTML отображается буквально, изображения не загружаются, URI проверяются до открытия внешнего браузера по нажатию пользователя. Файловые ссылки проверяет FileLinkTarget: real path внутри текущего project cwd, существующий IFile; открытие через IDE.openEditor и ITextEditor.selectAndReveal. Активные схемы заблокированы. При прокрутке вверх streaming не должен возвращать пользователя вниз.

## Создание и закрытие View

Конструктор View не создаёт `CodexSessionService` и не обращается к фабрике OSGi. `createPartControl()` создаёт controls в состоянии «Подключение...», устанавливает listener и ставит начало подключения в `Display.asyncExec`. `DeferredCodexClient` вызывает фабрику и методы клиента в собственном фоновом executor. Ошибка фабрики или синхронное исключение `connect()` завершают future с ошибкой, сохраняя обычную View и кнопку «Повторить подключение».

`initialize`, `account/read`, `model/list`, `thread/list` остаются в клиентском слое. Для создания панели не нужны window/page/editor/project. Контекст считывается только при отправке, а отсутствие физического cwd обрабатывается понятной ошибкой запроса. Создание controls не ожидает RPC, поиск executable или процесс.

Общий `ui()` проверяет закрытие View, Display и корневого Composite перед выполнением callback. Dispose самого Composite также закрывает ресурсы. `closeResources()` идемпотентен: сначала запрещает обновления, затем закрывает renderer и отложенный клиент. Незавершённые операции завершаются ошибкой; клиент, созданный уже после закрытия, освобождается ровно один раз. Остановка bundle и создание клиента синхронизированы; `release()` использует сохранённую ссылку на activator. Закрывается только принадлежащий сессии процесс.

## Принадлежность потоковых событий

Авторитетный turn id приходит из ответа `turn/start`. Сессия принимает только совпадающие `threadId` и `turnId` (для turn-уведомлений — вложенный `turn.id`). `turn/started` не может заменить id активного запроса. Части текста собираются по `itemId`; пустые id, дубли завершённых items и их поздние delta не применяются.

Помимо проверки протокола View присваивает локальное поколение каждому отображаемому запросу. Отложенные SWT-обновления и completion callbacks применяются только к своему поколению. Завершение/остановка фиксирует частичный ответ в истории и инвалидирует оставшиеся обновления. Проверки гонок выполняются без привязки к цветам темы.

Этот механизм не исправляет отсутствующий дескриптор в реестре Eclipse: эта ветка возникает до создания класса View. Причины конкретного пользовательского restore-сбоя и границы подтверждения описаны в `testing.md`.

## Диалоги и каталог

Один дочерний процесс обслуживает несколько thread. **Новый чат** отсоединяет текущую подписку (`thread/unsubscribe`) и очищает UI; `thread/start` выполняется при первом Send, когда известен физический cwd. Новые thread имеют `ephemeral:false`. Короткое название из первого пользовательского запроса задаётся `thread/name/set`, чтобы список не начинался с IDE-конверта.

`thread/list` запрашивает 15 последних неархивных интерактивных thread, сортировку `updated_at desc`; `nextCursor` управляет следующей страницей. Название берётся из `name`, затем `preview`, время из `updatedAt`. Дочерние agent thread не включаются.

Resume выполняет `thread/read(includeTurns:false)`, проверку физического cwd и отсутствия parent thread, затем `thread/resume(excludeTurns:true)` с новой read-only политикой. Проверяются фактические cwd, модель, sandbox и approvalPolicy. История поступает через `thread/turns/list(itemsView:full, sortDirection:desc)`, по 10 turn; при отображении порядок разворачивается. Плагин показывает user/assistant text и умеет подгружать предыдущие turn. Источник истины — Codex, не локальная база плагина.

Возобновлённый чат сохраняет свой cwd. Если активный редактор относится к другому проекту, Send возвращает понятную ошибку: нужно открыть подходящий модуль либо создать новый чат. При закрытом редакторе resume может продолжаться с сохранённым cwd, без IDE-контекста. В новом обычном диалоге переход на другой физический проект создаёт другой thread.

`model/list` читается со всех страниц. Используются `model` для RPC, `displayName` для UI, `isDefault` для первого выбора, иначе первая видимая модель. Reasoning берётся из `supportedReasoningEfforts` и `defaultReasoningEffort`; UI переводит только подписи. При смене модели текущий effort сохраняется, если допустим, иначе выбирается её default (или первый доступный при некорректном default). В `turn/start` поле называется **`effort`**. При resume используется текущий выбор composer.

## Контекст несохранённого редактора

Публичные API: `IWorkbenchPage.getActiveEditor()`, `MultiPageEditorPart.getSelectedPage()`, `ITextEditor`, `IEditorInput`, `ResourceUtil.getFile()`, `IProject.getLocationURI()`, `ITextSelection`, `IEditorPart.isDirty()`, `IDocumentProvider.getDocument(input)`, `IDocument.get(offset,length)`.

XtextEditor 2.33 наследует TextEditor. Для чтения текста не нужны внутренние EDT API и семантическая модель Xtext. Нет автоматического `doSave`, записи файлов и чтения всего сохранённого файла. Снимок снимается в момент Send, даже если фокус уже в Codex View.

Сохранённый BSL: путь и выделение; основу Codex читает из cwd. Dirty BSL: состояние, содержимое текущего IDocument и инструкция считать его приоритетным относительно диска. Пустой dirty-документ тоже передаётся как актуальное пустое содержимое. Выделение внутри буфера обозначается отдельным блоком с точными координатами UTF-16, без повторения текста.

Буфер до 48 000 символов UTF-16 передаётся целиком. Для больших документов берётся ограниченное окно вокруг начала selection/caret; указаны полный размер, начальная строка, диапазон и неполнота. Граница не разрезает surrogate pair. Выделение вне окна отдельно ограничено 16 000 символами с предупреждением. Diff от сохранённой основы пока не вычисляется. Лимит composer — 32 768 символов, ответ/отображаемая история — около 1 МиБ символов с сообщением о сокращении старых сообщений.

`ContextProvider` допускает последующее добавление metadata object, формы, процедуры и диагностики без переноса Eclipse-объектов в protocol layer. Семантическая EDT-интеграция, Compare Editor и нетекстовые страницы форм пока не реализованы.

## Авторизация, настройки и ограничения

`account/read` — единственный источник информации об аккаунте. В UI используются доступные `email` и `planType`; токены не читаются и не хранятся. `account/logout` вызывается только по действию пользователя, с предупреждением об общей авторизации Codex. Browser login через `account/login/start` реализован на отдельной странице настроек; также доступен `codex login`. После изменения входа переподключите View.

Общие Codex settings остаются у app-server. Composer передаёт выбранные модель/effort и обязательную политику сессии. Отдельное явное сохранение в Preferences использует config API; TOML вручную не редактируется. `EdtPreferencesService` хранит язык, Enter, auto-open, контекст и диагностику в `InstanceScope` рабочей области Eclipse.

Сохраняются `sandbox:read-only`, `approvalPolicy:never`, `approvalsReviewer:user` и `sandboxPolicy:{type:readOnly,networkAccess:false}`. MCP, hooks, плагины, приложения, browser/computer tools, notifications-команды и субагенты отключены для дочернего процесса/thread. Skills остаются доступными в рамках read-only sandbox. Неожиданные серверные запросы действий отклоняются; Активный flow UI approvals отсутствует; chip сообщает действующую политику без изменения backend.

JSONL и prompt не журналируются. STDERR и сообщения ошибок проходят редактирование типовых секретов, stack trace — в Error Log. Процесс закрывается по собственному Process/ProcessHandle и известным потомкам; чужие процессы по имени не завершаются. Предел RPC — 45 секунд, turn — пять минут. Сетевые/серверные ограничения показываются пользователю, а не обходятся.


## Настройки, локализация и ресурсы 0.4

`CodexSettingsService`, `McpService`, `AccountService`, `SkillsService` используют закрытый перечень stable RPC `ManagementRequest`. `EdtPreferencesService` хранит только настройки EDT в InstanceScope; `LocalizationService` выбирает UTF-8 ResourceBundle RU/EN по preference и Platform.getNL(). UI и бизнес-логика не читают TOML/credentials.

`SettingsPage` выполняет операции асинхронно, проверяет dispose и отображает ошибки. `SettingsAccess` создаёт один отдельный клиент на окно Preferences и закрывает его с окном. Все дочерние страницы этого окна разделяют этот клиент; активная View владеет своим процессом. Настройки не создают threads. Это существенная граница: MCP reload в Codex 0.153.4 обновляет loaded threads, а resume loaded thread может игнорировать config overrides. Поэтому management MCP запрещён на клиенте, который уже обслуживал threads. Настройки меняют общий backend, но reload их процесса не затрагивает read-only диалог.

`CodexSettingsService` разделяет effective config и user layer. Запись использует `expectedVersion`; MCP редактируется только из user layer, конфликт имени при добавлении отклоняется. JSON null сохраняется сериализатором для штатного удаления ключа. Модель/reasoning записываются атомарно, произвольный редактор config отсутствует. View продолжает использовать выбранную модель до переподключения; смена аккаунта в Settings требует переподключить View.

`ThemeService` слушает SWT.Settings и IThemeManager, заимствует системные/родительские цвета и JFace fonts. `IconResources` использует ImageDescriptor с DPI-вариантами и LocalResourceManager на время жизни control; освобождение не затрагивает системные ресурсы. Исходные SVG оригинальны, PNG 16/24/32/48/64 экспортированы отдельно для двух тем. HTML/JS/WebView не используются.

`AutoOpenCodex` opt-in по умолчанию выключен. Он асинхронно открывает View только при готовой page и отсутствии persisted View; стандартное восстановление Eclipse остаётся основным механизмом.

## Представление и вложения 0.5

`ThemePalette` получает исходные background/foreground у родительской поверхности Eclipse, а светлую/тёмную опору — из системных SWT colors. `PaletteModel` вычисляет panel/input/footer/border/muted/hover/selected/accent. При недостаточном контрасте исходного foreground выбирается читаемый; тесты проверяют контраст текста на типичных светлой/тёмной поверхностях. Собственные Color принадлежат View; после смены темы controls и Markdown получают новые цвета, старые освобождаются. Системные Color/Font не освобождаются. Native Windows chrome у Combo/scrollbars остаётся ответственностью EDT/ОС.

`IconButton` рисует простую геометрию в логических координатах SWT: header 28, круглое действие 36. Клавиатура, мышь и accessible action вызывают одно SWT.Selection; disabled не активируется, Tab проходит штатно. Форма не требует нового PNG и масштабируется SWT. Иконка View и прежние ImageDescriptor/DPI assets сохранены. `ComposerState` отделяет доступность действий от раскладки. Settings получают более равномерные поля/интервалы без изменения сервисов.

`ConversationDocument` собирает роли из SessionData.Message. Markdown, который модель написала похожим на подпись пользователя, не создаёт новое сообщение. Сообщения пользователя оформляются мягким фоном; код — отдельным фоном и моноширинным шрифтом. StyledText сохраняет выделение, безопасные links и контекстное копирование. Расчёт автопрокрутки учитывает фактические позиции строк и переносы; короткое приветствие не прокручивается вниз.

`AttachmentController` читает IDE context в UI, а real path/stat выполняет собственным executor. `AttachmentSelection` хранит до пяти проверенных ссылок, не содержимое файлов. Проверка повторяется перед отправкой. Epoch отбрасывает позднее добавление после Send, нового чата или resume. `ChatRequest` добавляет неизменяемый список относительных путей; ReadOnlyPolicy помещает их в отдельный блок text input, экранируя каждый путь JSON-строкой. Новых RPC/DTO протокола нет. UI не читает прикреплённые файлы, не сохраняет редактор и не меняет sandbox. Другие dirty buffers не загружаются; исторические chips не восстанавливаются отдельным списком из thread history.

ApprovalStatusComponent показывает фактический запрет расширения разрешений. ApprovalPresenter не разрешает approve/auto-approve/reject; ApprovalPanel существует как неактивный компонент будущей интеграции. Никакого обработчика принятия разрешений, переключения sandbox или симуляции серверного запроса не добавлено.

# Архитектура

## Компоненты

```text
1C:EDT / Eclipse workbench
  └─ CodexView (org.eclipse.ui.views → SWT / JFace)
       ├─ EclipseContextProvider → EditorContext
       └─ CodexClient.send(ChatRequest)
            └─ MockCodexClient → CompletionStage<String>
```

В будущем другую реализацию `CodexClient` можно подключить к codex app-server. Транспорт, процессы, сессии, авторизация и OpenAI API в текущем проекте отсутствуют.

| Каталог | Назначение |
| --- | --- |
| `bom` | Версии сборки, execution environment, runtime extenders Eclipse |
| `targets/default` | Целевые библиотеки EDT 2026.1 и Eclipse 2023-12 |
| `bundles/com.admglobal.codex.edt` | Единственный поставляемый OSGi bundle |
| `features/com.admglobal.codex.edt.feature` | Устанавливаемый компонент |
| `repositories/com.admglobal.codex.edt.repository` | Категория ADM Global и p2 repository |
| `tests/com.admglobal.codex.edt.tests` | UI-тест, отдельный test bundle; в поставку не включён |
| `scripts` | Сборка, локальная проверка, отдельный запуск EDT |
| `.runtime` | Логи, временные результаты и тестовые рабочие области; вне Git |
| `.m2` | Локальный кэш зависимостей; вне Git |

## View

Идентификатор: `com.admglobal.codex.edt.views.Codex`. Регистрация использует публичную точку расширения [org.eclipse.ui.views](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/extension-points/org_eclipse_ui_views.html). Класс наследуется от `ViewPart`; компоновка выполнена `GridLayout`. Плагин не изменяет перспективу EDT автоматически. Пользователь сам открывает и закрепляет панель.

Пустой запрос не отправляется. Во время обработки Send отключается. Ответ отображается в многострочном read-only поле. Виджеты используют системную тему и шрифт JFace; собственные SWT Font/Color не выделяются.

## Контекст

Снимок создаётся **на UI-потоке в момент Send**. Источник — `IWorkbenchPage.getActiveEditor()`, который продолжает указывать на редактор при переходе в View. Текст ввода чата не становится выделением модуля.

Алгоритм:

1. Получить активный редактор.
2. Для `MultiPageEditorPart` использовать выбранную страницу, если она является редактором. На странице формы без редактора вернуть пустой контекст.
3. Использовать `ITextEditor` напрямую либо через публичный адаптер.
4. Через `ResourceUtil.getFile(editorInput)` получить `IFile` и его `IProject`.
5. Для текстового файла с расширением `.bsl` получить текущий `ITextSelection`.

Читается выделение из документа редактора, включая несохранённые изменения. Полный модуль и файлы проекта не читаются. При отсутствии редактора/адаптера/ресурса возвращаются пустые значения, которые UI объясняет как неопределённый контекст.

Ограничения:

- Проект — workspace-контейнер файла. Плагин не определяет тип конфигурации и не проверяет `IV8Project`.
- Файл `.bsl` определяется по расширению, а не по БМ/AST. Для внешнего файла без `IFile` возможно только имя и выделение.
- Compare editor, бинарные модули и некоторые встроенные страницы могут не предоставлять нужные адаптеры.
- Контекст показывается в ответе при отправке, а не обновляется постоянно.
- Автоматический тест открывает `.bsl` стандартным текстовым редактором. Специфические редакторы EDT требуют отдельной ручной проверки.

Публичные API сверены с [официальным примером 1С](https://github.com/1C-Company/dt-example-plugins/blob/921c30ca006d05895d4d599091ea3e55a8afea69/bundles/org.example.ui/src/org/example/ui/DataProcessingHandler.java) и документацией [ITextEditor](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/ui/texteditor/ITextEditor.html), [ResourceUtil](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/ui/ide/ResourceUtil.html). Импортов private/internal API EDT в нашем Java-коде нет.

## Клиентский слой

`ChatRequest` и `EditorContext` — неизменяемые Java records. В них нет SWT-контролов, Eclipse-проектов или живых документов. Реализация клиента может завершить `CompletionStage` на любом потоке; View возвращает обновление интерфейса на Display через `asyncExec`.

Текущий `MockCodexClient` немедленно возвращает локальный ответ. При закрытии View вызывается `close()`, отложенный ответ не обращается к уничтоженным контролам. История, streaming, отмена и восстановление сессий будут отдельными изменениями интерфейса клиента при проектировании реального транспорта.

Для следующего этапа изучена [официальная документация Codex App Server](https://learn.chatgpt.com/docs/app-server). Предполагается отдельная реализация клиента, отвечающая за процесс сервера, протокол, диалоги, потоковые события и запросы разрешений. Это проектное решение, а не уже реализованный транспорт. Версию протокола и схему сообщений нужно зафиксировать перед его реализацией.

## Среда и зависимости

Используется JavaSE-17, а не Java 25 из master официального примера. Runtime extenders Felix SCR и Aries SPI Fly включены в настройку Tycho: обычных Java-импортов недостаточно для разрешения Eclipse OSGi classpath. Поставляемая feature включает только наш bundle; библиотеки EDT/Eclipse берутся из среды пользователя.

Проект не содержит BSL-бизнес-логики. API платформы 1С:Предприятие и БСП для первого этапа не нужны. Подключение к информационным базам отсутствует.

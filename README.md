# Codex for 1C:EDT

Личный проект: нативный Java-плагин с боковой панелью Codex для 1C:Enterprise Development Tools.

## Цель

Создание нативного плагина для 1C:Enterprise Development Tools, который позволит работать с OpenAI Codex непосредственно внутри EDT через отдельную боковую панель агента.

## Текущий статус

**Версия 0.3.1 — принятая вручную версия с усилением lifecycle и изоляции ответов, только чтение.** Настоящий `codex app-server` использует существующий вход пользователя и передаёт потоковый ответ в native SWT/JFace-панель. Добавлены чаты, выбор модели и reasoning, Stop, Markdown и актуальный несохранённый BSL-буфер.

Версия 0.3 (commit `3e6365b`) принята автором в настоящем BSL-редакторе: dirty buffer, чаты, модели/reasoning, Stop, обе темы, Account и Preferences. Версия 0.3.1 также принята автором вручную. Для 0.3.1 добавлены отложенное создание клиента, защита dispose и проверки поздних событий после Stop. **Первопричина сообщённого Error Part при restore пока не подтверждена**: исходная v0.3 также проходит автоматические повторные запуски. Диагностика, результаты и TC-18…TC-22 — в [docs/testing.md](docs/testing.md).

Проверенная среда: Windows x86_64, EDT **2026.1.3.25**, Java **17**, Maven **3.9.16**, Tycho **4.0.5**, Codex CLI **0.153.4**. Для другой версии Codex следует заново проверить schema и тесты.

## Возможности текущей версии

- Перемещаемая панель **Codex** рядом с редактором.
- Последние чаты из `thread/list`, название и время; **Просмотреть ещё** загружает следующую страницу.
- **Новый чат**, открытие старого чата и продолжение того же thread. Историю хранит Codex; собственной базы чатов нет.
- Динамические списки моделей с `displayName` и поддерживаемых уровней reasoning.
- Composer: **↑** отправляет, **■** останавливает активный turn. **Enter** отправляет, **Shift+Enter** добавляет строку.
- Потоковый ответ с оформлением Markdown: абзацы, выделение, код, списки, HTTP(S)-ссылки. Без WebView, HTML/JS и загрузки изображений.
- Текущий проект, физический cwd, модуль, выделение и dirty state через публичные Eclipse API.
- Несохранённый BSL-буфер имеет приоритет над сохранённым файлом; Ctrl+S не требуется. Плагин не сохраняет документ.
- Короткий статус, отдельная диагностика, информация об аккаунте и выход через app-server.
- Переподключение, закрытие собственного процесса вместе с View, установочный p2-репозиторий.

**Только чтение:** thread/resume/turn получают read-only, сеть инструментов запрещена, запросы повышения разрешений не исполняются. MCP и дополнительные средства действий отключены для этой дочерней сессии. Пользовательские `config.toml`, MCP и Skills не изменяются. Codex продолжает самостоятельно управлять своей авторизацией и служебными данными.

Сохранённый модуль целиком не отправляется: Codex может читать его через cwd. Dirty-буфер передаётся целиком до **48 000 символов UTF-16**; для большего модуля — явно обозначенное окно вокруг курсора. Выделение вне окна ограничено 16 000 символами с предупреждением. Код, уже находящийся в буфере, обозначается диапазоном выделения без дублирования.

## План развития

После ручной приёмки этой версии можно отдельно согласовать семантический контекст EDT, диагностику, улучшение больших буферов, файловые ссылки и очередь/steering. Write mode, diff, Apply / Reject и approvals требуют отдельного решения. Автоматического перехода к ним нет.

## Сборка

Нужны Full JDK 17 и Maven 3.9.x от 3.9.4. Первый запуск загружает зависимости с официальных репозиториев 1С, Eclipse и Maven Central.

```powershell
Set-Location C:\projects\codex-edt
.\scripts\build.ps1
```

Скрипт находит распакованный Maven в `.tools/apache-maven-*` или `apache-maven-*-bin/apache-maven-*`, затем в PATH. JDK по умолчанию — `C:\Program Files\Axiom\AxiomJDK-Pro-17-Full`; другие пути задаются `-JavaHome` и `-MavenHome`. Глобальное ПО не устанавливается, PATH не меняется.

Сборка выполняет `clean verify`, unit/UI tests и p2 verification. Обычные тесты используют тестовый дочерний сервер и не обращаются к OpenAI. Подробности: [docs/build.md](docs/build.md).

## Тестирование

Закройте прежний development EDT, затем выполните:

```powershell
.\scripts\start-dev-edt.ps1
```

В отдельном экземпляре EDT:

1. Откройте тестовый проект и BSL-модуль.
2. **Окно → Показать панель → Другая… → Codex → Codex** (английское меню: **Window → Show View → Other…**).
3. Перетащите панель вправо от редактора. Дождитесь статуса **Подключён**.
4. Проверьте модель и reasoning внизу, рядом с **↑**. Напишите вопрос; ответ должен появляться постепенно.
5. **■** останавливает запрос. **Новый чат** очищает диалог и composer. Выбор строки в **Чаты** открывает серверную историю; **Предыдущие сообщения** загружает более старые turn.
6. Обязательно проверьте несохранённый модуль со звёздочкой, сначала **без выделения**: «Какой код сейчас находится в открытом модуле?».
7. Затем выделите `Сообщить("Привет");` и спросите: «Что делает выделенный код и в каком контексте он находится?».

Через **⋯** или **Window → Preferences → Codex for 1C:EDT** меняются только настройки EDT: поведение Enter и видимость диагностики. При выключенном Enter-to-send Enter добавляет строку; отправка — кнопкой **↑**. Версия, raw model id, cwd и thread id также доступны в tooltip статуса.

Если executable не найден, используйте `-CodexExecutable 'полный путь к codex.exe'`. Если нужен вход — выполните штатный `codex login` в PowerShell, завершите вход в браузере и нажмите **Повторить подключение**. Не вводите токены в EDT. Кнопка **Аккаунт** показывает данные app-server; выход относится к общей авторизации Codex, поэтому UI запрашивает подтверждение.

Автоматические проверки в полном EDT:

```powershell
.\scripts\start-dev-edt.ps1 -Smoke
# Настоящие запросы, использующие вход и лимиты пользователя:
.\scripts\start-dev-edt.ps1 -Smoke -Live
```

Сценарии создают только временные проекты в отдельной workspace, проверяют streaming, контекст, SHA-256 файлов и завершение процесса. Live включает пустой сохранённый файл с непустым dirty-буфером. Стандартный Eclipse Text Editor в этих сценариях не подменяет ручную приёмку BSL-редактора: [TC-01 — TC-17](docs/testing.md).

## Установка

После BUILD SUCCESS установочный ZIP:

```text
repositories/io.github.zhumaniezov.codex.edt.repository/target/io.github.zhumaniezov.codex.edt.repository-0.3.1-SNAPSHOT.zip
```

В обычной EDT: **Help → Install New Software… → Add… → Archive…**, выбрать ZIP и **Codex → Codex for 1C:EDT**. Завершить мастер и перезапустить EDT. При обновлении с 0.2 идентификатор bundle сохраняется. Основная EDT автоматически не изменяется.

В p2 входят плагин и небольшой CommonMark bundle; тесты и Codex executable не входят. ZIP не включается в Git. Предварительные артефакты не подписаны.

## Архитектура

```text
1C:EDT
↓
Codex View (native SWT/JFace)
↓
EDT Plugin: ContextProvider + UI components
↓
Codex Client Layer: CodexSessionService
↓
CodexAppServerClient → CodexProcessManager (stdio / JSONL)
↓
codex app-server
↓
OpenAI Codex
```

Процесс, протокол, состояние сессии, контекст и renderer разделены. Подробнее: [docs/architecture.md](docs/architecture.md), [docs/ide-parity.md](docs/ide-parity.md).

## Использованные официальные материалы

- [Разработка плагинов 1C:EDT](https://edt.1c.ru/dev/ru/) и [1C-Company/dt-example-plugins](https://github.com/1C-Company/dt-example-plugins).
- [Eclipse ITextEditor](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/ui/texteditor/ITextEditor.html).
- [OpenAI Codex IDE](https://learn.chatgpt.com/docs/codex/ide), [настройки IDE](https://learn.chatgpt.com/docs/developer-settings?surface=ide), [модели](https://learn.chatgpt.com/docs/models).
- [Codex App Server](https://developers.openai.com/codex/app-server/) и [официальные исходники rust-v0.153.4](https://github.com/openai/codex/tree/rust-v0.153.4).
- [CommonMark Java](https://github.com/commonmark/commonmark-java/tree/commonmark-parent-0.30.0) — parser с лицензией BSD-2-Clause.

Исследования: [EDT](docs/research.md), [App Server](docs/app-server-research.md), [соответствие IDE](docs/ide-parity.md). Код официального расширения VS Code не используется.

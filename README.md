# Codex for 1C:EDT

Личный проект с открытыми исходниками: нативный Java-плагин с боковой панелью Codex для 1C:Enterprise Development Tools. Это сторонний клиент, не официальный продукт OpenAI или фирмы 1С.

## Цель

Создание нативного плагина для 1C:Enterprise Development Tools, который позволит работать с OpenAI Codex непосредственно внутри EDT через отдельную боковую панель агента.

## Текущий статус

**Версия 0.5 — обновлённый native UI/UX; агент работает только в режиме чтения.** Настоящий `codex app-server` использует авторизацию Codex пользователя и передаёт потоковые ответы в SWT/JFace. Поддерживаются серверные чаты, модели/reasoning, остановка, Markdown и несохранённый BSL-буфер.

Baseline этого этапа — **`36e9baf`**, принятая вручную v0.4. Lifecycle hardening и изоляция поздних событий сохранены. **Точная первопричина старого Error Part не доказана**; документация не утверждает обратного. Новый UX v0.5 предназначен для отдельной ручной приёмки: [результаты и сценарии](docs/testing.md).

Проверяемая среда: Windows x86_64, EDT **2026.1.3.25**, JDK **17**, Maven **3.9.16**, Tycho **4.0.5**, Codex CLI **0.153.4**. Для другой версии CLI следует заново сверить schema.

## Возможности текущей версии

- Dockable-панель **Codex** с оригинальной иконкой и компактным header; история с активной строкой, относительным временем и tooltip полного названия.
- Скруглённый composer с отдельной поверхностью ввода, нижней строкой действий и **круглой кнопкой отправки**, которая меняется на Stop.
- Видимые удаляемые ссылки-вложения: текущий BSL-файл или выбранный файл текущего проекта, максимум пять. Это дополнительный read-only контекст, не загрузка файлов.
- **Новый чат**, список и продолжение серверных threads. Каждый следующий запрос — turn того же чата; при смене проекта нужен явный новый чат.
- Модели с `displayName` и допустимые уровни рассуждения из `model/list`.
- Потоковые ответы, **Отправить / Остановить**, Enter для отправки и Shift+Enter для новой строки.
- Публичный Eclipse-контекст: проект, физический cwd, BSL-модуль, выделение и актуальный dirty buffer без Ctrl+S.
- Native Markdown с подписями ролей, фоном сообщения пользователя и code blocks: абзацы, выделение, списки, inline code; копирование выделения, кода у курсора и последнего ответа через контекстное меню.
- Файловые ссылки внутри текущего проекта открываются в EDT; HTTP(S) — штатным внешним браузером. HTML/JS не выполняются, WebView отсутствует.
- RU/EN, автоматический язык EDT, ресурсы для светлой/тёмной темы и нескольких DPI.
- Страницы настроек: общие, **Codex**, **Серверы MCP**, **Учётная запись**, **Навыки**, **Дополнительно**.
- Чтение Codex config, явное сохранение defaults модели/reasoning, настройка простых MCP STDIO/HTTP-серверов через официальный API.
- Аккаунт и доступные лимиты, браузерный вход ChatGPT, отмена входа и выход; список Skills без изменения.
- Переподключение, отложенный безопасный startup, закрытие собственных процессов и p2-установка.

**Граница read-only:** thread/resume/turn получают read-only и запрет повышения разрешений; MCP tools и дополнительные средства действий отключены для агентского диалога. Настройки используют отдельный временный процесс без threads: его MCP reload не обновляет агентский процесс. Общие model/MCP settings изменяются только явным действием с подтверждением; это влияет на общую среду Codex. Плагин не читает credentials и не редактирует TOML вручную. Skills не изменяются. Подробности: [настройки](docs/settings.md).

Dirty buffer до **48 000 символов UTF-16** передаётся целиком; больший модуль — явно обозначенным окном вокруг курсора. Выделение вне окна ограничено 16 000 символами с предупреждением. Сохранённый файл целиком в prompt не добавляется, редактор не сохраняется автоматически.

## Новый интерфейс 0.5

Header, список чатов и диалог отделены от светлеющей/темнеющей поверхности composer. Статус находится в строке «Диалог»; подробности доступны через tooltip/Preferences. Модель и рассуждение выбираются прежним способом. Круглая кнопка поддерживает мышь, Enter/Space и Tab; Enter/Shift+Enter в поле ввода сохраняют настройки.

**Добавление файла:** нажмите **+ → Прикрепить ссылку на текущий файл** или **Выбрать файл проекта…**. До отправки chip можно убрать крестиком. Перед turn пути повторно проверяются в фоне: только существующие файлы внутри физического cwd текущего проекта, включая проверку переходов и ссылок через `toRealPath`. Агент получает относительные пути и может читать сохранённые файлы. Содержимое файлов не копируется в prompt. Актуальный dirty buffer активного BSL-редактора передаётся прежним механизмом и имеет приоритет; другие несохранённые редакторы вложением не передаются. После успешного ответа, нового чата, resume или переподключения вложения очищаются.

**Подтверждения:** chip открывает сведения о read-only policy. Автоподтверждение недоступно, backend-политика не меняется. `ApprovalPresenter`/`ApprovalPanel` — основа для отдельного будущего этапа, без активного approval flow. Файлы вне текущего проекта и uploads не поддерживаются. Вложения не разрешают запись.

[Исследование и решение SWT/JFace](docs/ui-ux-research.md). Новый UI требует отдельной ручной проверки тем, DPI и удобства узкой панели; результат прежней приёмки v0.4 не подменяет её.

## План развития

После ручной проверки этой версии можно отдельно согласовать семантический контекст EDT, диагностику и улучшение больших буферов. Write mode, diff, Apply / Reject, работающий flow approvals и очередь/steering в эту версию не входят. Chip «Подтверждения» показывает действующую политику; автоподтверждение явно недоступно. Автоматического перехода к следующему этапу нет.

## Сборка

Нужны Full JDK 17 и Maven 3.9.x (от 3.9.4):

```powershell
Set-Location C:\projects\codex-edt
.\scripts\build.ps1
```

Скрипт находит распакованный Maven в проекте или PATH; для своих путей используйте `-JavaHome` и `-MavenHome`. Он запускает `clean verify`, unit/PDE UI tests и p2 verification. Обычные тесты используют тестовый App Server; реальные вход, config и MCP пользователя не меняются. Подробнее: [сборка](docs/build.md).

## Тестирование

Закройте прежний development EDT и выполните:

```powershell
.\scripts\start-dev-edt.ps1
```

1. В отдельной EDT откройте тестовый проект и BSL-модуль.
2. **Окно → Показать панель → Другая… → Codex → Codex** (Window → Show View → Other…). Перетащите панель вправо.
3. Дождитесь подключения; задайте вопрос. Стрелка **Отправить** во время turn сменяется квадратом **Остановить**.
4. Проверьте dirty-модуль со звёздочкой без выделения: «Какой код сейчас находится в открытом модуле?».
5. Откройте настройки кнопкой с ползунками или **Window → Preferences → Codex for 1C:EDT**. Язык меняется после закрытия/открытия View и окна настроек. Названия страниц в дереве Preferences следуют локали Eclipse.
6. Проверьте три запроса в одном чате, Stop, Resume, RU/EN, темы, Markdown и восстановление после перезапуска: [TC-01…TC-50](docs/testing.md).

Если executable не найден, передайте `-CodexExecutable 'полный путь к codex.exe'`. Вход доступен на странице **Учётная запись** либо штатной командой `codex login`. После изменения аккаунта в настройках переподключите View. Токены вводить в EDT не требуется.

Автоматические проверки полной EDT:

```powershell
.\scripts\start-dev-edt.ps1 -Smoke
# Настоящие запросы расходуют лимиты Codex пользователя:
.\scripts\start-dev-edt.ps1 -Smoke -Live
```

Тесты используют отдельные workspace и проверяют streaming, контекст, SHA-256 и закрытие процессов. Программная проверка публичного Text Editor API дополняет ручную приёмку настоящего BSL-редактора.

## Установка

После BUILD SUCCESS используйте:

```text
repositories/io.github.zhumaniezov.codex.edt.repository/target/io.github.zhumaniezov.codex.edt.repository-0.5.0-SNAPSHOT.zip
```

В обычной EDT: **Help → Install New Software… → Add… → Archive…**, выберите ZIP, затем **Codex → Codex for 1C:EDT**, завершите мастер и перезапустите EDT. Идентификатор bundle сохранён для обновления с 0.3.x. Основная установка EDT автоматически не изменяется.

В p2 входят основной плагин и CommonMark bundle. Тесты, Codex executable и секреты не включены. ZIP не хранится в Git; артефакты пока не подписаны.

## Архитектура

```text
1C:EDT → Codex View (SWT/JFace) → EDT Plugin
      → Codex Client Layer → codex app-server (stdio / JSONL) → OpenAI Codex

Eclipse Preferences → Settings services → отдельный app-server без threads
```

UI, протокол, процессы, контекст, настройки, локализация и renderer разделены: [архитектура](docs/architecture.md), [соответствие IDE](docs/ide-parity.md).

## Использованные официальные материалы

- [Разработка плагинов EDT](https://edt.1c.ru/dev/ru/), [1C-Company/dt-example-plugins](https://github.com/1C-Company/dt-example-plugins), [1С:Напарник](https://code.1c.ai/easystart/).
- [Codex IDE](https://learn.chatgpt.com/docs/codex/ide), [IDE settings](https://learn.chatgpt.com/docs/developer-settings?surface=ide), [модели](https://learn.chatgpt.com/docs/models), [MCP](https://learn.chatgpt.com/docs/extend/mcp), [Skills](https://learn.chatgpt.com/docs/build-skills), [аккаунт](https://learn.chatgpt.com/docs/auth).
- [App Server](https://learn.chatgpt.com/docs/app-server), [официальные исходники rust-v0.153.4](https://github.com/openai/codex/tree/rust-v0.153.4).
- [Eclipse ITextEditor](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/ui/texteditor/ITextEditor.html), [ImageDescriptor](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/jface/resource/ImageDescriptor.html), [IDE.openEditor](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/ui/ide/IDE.html).
- [Официальное расширение в OpenVSX](https://open-vsx.org/extension/openai/chatgpt), [OpenAI brand guidelines](https://openai.com/brand/). Его код и assets не копируются; [оригинальная иконка и лицензия](docs/branding.md).

Дополнительно: [исследование EDT](docs/research.md), [App Server](docs/app-server-research.md), [Напарник и MCP-RSV](docs/edt-ai-references.md), [CommonMark Java](https://github.com/commonmark/commonmark-java/tree/commonmark-parent-0.30.0).

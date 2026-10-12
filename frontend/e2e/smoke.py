"""Real browser smoke checks; requires a running frontend and backend."""
import os
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

BASE = os.environ.get('BASE_URL', 'http://127.0.0.1:5173')
SHOTS = Path(os.environ.get('SCREENSHOT_DIR', 'test-results/screenshots'))
SHOTS.mkdir(parents=True, exist_ok=True)
with sync_playwright() as p:
    executable = os.environ.get('CHROMIUM_PATH')
    browser = p.chromium.launch(**({'executable_path': executable} if executable else {}), args=['--no-sandbox'])
    context = browser.new_context(viewport={'width': 1440, 'height': 1000}, accept_downloads=True)
    page = context.new_page()
    failures = []
    page.on('pageerror', lambda error: failures.append(str(error)))
    page.goto(BASE)
    expect(page.get_by_text('Готов к передаче', exact=True)).to_be_visible()
    expect(page.get_by_role('button', name='Создать ссылку')).to_be_disabled()
    page.wait_for_timeout(600)
    page.screenshot(path=str(SHOTS / 'desktop.png'), full_page=True)
    for width in [320, 375, 390, 768, 1024, 1440]:
        page.set_viewport_size({'width': width, 'height': 844})
        assert page.evaluate('document.documentElement.scrollWidth <= innerWidth'), f'Overflow at {width}'
    page.set_viewport_size({'width': 390, 'height': 844})
    page.screenshot(path=str(SHOTS / 'mobile.png'), full_page=True)
    page.get_by_role('button', name='Как это работает').click()
    expect(page.get_by_role('dialog')).to_be_visible()
    page.keyboard.press('Escape')
    expect(page.get_by_role('dialog')).not_to_be_visible()
    page.get_by_role('button', name='Текст', exact=True).click()
    message = 'Тест: Привет 👋 <script>window.unwanted=true</script>'
    page.get_by_label('Текст передачи', exact=True).fill(message)
    page.get_by_role('switch', name='Защитить паролем').click()
    page.get_by_label('Пароль передачи', exact=True).fill('Бета-пароль!')
    page.get_by_role('button', name='Создать ссылку').click()
    expect(page.get_by_role('heading', name='Можно передавать.')).to_be_visible()
    read = page.get_by_label('Ссылка для получателя', exact=True).input_value()
    page.get_by_text('Отзыв и управление', exact=True).click()
    manage = page.get_by_label('Ссылка управления', exact=True).input_value()
    recipient = context.new_page()
    recipient.goto(read)
    expect(recipient.get_by_role('heading', name='Защищённая передача')).to_be_visible()
    recipient.get_by_label('Пароль от отправителя', exact=True).fill('wrong')
    recipient.get_by_role('button', name='Показать текст').click()
    expect(recipient.get_by_role('alert')).to_contain_text('Пароль не подошёл')
    recipient.get_by_label('Пароль от отправителя', exact=True).fill('Бета-пароль!')
    recipient.get_by_role('button', name='Показать текст').click()
    expect(recipient.get_by_label('Полученный текст')).to_have_text(message)
    assert recipient.evaluate('window.unwanted === undefined')
    recipient.reload()
    expect(recipient.get_by_role('heading', name='Уже получено.')).to_be_visible()
    page.goto(manage)
    expect(page.get_by_text('1 из 1', exact=True)).to_be_visible()
    page.goto(BASE)
    payload = b'\x00\xffpollyDrop beta\r\n'
    page.get_by_label('Выберите файл', exact=True).set_input_files({'name': 'проверка.bin', 'mimeType': 'application/octet-stream', 'buffer': payload})
    page.get_by_label('Количество получений', exact=True).select_option('3')
    page.get_by_role('button', name='Создать ссылку').click()
    expect(page.get_by_role('heading', name='Можно передавать.')).to_be_visible()
    file_read = page.get_by_label('Ссылка для получателя', exact=True).input_value()
    page.get_by_text('Отзыв и управление', exact=True).click()
    file_manage = page.get_by_label('Ссылка управления', exact=True).input_value()
    recipient.goto(file_read)
    with recipient.expect_download() as download_info:
        recipient.get_by_role('button', name='Получить файл').click()
    download = download_info.value
    assert download.suggested_filename == 'проверка.bin'
    assert Path(download.path()).read_bytes() == payload
    page.goto(file_manage)
    expect(page.get_by_text('1 из 3', exact=True)).to_be_visible()
    page.get_by_role('button', name='Отозвать передачу', exact=True).click()
    page.get_by_role('button', name='Да, отозвать передачу', exact=True).click()
    expect(page.get_by_text('Отозвана', exact=True)).to_be_visible()
    recipient.goto(file_read)
    expect(recipient.get_by_role('heading', name='Ссылка отозвана.')).to_be_visible()
    recipient.goto(BASE + '/s/' + 'a' * 43)
    expect(recipient.get_by_role('heading', name='Не удалось открыть.')).to_be_visible()
    page.goto(BASE)
    page.get_by_label('Выберите файл', exact=True).set_input_files({'name': 'empty.txt', 'mimeType': 'text/plain', 'buffer': b''})
    expect(page.get_by_role('alert')).to_contain_text('Файл пустой')
    context.close()
    reduced = browser.new_context(viewport={'width': 390, 'height': 844}, reduced_motion='reduce')
    quiet = reduced.new_page(); quiet.goto(BASE)
    assert quiet.locator('.front-sheet').evaluate("e => getComputedStyle(e).animationName") == 'none'
    assert not failures, failures
    browser.close()
print('PASS: responsive 6 widths, dialog/Escape, protected Unicode text, escaped markup, wrong password, one-time exhaustion, binary download, management/revoke, invalid link, empty file, reduced motion, no JS errors')

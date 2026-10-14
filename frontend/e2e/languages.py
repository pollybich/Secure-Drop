"""Country-based defaults and manual language selection with real browser state."""
import os,re
from playwright.sync_api import sync_playwright,expect
base=os.environ.get('BASE_URL','http://127.0.0.1:8088')
with sync_playwright() as p:
    engine=os.environ.get('BROWSER','chromium')
    kwargs={}
    if os.environ.get('CHROMIUM_PATH'):kwargs['executable_path']=os.environ['CHROMIUM_PATH']
    if engine=='chromium':kwargs['args']=['--no-sandbox']
    browser=getattr(p,engine).launch(**kwargs)
    for country,language,browser_language in [('RU','ru','en-GB'),('US','en','ru-RU'),('ZZ','en','ru-RU')]:
        context=browser.new_context(locale=browser_language,viewport={'width':390,'height':844})
        context.route('**/api/locale',lambda route:route.fulfill(json={'country':country,'language':language}))
        page=context.new_page();page.goto(base)
        expect(page.locator('html')).to_have_attribute('lang',language)
        expect(page.get_by_role('button',name='Создать ссылку' if language=='ru' else 'Create link',exact=True)).to_be_visible()
        assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
        if language=='en':assert not re.search('[А-Яа-яЁё]',page.locator('body').inner_text())
        # Switching preserves entered content and the one-hour transfer setting.
        page.get_by_role('button',name='Текст' if language=='ru' else 'Text',exact=True).click()
        textarea=page.locator('#transfer-text');textarea.fill('User content stays unchanged: Привет 👋')
        page.get_by_role('button',name='EN' if language=='ru' else 'RU',exact=True).click()
        selected='en' if language=='ru' else 'ru'
        expect(page.locator('html')).to_have_attribute('lang',selected)
        expect(textarea).to_have_value('User content stays unchanged: Привет 👋')
        expect(page.get_by_label('Delete after' if selected=='en' else 'Удалить через',exact=True)).to_have_value('3600')
        page.reload();expect(page.locator('html')).to_have_attribute('lang',selected)
        context.close()
    # English delivery/management flow, preserving Unicode and literal markup.
    context=browser.new_context(locale='ru-RU')
    context.route('**/api/locale',lambda route:route.fulfill(json={'country':'US','language':'en'}))
    page=context.new_page();page.goto(base)
    page.get_by_role('button',name='Text',exact=True).click();page.get_by_label('Transfer text',exact=True).fill('English flow: Привет <script>window.unwanted=true</script>')
    page.get_by_role('button',name='Create link',exact=True).click()
    expect(page.get_by_role('heading',name='Ready to share.')).to_be_visible()
    read=page.get_by_label('Recipient link',exact=True).input_value()
    page.get_by_text('Revoke and manage',exact=True).click();manage=page.get_by_label('Management link',exact=True).input_value()
    page.goto(read);page.get_by_role('button',name='Show text',exact=True).click()
    expect(page.get_by_label('Received text')).to_contain_text('English flow: Привет <script>')
    assert page.evaluate('window.unwanted === undefined')
    page.goto(manage);expect(page.get_by_text('1 of 1',exact=True)).to_be_visible()
    browser.close()
print('Language checks passed: country overrides browser language, manual preference persists, draft content stays intact, English receive/manage flow works.')

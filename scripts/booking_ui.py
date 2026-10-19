"""Optional Playwright UI checks. Requires demo profile, ADMIN_PASSWORD and BOOKING_URL.
Run against an isolated verification database.
"""
import os,json,pathlib,datetime as dt
from playwright.sync_api import sync_playwright,expect
root=pathlib.Path(__file__).resolve().parents[1];out=root/'docs/screenshots';out.mkdir(parents=True,exist_ok=True)
base=os.environ.get('BOOKING_URL','http://localhost:8080')
import uuid
run_id=uuid.uuid4().hex[:6]
errors=[]
with sync_playwright() as p:
 browser=p.chromium.launch(headless=True,args=['--no-sandbox'])
 context=browser.new_context(viewport={'width':1440,'height':1000},timezone_id='Asia/Shanghai',locale='zh-CN')
 page=context.new_page();page.on('pageerror',lambda e:errors.append(str(e)));page.on('dialog',lambda d:d.accept())
 def login(user,password):
  page.locator('#username').fill(user);page.locator('#password').fill(password);page.locator('#auth-form button[type=submit]').click();expect(page.locator('#workspace')).to_be_visible();expect(page.locator('#equipment-list .card').first).to_be_visible()
 def logout():page.locator('#identity button').click();expect(page.locator('#auth-panel')).to_be_visible()
 def book(name,purpose):
  page.locator('#tabs button[data-tab=equipment]').click();card=page.locator('#equipment-list .card').filter(has=page.get_by_role('heading',name=name,exact=True));expect(card).to_be_visible();card.get_by_role('button',name='选择时段').click()
  start=(dt.datetime.now()+dt.timedelta(days=2)).replace(hour=10,minute=0,second=0,microsecond=0)
  page.locator('#start-at').fill(start.isoformat(timespec='minutes'));page.locator('#end-at').fill((start+dt.timedelta(hours=1)).isoformat(timespec='minutes'));page.locator('#purpose').fill(purpose)
  page.locator('#load-schedule').click();expect(page.locator('#schedule')).not_to_be_empty();page.locator('#booking-form button[type=submit]').click();expect(page.locator('#booking-dialog')).not_to_be_visible()
  page.locator('#tabs button[data-tab=reservations]').click();row=page.locator('#reservation-list .reservation').filter(has_text=purpose);expect(row).to_be_visible();return row
 page.goto(base+'/');login('alice','password123')
 page.screenshot(path=str(out/'booking-workspace.png'),full_page=True)
 purpose='浏览器验证 '+run_id+' <img src=x onerror=alert(1)>'
 row=book('示波器 · A01',purpose);expect(row.locator('.status')).to_have_text('已确认');expect(row.locator('img')).to_have_count(0)
 row.get_by_role('button',name='流转记录').click();expect(page.locator('#audit-list')).to_contain_text('提交预约');page.locator('#audit-dialog [data-close]').click()
 row.get_by_role('button',name='取消',exact=True).click();expect(row.locator('.status')).to_have_text('已取消');logout()
 login('admin',os.environ['ADMIN_PASSWORD']);page.locator('#admin-tab').click();expect(page.locator('#summary')).to_contain_text('运行概览')
 name='浏览器验证设备 '+run_id;form=page.locator('#equipment-form');form.locator('[name=name]').fill(name);form.locator('[name=category]').fill('测试');form.locator('[name=location]').fill('测试楼 101');form.locator('[name=description]').fill('浏览器完整操作链路');form.locator('[name=approvalRequired]').check();form.locator('[name=trainingRequired]').check();form.get_by_role('button',name='创建设备').click()
 expect(page.locator('#training-equipment')).to_contain_text(name);page.locator('#training-equipment').select_option(label=name);page.locator('#user-search').fill('alice');page.locator('#find-user').click();expect(page.locator('#training-user')).to_contain_text('alice');page.locator('#training-form button[type=submit]').click();expect(page.locator('#toast')).to_contain_text('培训资格已更新');logout()
 login('alice','password123');row=book(name,'培训设备审批浏览器验证 '+run_id);expect(row.locator('.status')).to_have_text('待审批');logout()
 login('admin',os.environ['ADMIN_PASSWORD']);page.locator('#tabs button[data-tab=reservations]').click();row=page.locator('#reservation-list .reservation').filter(has_text='培训设备审批浏览器验证 '+run_id);row.get_by_role('button',name='批准',exact=True).click();expect(row.locator('.status')).to_have_text('已确认')
 page.screenshot(path=str(out/'booking-reservations.png'),full_page=False)
 mobile=browser.new_context(viewport={'width':390,'height':844},is_mobile=True,timezone_id='Asia/Shanghai');mp=mobile.new_page();mp.goto(base+'/');expect(mp.locator('#auth-panel')).to_be_visible();assert mp.evaluate('document.documentElement.scrollWidth<=window.innerWidth');mp.screenshot(path=str(out/'booking-mobile.png'),full_page=True)
 assert not errors,errors
 (root/'docs/validation/browser.json').write_text(json.dumps({'desktop':'1440x1000','mobile':'390x844','login':'passed','reservation_and_cancel':'passed','audit':'passed','create_equipment':'passed','grant_training':'passed','approval':'passed','xss_text_rendering':'passed','mobile_overflow':False,'javascript_errors':errors},indent=2))
 print('Browser checks passed: login, reserve, cancel, audit, equipment, training, approve, mobile.',flush=True)
 browser.close()

async (page) => {
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.evaluate(() => { window.confirm = () => true; });
  const unique = Date.now().toString();
  const purpose = '预约改期验收 ' + unique;
  const reason = '设备维护验收 ' + unique;
  const day = new Date(); day.setDate(day.getDate() + 2);
  const date = day.getFullYear() + '-' + String(day.getMonth() + 1).padStart(2, '0') + '-' + String(day.getDate()).padStart(2, '0');
  const check = (value, message) => { if (!value) throw new Error(message); };
  async function login(username, password) {
    await page.locator('#username').fill(username);
    await page.locator('#password').fill(password);
    await page.locator('#auth-form [type=submit]').click();
    await page.locator('#workspace').waitFor({state: 'visible'});
    await page.getByRole('heading', {name:'示波器 · A01', exact:true}).waitFor();
  }
  async function logout() {
    await page.locator('#identity button').click();
    await page.locator('#auth-panel').waitFor({state:'visible'});
  }
  const card = () => page.locator('#equipment-list .card').filter({has:page.getByRole('heading',{name:'示波器 · A01',exact:true})});
  await login('alice', 'password123');
  await card().getByRole('button',{name:'选择时段'}).click();
  await page.locator('#start-at').fill(date + 'T10:00');
  await page.locator('#end-at').fill(date + 'T11:00');
  await page.locator('#purpose').fill(purpose);
  await page.locator('#booking-form [type=submit]').click();
  await page.locator('#booking-dialog').waitFor({state:'hidden'});
  await page.locator('#tabs [data-tab=reservations]').click();
  let row = page.locator('#reservation-list .reservation').filter({hasText:purpose}).first();
  await row.getByRole('button',{name:'改期',exact:true}).click();
  await page.locator('#start-at').fill(date + 'T12:00');
  await page.locator('#end-at').fill(date + 'T13:00');
  await page.locator('#booking-form [type=submit]').click();
  await page.locator('#booking-dialog').waitFor({state:'hidden'});
  await page.waitForFunction(p => document.querySelectorAll('#reservation-list .reservation').length >= 2 && [...document.querySelectorAll('#reservation-list .reservation')].filter(e=>e.textContent.includes(p)).length===2, purpose);
  row = page.locator('#reservation-list .reservation').filter({hasText:purpose}).first();
  check((await row.locator('.status').innerText())==='已确认','Replacement was not confirmed');
  await row.getByRole('button',{name:'取消',exact:true}).click();
  await page.waitForFunction(p => [...document.querySelectorAll('#reservation-list .reservation')].filter(e=>e.textContent.includes(p)).every(e=>e.querySelector('.status').textContent==='已取消'), purpose);
  await logout();

  await login('admin','admin12345');
  await page.locator('#admin-tab').click();
  await page.locator('#maintenance-panel').waitFor({state:'visible'});
  await page.locator('#maintenance-equipment').selectOption({label:'示波器 · A01'});
  await page.locator('#maintenance-start').fill(date + 'T10:00');
  await page.locator('#maintenance-end').fill(date + 'T11:00');
  await page.locator('#maintenance-reason').fill(reason);
  await page.locator('#maintenance-form [type=submit]').click();
  await page.locator('#maintenance-list .notice').filter({hasText:reason}).waitFor();
  check((await page.locator('#summary').innerText()).includes('通知积压'),'Operations summary missing');
  await logout();

  await login('alice','password123');
  await card().getByRole('button',{name:'选择时段'}).click();
  await page.locator('#start-at').fill(date + 'T10:00');
  await page.locator('#load-schedule').click();
  await page.locator('#schedule').getByText('维修停机',{exact:false}).first().waitFor();
  await page.locator('#booking-dialog [data-close]').click();
  await logout();
  await login('admin','admin12345');
  await page.locator('#admin-tab').click();
  await page.locator('#maintenance-equipment').selectOption({label:'示波器 · A01'});
  await page.locator('#maintenance-list .notice').filter({hasText:reason}).getByRole('button',{name:'取消停机'}).click();
  await page.locator('#maintenance-list .notice').filter({hasText:reason}).waitFor({state:'hidden'});
  await logout();
  await page.setViewportSize({width:390,height:844});
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Mobile horizontal overflow');
  check(errors.length===0, errors.join('; '));
  const result = {login:'passed',reschedule:'passed',cancel:'passed',maintenance:'passed',calendar:'passed',operations:'passed',mobile:'passed',javascriptErrors:errors};
  await page.evaluate(value => { window.bookingVerification = value; }, result);
  return result;
}

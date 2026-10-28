/* DOM extraction only. This file is never installed as a bridge on a remote page. */
(function (doc, kind, expected) {
  'use strict';
  const text = node => String(node && node.textContent || '').replace(/\s+/g, ' ').trim();
  const links = node => Array.from(node.querySelectorAll('a[href]'));
  const address = node => { try { return new URL(node.getAttribute('href'), 'https://www.wenku8.net/'); } catch (_) { return null; } };
  const positive = value => /^[1-9]\d{0,9}$/.test(String(value || '')) ? Number(value) : 0;
  const fail = (message, code) => ({error: message, code:code || 'response'});
  const cleanName = value => String(value || '').replace(/[（(]?\s*留空则[\s\S]*$/, '').replace(/[（(]修改[\s\S]*$/, '').trim();
  const field = cell => {const input=cell.querySelector('input:not([type=hidden]):not([type=button]):not([type=submit]),textarea,select');return input?String(input.value || '').trim():cleanName(text(cell));};
  const body = text(doc.body);
  const challenged = /Just a moment|Verify you are human|Attention Required|验证您是人类/i.test(doc.title + ' ' + body.slice(0, 1000)) || doc.querySelector('#challenge-running');
  const gate = () => challenged?fail('书架同步需要官网验证，请在“我的”打开官方网页完成验证；本机记录不受影响','verification'):doc.querySelector('input[name="password"]')?fail('官网登录已过期，请重新登录后同步书架；本机记录不受影响','login'):null;
  if (kind === 'profile') {
    const profile = {};
    Array.from(doc.querySelectorAll('table.grid tr')).forEach(row => {
      const cells = Array.from(row.children).filter(n => n.tagName === 'TD');
      for (let i = 0; i + 1 < cells.length; i += 2) {
        const key = text(cells[i]).replace(/[：:\s]/g, ''), value = field(cells[i + 1]);
        if (/^(用户|用戶)ID$/i.test(key)) profile.uid = String(positive(value));
        if (/^(用户名|用戶名)$/.test(key)) profile.uname = cleanName(value);
        if (/^(昵称|暱稱|昵稱)$/.test(key)) profile.nickname = cleanName(value);
      }
    });
    if (!positive(profile.uid) || !profile.uname) return gate() || fail('网站尚未返回有效账号资料，请在官方网页确认登录状态');
    return {profile};
  }
  if (kind === 'shelf') {
    const form = doc.querySelector('form#checkform,form[name="checkform"]');
    if (!form) return gate() || fail('网站未返回书架页面，原书架已保留；可在“我的”打开官方网页核对');
    const header = text(form.querySelector('.gridtop'));
    const counts = header.match(/已收藏\s*(\d+)\s*本[\s\S]*?本组有\s*(\d+)\s*本/);
    if (!counts) return fail('书架数量校验失败，原书架已保留');
    const books = [], seen = new Set();
    const checks = Array.from(form.querySelectorAll('input[name="checkid[]"]'));
    for (const check of checks) {
      const row = check.closest('tr'), cells = row ? Array.from(row.children) : [];
      const titleLink = row && links(row).find(a => {const u = address(a); return u && /\/readbookcase\.php$/.test(u.pathname) && !u.searchParams.get('cid');});
      const u = titleLink && address(titleLink), aid = u && positive(u.searchParams.get('aid')), rowId = positive(check.value);
      if (!aid || aid > 1000000 || !rowId || positive(u.searchParams.get('bid')) !== rowId || seen.has(aid) || cells.length < 6) return fail('书架记录不完整，原书架已保留');
      seen.add(aid);
      books.push({id: String(aid), source:'online', title:text(titleLink), author:text(cells[2]), updated:text(cells[5]), rowId:String(rowId), favorite:true, cloud:true, cover:'https://img.wenku8.com/image/'+Math.floor(aid/1000)+'/'+aid+'/'+aid+'s.jpg'});
    }
    const groups = Array.from(form.querySelectorAll('select[name="classlist"] option')).map(o => Number(o.value)).filter(n => Number.isInteger(n) && n >= 0 && n <= 5);
    const pages = [1];
    links(doc).forEach(a => {const u = address(a); if (u && /\/bookcase\.php$/.test(u.pathname)) {const n = positive(u.searchParams.get('page')); if (n) pages.push(n);}});
    const lastPage = Math.max.apply(null, pages);
    if (lastPage > 30 || Number(counts[2]) > 2000 || (Number(counts[2]) === 0 && books.length)) return fail('书架分页超出校验范围，原书架已保留');
    return {books, total:Number(counts[1]), inGroup:Number(counts[2]), groups, pages:lastPage};
  }
  if (kind === 'action') return gate() || {received:true}; // success is always checked against a fresh shelf.
  return fail('不支持的网页操作');
})

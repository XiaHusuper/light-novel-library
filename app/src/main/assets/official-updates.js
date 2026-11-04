/* Read-only official-page extraction. Diagnostics contain counts and tag names only. */
(function(doc,kind,expected){
 'use strict';expected=expected||{};
 const text=n=>String(n&&n.textContent||'').replace(/\s+/g,' ').trim();
 const content=doc.querySelector('#content')||doc.body;
 const host=h=>/^(?:www\.)?wenku8\.(?:net|com)$/.test(h);
 const base='https://www.wenku8.net/modules/article/toplist.php?sort=lastupdate&page='+Number(expected.page||1);
 const address=a=>{try{return new URL(a.getAttribute('href'),base);}catch(e){return null;}};
 const idOf=a=>{
  const u=address(a);if(!u||!host(u.hostname)||!/^https?:$/.test(u.protocol))return '';
  const m=u.pathname.match(/^\/book\/([1-9]\d{0,6})\.html?$/);return m?m[1]:u.pathname==='/modules/article/articleinfo.php'&&/^[1-9]\d{0,6}$/.test(u.searchParams.get('id')||'')?u.searchParams.get('id'):'';
 };
 const candidates=Array.from((content||doc).querySelectorAll('a[href]')).filter(a=>idOf(a)&&text(a));
 const diag={kind:kind==='identity'?'identity':'updates',tables:(content||doc).querySelectorAll('table').length,grids:(content||doc).querySelectorAll('table.grid').length,bookLinks:candidates.length,uniqueBooks:new Set(candidates.map(idOf)).size,recognized:0,dated:0};
 if(candidates.length){let n=candidates[0];diag.linkPath=[];for(let i=0;n&&i<6;i++,n=n.parentElement)diag.linkPath.push(n.tagName.toLowerCase());}
 const fail=(message,code)=>({error:message,code:code||'response',diagnostics:diag});
 const body=text(doc.body),head=String(doc.title||'')+' '+body.slice(0,1800);
 if(/Just a moment|Verify you are human|Attention Required|Sorry,? you have been blocked|验证您是人类/i.test(head)||doc.querySelector('#challenge-running,#challenge-stage'))return fail('官网要求验证或暂时拦截访问，请在当前网页核对。','verification');
 if(doc.querySelector('input[name="password"]'))return {gate:'login',captcha:!!doc.querySelector('input[name="checkcode"],input[name="captcha"]'),diagnostics:diag};
 if(kind==='identity'){
  let uid='';Array.from(doc.querySelectorAll('table tr')).forEach(row=>{
   const cells=Array.from(row.children).filter(n=>/^(TD|TH)$/.test(n.tagName));
   for(let i=0;i+1<cells.length;i++)if(/^(用户|用戶)ID[:：]?$/i.test(text(cells[i]).replace(/\s/g,''))){const value=text(cells[i+1]);if(/^[1-9]\d*$/.test(value))uid=value;}
  });return uid?{uid}:fail('官网尚未返回可核对的账号资料。','identity');
 }
 function date(value){
  const m=String(value||'').match(/(\d{4})\s*[-\/年]\s*(\d{1,2})\s*[-\/月]\s*(\d{1,2})(?:日)?/);if(!m)return '';
  const year=Number(m[1]),month=Number(m[2]),day=Number(m[3]),d=new Date(Date.UTC(year,month-1,day));
  return d.getUTCFullYear()===year&&d.getUTCMonth()===month-1&&d.getUTCDate()===day?m[1]+'-'+String(month).padStart(2,'0')+'-'+String(day).padStart(2,'0'):'';
 }
 const labelDate=value=>{const m=value.match(/(?:最后更新|最後更新|更新时间|更新時間|更新日期|更新)\s*[:：]\s*(\d{4}\s*[-\/年]\s*\d{1,2}\s*[-\/月]\s*\d{1,2}(?:日)?)/);return m?date(m[1]):'';};
 const labelAuthor=value=>(value.match(/(?:小说作者|小說作者|作者)\s*[:：]\s*(.*?)(?:\s*[/／]|\s*(?:分类|分類|状态|狀態|更新|字数|字數)\s*[:：]|$)/)||[])[1]||'';
 const sameBook=(node,id)=>{const ids=new Set(Array.from(node.querySelectorAll('a[href]')).map(idOf).filter(Boolean));return ids.size===1&&ids.has(id);};
 const rowFields=row=>{
  const cells=Array.from(row.children).filter(n=>/^(TD|TH)$/.test(n.tagName)),table=row.closest('table');let author='',updated='';
  if(table)for(const header of Array.from(table.rows)){
   if(header===row)break;const heads=Array.from(header.children).filter(n=>/^(TD|TH)$/.test(n.tagName)).map(n=>text(n).replace(/[\s：:]/g,''));
   const u=heads.findIndex(v=>/^(?:最后|最後|最近)?更新(?:时间|時間|日期)?$/.test(v)),a=heads.findIndex(v=>/^(?:小说|小說)?作者$/.test(v));
   if(u>=0&&cells[u])updated=date(text(cells[u]));if(a>=0&&cells[a])author=text(cells[a]);
  }
  if(!updated){const cell=cells.find(c=>/^\d{4}[-\/年]\d{1,2}[-\/月]\d{1,2}(?:日)?(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?$/.test(text(c)));if(cell)updated=date(text(cell));}
  return {author,updated};
 };
 const books=[],seen=new Set(),actions=/^(我要阅读|我要閱讀|加入书架|加入書架|查看详情|查看詳情|开始阅读|開始閱讀|阅读|閱讀)$/;
 for(const a of candidates){
  const id=idOf(a),title=text(a);if(seen.has(id)||actions.test(title)||title.length>300)continue;
  let info=null;
  const row=a.closest('tr');if(row&&sameBook(row,id)){
   const fields=rowFields(row),plain=text(row);fields.updated=fields.updated||labelDate(plain);fields.author=fields.author||labelAuthor(plain);
   if(fields.updated)info=fields;
  }
  if(!info){let node=a.parentElement;while(node&&node!==doc.body){
   if(!sameBook(node,id))break;
   const plain=text(node),updated=labelDate(plain),author=labelAuthor(plain);
   // Covers are optional. Accept a complete dated entry or a recognizable book card.
   if(updated||author&&node.querySelector('img')){info={author,updated};break;}if(node===content)break;node=node.parentElement;
  }}
  if(!info)continue;seen.add(id);if(info.updated)diag.dated++;books.push({id,source:'online',title,author:info.author,updated:info.updated,cover:'https://img.wenku8.com/image/'+Math.floor(Number(id)/1000)+'/'+id+'/'+id+'s.jpg'});
 }
 diag.recognized=books.length;
 if(!books.length){
  if(/请求过于频繁|请求过多|访问过于频繁|访问太频繁|Too Many Requests/i.test(body))return fail('官网暂时限制了本次访问，请稍后再试。','limited');
  return candidates.length?fail('网页存在书籍链接，但书目版式尚未识别。请保留网页并复制读取信息。','structure'):fail('当前网页没有找到更新书目链接，请核对下方是否为今日更新榜单。','empty');
 }
 const pagination=doc.querySelector('.pages,.pagelink,.pagination,#pagestats,[aria-label="分页"]'),page=Number(expected.page);let pages=0,current=0;
 if(pagination){
  // Keep sibling text separate: <em>1/217</em><a>1</a> must not become 1/2171.
  const separated=node=>node.nodeType===3?node.nodeValue:Array.from(node.childNodes).map(separated).join(' ');
  const label=separated(pagination).replace(/\s+/g,' ').trim();
  const pair=value=>{const m=value.match(/(?:^|\s)(\d+)\s*\/\s*(\d+)(?=\s|$)/);if(!m)return null;const c=Number(m[1]),n=Number(m[2]);return Number.isInteger(c)&&c>0&&c<=n&&n<=1000?{current:c,total:n}:null;};
  const stats=Array.from(pagination.querySelectorAll('#pagestats,.pagestats'));if(pagination.matches('#pagestats,.pagestats'))stats.unshift(pagination);
  const named=stats.map(n=>pair(separated(n).trim())).find(Boolean),fraction=pair(label);
  let lastTotal=0;
  for(const a of Array.from(pagination.querySelectorAll('a[href]'))){
   const u=address(a);if(!u||!host(u.hostname)||u.pathname!=='/modules/article/toplist.php'||u.searchParams.get('sort')&&u.searchParams.get('sort')!=='lastupdate')continue;
   const n=Number(u.searchParams.get('page')),next=a.classList.contains('next')||/\bnext\b/i.test(a.getAttribute('rel')||'');
   const last=a.classList.contains('last')||/\blast\b/i.test(a.getAttribute('rel')||'')||/^(?:末页|尾页|最后一页|最後一頁|末頁|尾頁)$/.test(text(a));
   if(!next&&last&&Number.isInteger(n)&&n>0&&n<=1000)lastTotal=Math.max(lastTotal,n);
  }
  const totalLabel=label.match(/共\s*(\d+)\s*[页頁]/),labelTotal=totalLabel?Number(totalLabel[1]):0;
  const primary=named||fraction;
  // The site's explicit page pair wins. Auxiliary arrows/labels never veto valid site pagination.
  if(primary){current=primary.current;pages=primary.total;diag.paginationSource=named?'stats':'fraction';}
  else if(lastTotal){pages=lastTotal;diag.paginationSource='last-link';}
  else if(labelTotal){pages=labelTotal;diag.paginationSource='total-label';}
  else if(/仅\s*1\s*页/.test(label)){pages=1;diag.paginationSource='single-page';}
  const semantic=Array.from(pagination.querySelectorAll('.current,.currentpage,.pagenow,[aria-current="page"]')).find(n=>/^\d+$/.test(text(n)));
  const bold=Array.from(pagination.querySelectorAll('strong,b')).find(n=>!n.closest('a,#pagestats,.pagestats')&&/^\d+$/.test(text(n))&&!/^共\s*\d+\s*[页頁]$/.test(separated(n.parentElement).trim()));
  const marked=semantic||bold;
  if(!current&&marked)current=Number(text(marked));
  diag.statPages=named?named.total:0;diag.statCurrent=named?named.current:0;diag.linkPages=lastTotal;diag.labelPages=labelTotal;diag.markedCurrent=marked?Number(text(marked)):0;
 }
 diag.hasPagination=!!pagination;diag.pages=pages;diag.current=current;
 if(!Number.isInteger(pages)||pages<1||pages>1000)return fail('已识别书目，但官网总页数尚未确认。请保留网页并复制读取信息。','pagination');
 if(current&&current!==page)return fail('官网返回的页码与本次请求不一致，未推进页码。','page');
 return {books,page,pages};
})

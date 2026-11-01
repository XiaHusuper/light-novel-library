const{chromium}=require(process.env.READER_PLAYWRIGHT||'playwright');
const fs=require('fs'),path=require('path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'),output=path.resolve(process.env.READER_TEST_OUTPUT||path.join(root,'dist/test-results'));
fs.mkdirSync(output,{recursive:true});
function fixture(){
 const books=Array.from({length:140},(_,i)=>({id:String(i+1),source:'online',title:'原创故事 '+(i+1),author:'原创作者',tags:'魔法 冒险',cover:'data:image/svg+xml,'+encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="150" height="210"><rect width="150" height="210" fill="#3a5068"/></svg>')}));
 window.__calls=[];window.__fixture={books};
 window.ReaderNative={request(id,operation,json){const a=JSON.parse(json);window.__calls.push({operation,args:a});let data={};
 if(operation==='bootstrap')data={state:{settings:{mode:'page'},shelf:books.slice(80,120).map(b=>Object.assign({},b,{favorite:true}))},localBooks:[],bookmarks:[],account:{signedIn:false,books:[]}};
 else if(operation==='feed')data={page:a.page,pages:4,books:books.slice((a.page-1)*30,a.page*30)};
 else if(operation==='saveState')window.__saved=a;
 else if(operation==='metadata')data=books.find(b=>b.id===String(a.id));
 else if(operation==='intro')data={summary:'原创测试简介'};
 else if(operation==='catalog')data={chapters:[{id:'111',title:'第一章',volume:'第一卷',key:'111'}]};
 else if(operation==='chapter')data={format:'text',text:Array(80).fill('原创测试段落，回到列表时应当保留点击前的同一本书和封面。').join('\n')};
 setTimeout(()=>window.__nativeResponse({id,ok:true,data}),5);
 }};
}
async function run(){const browser=await chromium.launch({channel:process.env.READER_BROWSER_CHANNEL||undefined,headless:true});try{
 const p=await browser.newPage({viewport:{width:393,height:852}}),errors=[],results=[];p.on('pageerror',e=>errors.push(e.message));await p.addInitScript(fixture);
 await p.goto('file:///'+path.join(root,'app/src/main/assets/index.html').replace(/\\/g,'/'));await p.waitForFunction(()=>window.App&&document.querySelectorAll('.book-card').length===40);
 for(const tab of ['shelf','discover','recommend']){
  if(tab!=='shelf'){await p.click('[data-tab="'+tab+'"]');await p.waitForFunction(()=>document.querySelectorAll('.book-card').length>=20);await p.waitForTimeout(120);}
  const card=p.locator('.book-card').nth(14);await card.scrollIntoViewIfNeeded();await p.evaluate(()=>window.scrollBy(0,-160));await p.waitForTimeout(250);
  const before=await p.evaluate(()=>{const card=document.querySelectorAll('.book-card')[14];window.__returnCard=card;window.__returnImage=card.querySelector('img');return {id:card.dataset.id,top:card.getBoundingClientRect().top,y:scrollY,ids:Array.from(document.querySelectorAll('.book-card'),n=>n.dataset.id),feedCalls:window.__calls.filter(c=>c.operation==='feed').length};});assert.ok(before.y>500);
  await card.locator('.cover').click();await p.waitForFunction(()=>!document.getElementById('readButton').disabled);
  // Exercise a nested fixed scroller and document scroll drift while it is covered.
  await p.locator('.detail-scroll').evaluate(n=>n.scrollTop=n.scrollHeight);await p.evaluate(()=>window.scrollTo(0,0));
  const returned=await p.evaluate(()=>{const grid=document.getElementById('grid');let changes=0;const watch=new MutationObserver(records=>changes+=records.length);watch.observe(grid,{childList:true,subtree:true});document.getElementById('detailBack').click();const card=window.__returnCard,result={top:card.getBoundingClientRect().top,y:scrollY,sameCard:card.isConnected&&document.querySelector('[data-id="'+card.dataset.id+'"]')===card,sameImage:card.querySelector('img')===window.__returnImage,hidden:document.getElementById('detail').classList.contains('hidden'),changes:watch.takeRecords().length,feedCalls:window.__calls.filter(c=>c.operation==='feed').length};watch.disconnect();return result;});
  assert.ok(Math.abs(returned.top-before.top)<1,tab+' restores synchronously');assert.equal(returned.y,before.y);assert.ok(returned.sameCard&&returned.sameImage&&returned.hidden);assert.equal(returned.changes,0,tab+' does not rebuild the grid');assert.equal(returned.feedCalls,before.feedCalls);
  await p.waitForTimeout(350);assert.ok(Math.abs((await card.boundingBox()).y-before.top)<1,tab+' remains stable after paint');assert.deepEqual(await p.locator('.book-card').evaluateAll(ns=>ns.map(n=>n.dataset.id)),before.ids);assert.equal(await p.evaluate(()=>window.__calls.filter(c=>c.operation==='feed').length),before.feedCalls);
  results.push({tab,immediateReturn:true,sameCardAndImage:true,noGridRebuild:true,noListRequest:true});
 }
 // First reading adds a continue card above the grid: anchor, rather than old Y,
 // must keep the clicked book at the same screen position on the very first frame.
 await p.click('[data-tab="shelf"]');await p.locator('.book-card').nth(18).scrollIntoViewIfNeeded();await p.evaluate(()=>scrollBy(0,-160));const book=p.locator('.book-card').nth(18),old=await book.boundingBox();await book.locator('.cover').click();await p.waitForFunction(()=>!document.getElementById('readButton').disabled);await p.click('#readButton');await p.waitForFunction(()=>document.getElementById('reader').dataset.ready==='1');await p.evaluate(()=>window.App.back());await p.evaluate(()=>window.App.back());assert.ok(Math.abs((await book.boundingBox()).y-old.y)<1,'reading return compensates for newly added continue card');
 await p.screenshot({path:path.join(output,'reader-v82-list-return.png')});assert.deepEqual(errors,[]);const report={passed:true,results,readingReturnKeepsAnchorWhenContinueCardAppears:true,errors};fs.writeFileSync(path.join(output,'reader-v82-list-return-test.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
 }finally{await browser.close();}}
if(require.main===module)run().catch(e=>{console.error(e);process.exit(1)});module.exports={fixture};

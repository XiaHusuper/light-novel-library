const{chromium}=require(process.env.READER_PLAYWRIGHT||'playwright'),fs=require('fs'),path=require('path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'),out=path.resolve(process.env.READER_TEST_OUTPUT||path.join(root,'dist/test-results'));fs.mkdirSync(out,{recursive:true});
function fixture(){
 const book=id=>({id:String(id),source:'online',title:'Original account story '+id,author:'Original author',cover:''});
 window.__syncFixture={books:[book(129)],pending:0,calls:[],signedIn:true};
 window.ReaderNative={request(id,op,json){const a=JSON.parse(json),f=window.__syncFixture;f.calls.push({op,a});let data={};const status=()=>({signedIn:f.signedIn,profile:f.signedIn?{uid:'123',uname:'Original account'}:null,books:f.signedIn?f.books:[],pendingShelfCount:f.pending});
 if(op==='bootstrap')data={state:{shelf:[Object.assign(book(129),{favorite:true}),Object.assign(book(200),{favorite:true})]},localBooks:[],bookmarks:[],account:status()};
 else if(op==='accountPull'||op==='accountStatus')data=status();
 else if(op==='accountRemove'){f.books=f.books.filter(b=>String(b.id)!==String(a.id));f.pending=1;data=status();}
 else if(op==='accountLogout'){f.signedIn=false;data=status();}
 else if(op==='metadata')data=book(a.id);
 else if(op==='intro')data={summary:'Original fixture description'};
 else if(op==='catalog')data={chapters:[]};
 setTimeout(()=>window.__nativeResponse({id,ok:true,data}),5);
 }};
}
(async()=>{const b=await chromium.launch({channel:process.env.READER_BROWSER_CHANNEL||undefined,headless:true});try{
 const p=await b.newPage({viewport:{width:393,height:852}}),errors=[];p.on('pageerror',e=>errors.push(e.message));await p.addInitScript(fixture);await p.goto('file:///'+path.join(root,'app/src/main/assets/index.html').replace(/\\/g,'/'));await p.waitForFunction(()=>window.App&&document.querySelector('.book-card[data-id="129"]'));assert.equal(await p.locator('.book-card').count(),1,'Signed-in cloud shelf excludes guest favorites');
 await p.locator('.book-card[data-id="129"] .cover').click();await p.waitForFunction(()=>!document.getElementById('shelfButton').disabled);await p.click('#shelfButton');await p.waitForFunction(()=>window.__syncFixture.pending===1&&!document.getElementById('shelfButton').disabled);await p.click('#detailBack');assert.equal(await p.locator('.book-card').count(),0);
 await p.click('[data-tab="mine"]');await p.click('#accountRefresh');await p.waitForFunction(()=>!document.getElementById('accountRefresh').disabled);await p.click('[data-tab="shelf"]');assert.equal(await p.locator('.book-card').count(),0,'Confirmed removal never survives as duplicate guest favorite');
 await p.evaluate(()=>{window.__syncFixture.pending=0;window.__syncFixture.books=[{id:'300',source:'online',title:'Original website story',cover:''}]});await p.click('[data-tab="mine"]');await p.click('#accountRefresh');await p.waitForFunction(()=>!document.getElementById('accountRefresh').disabled);await p.click('[data-tab="shelf"]');assert.deepEqual(await p.locator('.book-card').evaluateAll(ns=>ns.map(n=>n.dataset.id)),['300']);
 await p.evaluate(()=>window.__syncFixture.books=[]);await p.click('[data-tab="mine"]');await p.click('#accountRefresh');await p.waitForFunction(()=>!document.getElementById('accountRefresh').disabled);await p.click('[data-tab="shelf"]');assert.equal(await p.locator('.book-card').count(),0,'Website removal appears when the account response changes');
 await p.click('[data-tab="mine"]');await p.click('#accountLogout');await p.waitForFunction(()=>!document.getElementById('accountLogin').classList.contains('hidden'));await p.click('[data-tab="shelf"]');assert.deepEqual(await p.locator('.book-card').evaluateAll(ns=>ns.map(n=>n.dataset.id)),['200'],'Unrelated guest favorite retained for logout');assert.equal(await p.evaluate(()=>window.__syncFixture.calls.filter(c=>c.op==='accountRemove').length),1);assert.deepEqual(errors,[]);
 const report={passed:true,accountAndGuestShelvesSeparate:true,acceptedRemovalStaysAbsent:true,websiteChangesApplyWhenReceived:true,guestFavoritesRetained:true,singleWrite:true,errors};fs.writeFileSync(path.join(out,'sync1-shelf-ui.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
 }finally{await b.close();}})().catch(e=>{console.error(e);process.exit(1)});

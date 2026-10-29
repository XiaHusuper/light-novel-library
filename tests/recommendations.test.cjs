const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),crypto=require('node:crypto');
const context={window:{}};vm.createContext(context);
for(const file of ['taxonomy.js','library.js','profile.js','recommendations.js'])vm.runInContext(fs.readFileSync(path.join(__dirname,'../app/src/main/assets',file),'utf8'),context);
const R=context.window.ReaderRecommendation,now=Date.UTC(2026,9,30,4);
for(const key of ['','abc','guest:2026-10-30:1:42:explore','账户:2026-10-30:99:3:']){const expected=Number(BigInt('0x'+crypto.createHash('sha256').update(key).digest('hex').slice(0,16)))/Number((1n<<64n)-1n);assert.ok(Math.abs(R.seed(key)-expected)<1e-15);}
const books=Array.from({length:120},(_,i)=>({id:String(i+1),source:'online',title:'原创故事 '+(i+1),author:'原创作者',tags:i<80?'魔法 冒险':i%2?'青春 恋爱':'悬疑 旅行'}));
const pref={likes:['魔法'],avoid:[],feedback:{}},pick=(shown=[],extra={})=>R.select(books,pref,[],[],{books:{}},[],{limit:20,shown,identity:'guest',batch:1,now,...extra});
const first=pick();assert.equal(first.books.length,20);assert.equal(first.explorationCount,4);assert.deepEqual(Array.from(first.books,(b,i)=>b.recommendationKind==='explore'?i:-1).filter(i=>i>=0),[4,9,14,19]);
assert.deepEqual(Array.from(pick().books,b=>b.id),Array.from(first.books,b=>b.id));
const history=[],all=new Set();for(let batch=0;batch<6;batch++){const result=pick(history,{batch});assert.equal(result.books.length,20);for(const b of result.books){assert.ok(!all.has(b.id));all.add(b.id);history.push(b.id);}}
assert.equal(all.size,120);assert.equal(pick(history).books.length,0);assert.equal(history.length,120);
const reset=pick(history,{allowReset:true,batch:7});assert.equal(reset.rotationReset,true);assert.equal(reset.books.length,20);const recent=new Set(history.slice(-20));assert.ok(reset.books.every(b=>!recent.has(b.id)));
const short=pick(books.slice(0,119).map(b=>b.id),{allowReset:true});assert.equal(short.books.length,1);assert.equal(short.books[0].id,'120');assert.equal(short.rotationReset,false);
const shelf=books.slice(0,3).map(b=>({...b,favorite:true})),experienced=R.select(books,pref,shelf,[],{books:{}},shelf,{now,limit:20});assert.equal(experienced.explorationCount,2);assert.deepEqual(Array.from(experienced.books,(b,i)=>b.recommendationKind==='explore'?i:-1).filter(i=>i>=0),[9,19]);assert.ok(experienced.books.every(b=>!shelf.some(s=>s.id===b.id)));
const weak={books:{'1':{views:{'2026-10-30':{weight:.1,at:now}},reads:{},hours:{},touched:now}}},cold=R.select(books,{likes:[],avoid:[],feedback:{}},[],[books[0]],weak,[],{now,limit:20});assert.equal(cold.explorationCount,0);assert.ok(cold.books.every(b=>b.recommendationKind==='discovery'));
const excluded=R.select(books,{likes:['魔法'],avoid:['悬疑'],feedback:{'2':'hide'}},[books[0]],[],{books:{}},[{id:'local',source:'local',title:books[2].title,author:books[2].author}],{now,limit:20});assert.ok(excluded.books.every(b=>!['1','2','3'].includes(b.id)&&!b.tags.includes('悬疑')));
console.log(JSON.stringify({passed:true,twentyPerBatch:true,sha256WebsiteSeed:true,exhaustUnseenBeforeRepeat:true,recycleAvoidsPreviousBatch:true,partialRoundDoesNotRepeat:true,explorationSpacedAndTenOrTwentyPercent:true,weakClickKeepsDiscovery:true,hardShelfAndLocalExclusions:true}));

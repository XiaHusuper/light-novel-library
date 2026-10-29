'use strict';
(function () {
  const $ = id => document.getElementById(id);
  const origin = 'https://appassets.androidplatform.net';
  const paths = {
    back:'M15 5l-7 7 7 7', close:'M6 6l12 12M18 6L6 18',
    search:'M21 21l-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
    settings:'M9 4V2h6v2l2 1 2-1 3 5-2 1v4l2 1-3 5-2-1-2 1v2H9v-2l-2-1-2 1-3-5 2-1v-4L2 9l3-5 2 1 2-1zM15.5 12a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0',
    shelf:'M4 4h4v16H4zM10 4h4v16h-4zM16 5l4-1 4 15-4 1z',
    discover:'M12 3l8 5v9l-8 5-8-5V8zM12 3v19M4 8l8 5 8-5',
    mine:'M20 21v-2a6 6 0 0 0-6-6h-4a6 6 0 0 0-6 6v2M16 6a4 4 0 1 1-8 0 4 4 0 0 1 8 0', spark:'M12 3l2.4 6.6L21 12l-6.6 2.4L12 21l-2.4-6.6L3 12l6.6-2.4z', folder:'M3 6h7l2 3h9v11H3z', catalog:'M7 6h14M7 12h14M7 18h14M3 6h.01M3 12h.01M3 18h.01',
    play:'M8 5l11 7-11 7z', book:'M4 4h6l2 2 2-2h6v16h-6l-2 2-2-2H4zM12 6v16'
  };
  const icon = name => '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="' + paths[name] + '"/></svg>';
  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
  function visible(id, value) { $(id).classList.toggle('hidden', !value); }
  const pending = new Map(),bridgeEpoch=Date.now().toString(36)+Math.random().toString(36).slice(2,10); let sequence = 0;
  function native(operation, payload) {
    return new Promise((resolve, reject) => {
      const id = 'r' + bridgeEpoch + '-' + (++sequence), timeout = ['import','importFolder','accountLogin'].includes(operation) ? null : setTimeout(() => { pending.delete(id); reject(new Error('等待超时，请检查网络后重试')); }, operation.startsWith('account')?240000:45000);
      pending.set(id, {resolve, reject, timeout});
      if (!window.ReaderNative) { clearTimeout(timeout); pending.delete(id); reject(new Error('请在 Android App 中使用')); return; }
      window.ReaderNative.request(id, operation, JSON.stringify(payload || {}));
    });
  }
  window.__nativeResponse = response => {
    const request = pending.get(response.id); if (!request) return;
    pending.delete(response.id); clearTimeout(request.timeout);
    if (response.ok) request.resolve(response.data); else request.reject(new Error(response.error || '暂时无法完成，请重试'));
  };
  let state = {settings:{fontSize:22,lineHeight:1.9,theme:'paper',mode:'scroll',font:'serif',hideStatusBar:false},shelf:[],history:[],learning:{books:{}},progress:{},preference:{likes:[],avoid:[],feedback:{}}};
  const library=window.ReaderLibrary,knownBooks=new Map();let tagFilters=[],filterMode='all',filterDraft=[],filterDraftMode='all',preferenceDraft=null;
  let cacheState={books:[],bytes:0,limitMiB:200},cacheTarget=null,cacheChapters=[],cachePolling=false;
  let accountState={signedIn:false,books:[]},accountBusy=false,lastCloudCheck=0,foreground=true,booted=false;
  let listBusy=false,hasMore=true,listFailed=false,autoBudget=3,autoTimer,lastWindowY=0;
  let historyMode='browse',bookmarks=[],bookmarkUi=null,illustrations=null,aboutUi=null,selectedQuote=null,lastSystemUi='';
  let lastInteraction=Date.now(),lastHeartbeat=Date.now(),detailDwell=0;
  function rememberBooks(books){books.forEach(book=>{knownBooks.delete(String(book.id));knownBooks.set(String(book.id),book);});while(knownBooks.size>300)knownBooks.delete(knownBooks.keys().next().value);}
  let rawLocalBooks=[],localOrders={},localOrderEditor=null;
  function prepareLocal(raw){rawLocalBooks=raw;const grouped=library.applyLocalOrders(library.groupBooks(raw),localOrders);grouped.forEach(book=>{const existing=state.progress[book.id];if(existing){const index=book.chapters.findIndex(c=>c.key===existing.chapterKey);if(index>=0)existing.index=index;return;}const source=book.fileIds.map(id=>({id,progress:state.progress[id]})).filter(p=>p.progress).sort((a,b)=>(b.progress.time||0)-(a.progress.time||0))[0];if(source){const index=book.chapters.findIndex(c=>c.fileId===source.id&&c.fileIndex===source.progress.index);state.progress[book.id]=Object.assign({},source.progress,{index:Math.max(0,index),chapterKey:index>=0?book.chapters[index].key:''});}});return grouped;}

  const listBuffers=new Map();let activeListKey='',searchType='title',tagBusy=false,tagStartWanted=false,tagLimit=30,tagStats={};
  let localBooks = [], tab = 'discover', feed = [], query = '', sort = 'lastupdate', page = 1;
  let listTicket = 0, detailTicket = 0, currentBook = null, chapters = [], readerBook = null, readerChapters = [], currentIndex = 0;
  let pageMotion=null,readingLocation=null,pagination=null,readerControls=null;const chapterMemory=new Map();let chapterMemoryBytes=0;
  let reading = false, chapterTicket = 0, loading = false, imageObserver = null, saveTimer, toastTimer, displayBooks = [];
  function toast(message) { $('toast').textContent = message; $('toast').classList.add('visible'); clearTimeout(toastTimer); toastTimer = setTimeout(() => $('toast').classList.remove('visible'), 3000); }
  function save() { clearTimeout(saveTimer); saveTimer = setTimeout(() => native('saveState', state).catch(e => toast(e.message)), 400); }
  function flush() { clearTimeout(saveTimer); if (reading) rememberPosition(); native('saveState', state).catch(() => {}); }
  function onlineFavorites(){const books=new Map();state.shelf.filter(b=>b.source!=='local'&&b.favorite===true).forEach(b=>books.set(String(b.id),b));(accountState.books||[]).forEach(b=>{const known=knownBooks.get(String(b.id))||state.history.find(h=>String(h.id)===String(b.id))||{};books.set(String(b.id),Object.assign({},known,b,{title:/^书籍 \d+$/.test(b.title)&&known.title?known.title:b.title,favorite:true,cloud:true}));});return Array.from(books.values());}
  function allBooks() { return localBooks.concat(onlineFavorites()); }
  function historyBooks(){return localBooks.map(b=>Object.assign({},b,state.history.find(h=>String(h.id)===String(b.id))||{})).concat(state.history.filter(b=>b.source!=='local'),onlineFavorites());}
  function byId(id) { return allBooks().concat(feed,state.history,cacheState.books||[]).find(book => String(book.id) === String(id)); }
  function rememberHistory(book,started,browsed){const previous=state.history.find(b=>String(b.id)===String(book.id))||{},clean={};['id','source','title','author','cover','tags','summary','updated'].forEach(k=>{if(book[k]!=null)clean[k]=String(book[k]).slice(0,800);});clean.seenAt=started?Date.now():previous.seenAt||book.seenAt||0;clean.viewedAt=browsed?Date.now():previous.viewedAt||book.viewedAt||0;state.history=state.history.filter(b=>String(b.id)!==String(book.id));state.history.unshift(Object.assign({},previous,clean));state.history=state.history.slice(0,200);save();}
  function settings() { return state.settings; }
  function syncSystemUi(){const value={reading,hideStatusBar:Boolean(settings().hideStatusBar),theme:settings().theme},key=JSON.stringify(value);if(key===lastSystemUi)return;lastSystemUi=key;native('readerSystemUi',value).catch(()=>{lastSystemUi='';});}
  function applySettings(preserve) {
    if(pageMotion)pageMotion.reset();
    const previous = preserve && reading ? ratio() : 0,anchor=preserve&&reading&&readingLocation?readingLocation.capture():null;
    const config = settings();
    document.body.classList.remove('theme-paper','theme-warm','theme-night'); document.body.classList.add('theme-' + config.theme);
    document.documentElement.style.setProperty('--font-size', config.fontSize + 'px');
    document.documentElement.style.setProperty('--line-height', config.lineHeight);
    document.documentElement.style.setProperty('--book-font', config.font === 'sans' ? "'Noto Sans',sans-serif" : "Georgia,'Noto Serif CJK SC','Songti SC',serif");
    $('readerViewport').className = 'reader-viewport mode-' + config.mode;
    $('hideStatusBar').checked=Boolean(config.hideStatusBar);syncSystemUi();$('fontSize').value = config.fontSize; $('fontValue').textContent = config.fontSize;
    $('lineHeight').value = config.lineHeight; $('lineValue').textContent = Number(config.lineHeight).toFixed(1);
    document.querySelectorAll('[data-theme]').forEach(node => node.classList.toggle('active', node.dataset.theme === config.theme));
    document.querySelectorAll('[data-mode]').forEach(node => node.classList.toggle('active', node.dataset.mode === config.mode));
    document.querySelectorAll('[data-font]').forEach(node => node.classList.toggle('active', node.dataset.font === config.font));
    measurePage();
    if(preserve&&reading)requestAnimationFrame(()=>{measurePage();if(!anchor||!readingLocation.restore(anchor))restoreRatio(previous);});
  }
  function palette(title) { const colors=['#d9dfca','#e5dac3','#cbd6cb','#d5d9dd','#e1d6ce','#ccd7bc']; return colors[Array.from(title || '').reduce((sum,c)=>sum+c.charCodeAt(0),0)%colors.length]; }
  function cover(book, extra) {
    return '<div class="cover ' + (extra || '') + '" style="--cover-color:' + palette(book.title) + '"><span class="cover-title">' + esc((book.title || '未命名').slice(0,18)) + '</span><span class="cover-small">LIGHT NOVEL LIBRARY</span>' + (book.cover ? '<img decoding="async" data-cover="' + esc(book.cover) + '" alt="">' : '') + '</div>';
  }
  const coverRequests=new Map();let coverObserver;
  function warmCover(url){if(!url)return Promise.resolve(false);if(coverRequests.has(url))return coverRequests.get(url);const promise=new Promise(resolve=>{const image=new Image();image.decoding='async';image.setAttribute('fetchpriority','high');image.onload=()=>resolve(true);image.onerror=()=>{coverRequests.delete(url);resolve(false);};image.src=url;});coverRequests.set(url,promise);while(coverRequests.size>96)coverRequests.delete(coverRequests.keys().next().value);return promise;}
  async function warmCovers(books){const promises=books.slice(0,10).map(b=>warmCover(b.cover));await Promise.race([Promise.all(promises.slice(0,4)),new Promise(resolve=>setTimeout(resolve,900))]);}
  function showCover(image){if(image.getAttribute('src'))return;const url=image.dataset.cover;warmCover(url).then(ok=>{if(image.isConnected&&ok){image.classList.remove('cover-failed');image.src=url;}else if(image.isConnected&&!ok){image.classList.add('cover-failed');image.title='封面暂时无法载入';}});}
  function bindImageErrors(root) { root.querySelectorAll('.cover img').forEach(image => {image.onerror=()=>{coverRequests.delete(image.dataset.cover);image.removeAttribute('src');image.classList.add('cover-failed');};if(['bookHeading','continueSection'].includes(root.id)||!window.IntersectionObserver)showCover(image);else{if(!coverObserver)coverObserver=new IntersectionObserver(entries=>entries.forEach(entry=>{const image=entry.target;if(entry.isIntersecting)showCover(image);else if(image.getAttribute('src'))image.removeAttribute('src');}),{rootMargin:Math.max(1400,window.innerHeight*2)+'px'});coverObserver.observe(image);}}); }
  function skeleton() { $('grid').innerHTML = Array.from({length:6},()=>'<div><div class="skeleton-cover"></div><div class="skeleton-line"></div><div class="skeleton-line short"></div></div>').join(''); $('gridMessage').textContent=''; visible('moreButton',false); }
  function renderGrid(books) {
    if(tab==='recommend')books=books.filter(b=>!library.inShelf(b,allBooks()));
    const previous=new Map(Array.from($('grid').querySelectorAll('.book-card')).map(card=>[card.dataset.id,card])),fragment=document.createDocumentFragment();if(coverObserver)coverObserver.disconnect();displayBooks = books;
    books.forEach((book,index)=>{const signature=JSON.stringify([book.title,book.author,book.cover,book.fileCount,book.recommendationReason,book.cloud]),old=previous.get(String(book.id));let card=old&&old.dataset.signature===signature?old:document.createElement('div');if(card!==old){card.className='book-card';card.setAttribute('tabindex','0');card.setAttribute('role','button');card.setAttribute('aria-label','打开'+book.title);card.dataset.signature=signature;card.dataset.id=String(book.id);card.innerHTML=cover(book)+'<h3 class="book-title">'+esc(book.title)+'</h3><p class="book-author">'+(book.source!=='local'&&book.author?'<button class="author-link" data-author="'+esc(book.author)+'">'+esc(book.author)+'</button>':esc(book.author||'轻小说文库'))+'</p>'+(book.source==='local'?'<span class="book-source">'+esc((book.kind||'本地').toUpperCase())+(book.fileCount>1?' · '+book.fileCount+' 个文件':'')+'</span>':book.cloud?'<span class="book-source">账号书架</span>':'')+(book.recommendationReason?'<p class="recommendation-reason">'+esc(book.recommendationReason)+'</p>':'');}card.dataset.book=String(index);card.dataset.recommendationKind=book.recommendationKind||'';fragment.appendChild(card);});$('grid').textContent='';$('grid').appendChild(fragment);
    bindImageErrors($('grid'));
    $('gridMessage').innerHTML = books.length ? '' : '<div class="empty-illustration">' + (tab==='local'?'⌑':'⌁') + '</div><p>' + (tab==='local'?'选几本喜欢的书，带在身边。':tab==='shelf'?'这里保留主动收藏和本地导入；读过的书可在最近阅读与画像中找到。':tagFilters.length?'正在全库查找所选标签，结果会自动出现。':tab==='recommend'?'暂无符合当前偏好的推荐，可调整偏好或浏览更多作品。':'没有找到这本书，换个关键词试试。') + '</p>';
    visible('moreButton',false);scheduleMore();
  }
  function renderContinue() {
    const books = historyBooks();
    const candidates = Object.keys(state.progress).filter(id => books.some(book => String(book.id)===id)).sort((a,b)=>(state.progress[b].time||0)-(state.progress[a].time||0));
    if (!candidates.length || (tab === 'local'||tab==='recommend')) { $('continueSection').textContent=''; return; }
    const id=candidates[0], book=books.find(book=>String(book.id)===id), progress=state.progress[id];
    $('continueSection').innerHTML='<div class="continue-card">'+cover(book,'continue-cover')+'<div><span class="eyebrow">CONTINUE READING</span><strong>'+esc(book.title)+'</strong><small>'+esc(progress.chapterTitle||('上次读到第 '+(progress.index+1)+' 章'))+' · '+('章内 '+Math.round((progress.ratio||0)*100)+'%')+'</small></div><button aria-label="继续阅读">'+icon('play')+'</button></div>';
    bindImageErrors($('continueSection'));$('continueSection').querySelector('button').onclick=()=>openBook(book,true);
  }
  function listKey(next){return next==='recommend'?'recommend':next==='discover'?JSON.stringify([sort,query,searchType,tagFilters.slice().sort(),filterMode]):'';}
  function rememberList(){if(!activeListKey)return;listBuffers.delete(activeListKey);listBuffers.set(activeListKey,{feed,page,hasMore,meta:tab==='recommend'?recommendationMeta:null});while(listBuffers.size>6){const key=Array.from(listBuffers.keys()).find(k=>k!=='recommend');listBuffers.delete(key);}}
  function isList(){return tab==='discover'||tab==='recommend';}
  function overlayOpen(){return !$('detail').classList.contains('hidden')||['preferencesSheet','tagFilterSheet','profileScreen','historyScreen','cacheScreen','localOrderScreen','bookmarkScreen','illustrationScreen','aboutScreen','licensesScreen','updateScreen','updateNoticeSheet'].some(id=>!$(id).classList.contains('hidden'));}
  function renderTagStatus(){
    const s=tagStats;visible('tagIndexPanel',tab==='discover'&&tagFilters.length>0);$('tagIndexControl').textContent=s.running?'暂停检索':s.complete?'更新标签库':'继续检索';
    $('tagIndexProgress').textContent=s.running?(s.quickFinished?'快速结果已就绪 · 正在补全 ':'正在全库检索 · ')+(s.scannedPages||0)+' / '+(s.totalPages||'…')+' 页 · 已发现 '+(s.totalMatches||0)+' 本':s.error?'检索暂时中断：'+s.error:s.complete?'全库检索完成 · '+(s.totalMatches||0)+' 本匹配':'已保留 '+(s.indexedBooks||0)+' 本书目，可继续检索';
    $('feedStatus').textContent=s.running?(s.quickRunning?'正在快速核对标签，完整检索会自动继续':s.quickFinished?'已显示核实的匹配，正在自动补全；无需刷新':'检索会自动继续，无需刷新'):s.complete?(s.totalMatches>feed.length?'向下滑动加载更多':'已显示全部匹配作品'):'检索尚未完成，当前结果不是全库结论';
    if(!feed.length)$('gridMessage').innerHTML=s.complete?'<p>全库书目没有同时满足当前标签和关键词的作品。可切换“包含任一”或减少条件。</p>':'<div class="spinner" style="margin:auto"></div><p>'+(s.running?'正在查找所选标签，结果会自动出现。':'检索尚未完成，请继续检索。')+'</p>';
  }
  async function loadTags(start,refresh){
    if(tagBusy){tagStartWanted=tagStartWanted||start;return;}if(tab!=='discover'||!tagFilters.length)return;
    const ticket=listTicket;tagBusy=true;const shouldStart=start||tagStartWanted;tagStartWanted=false;
    try{const result=await native('tagQuery',{tags:tagFilters.slice(),mode:filterMode,query,limit:tagLimit,start:shouldStart,refresh:Boolean(refresh)});if(ticket!==listTicket||tab!=='discover'||!tagFilters.length)return;tagStats=Object.assign({initialized:true},result);feed=result.books||[];hasMore=(result.totalMatches||0)>feed.length;rememberBooks(feed);renderGrid(feed);renderTagStatus();}
    catch(error){if(ticket===listTicket){tagStats=Object.assign({},tagStats,{initialized:true,running:false,error:error.message});renderTagStatus();}}
    finally{tagBusy=false;}
  }
  const recommendationSources=['lastupdate','allvisit','postdate'].map(sort=>({sort,page:0,done:false}));
  const recommendationRotations=new Map();let recommendationFlight=null,recommendationMeta=null,recommendationRetryChange=false;
  function recommendationIdentity(){return String((accountState.profile||{}).uid||'guest');}
  function recommendationRotation(){const key=recommendationIdentity();if(!recommendationRotations.has(key)){recommendationRotations.set(key,{batch:0,shown:[]});while(recommendationRotations.size>8)recommendationRotations.delete(recommendationRotations.keys().next().value);}return recommendationRotations.get(key);}
  async function extendRecommendationPool(){
    if(recommendationFlight)return recommendationFlight;
    const sources=recommendationSources.filter(s=>!s.done);
    const flight=Promise.all(sources.map(async source=>{
      const next=source.page+1;
      try{const result=await native('feed',{sort:source.sort,page:next}),books=result.books||[];rememberBooks(books);source.page=next;const pages=Number(result.pages)||0;source.done=pages>0?next>=pages:books.length===0;return null;}catch(error){return error;}
    })).then(errors=>errors.filter(Boolean));
    recommendationFlight=flight;
    try{return await flight;}finally{if(recommendationFlight===flight)recommendationFlight=null;}
  }
  function recommendationTop(){const anchor=$('listAnchor');return Math.max(0,window.scrollY+anchor.getBoundingClientRect().bottom-12);}
  function renderRecommendationEnd(){
    $('recommendBatchNote').textContent=displayBooks.length?'看完了，可以点击上方的“换一批”。':'暂未找到合适的新书，可调整偏好或再换一批。';
    $('recommendNextBatch').disabled=listBusy;
  }
  async function loadRecommendations(changeBatch){
    if(tab!=='recommend'||listBusy)return;
    const ticket=++listTicket,identity=recommendationIdentity(),rotation=recommendationRotation(),batch=changeBatch?(rotation.batch+1)%1000001:rotation.batch;
    recommendationRetryChange=Boolean(changeBatch);listBusy=true;listFailed=false;hasMore=false;
    $('recommendNextBatch').disabled=true;$('recommendBatchNote').textContent='正在挑选故事…';visible('moreButton',false);
    if(changeBatch)window.scrollTo(0,Math.min(window.scrollY,recommendationTop()));
    if(!feed.length)skeleton();
    try{
      const select=allowReset=>window.ReaderRecommendation.select(Array.from(knownBooks.values()),state.preference,onlineFavorites(),state.history,state.learning,allBooks(),{limit:20,shown:rotation.shown,batch,identity,allowReset});
      let result=select(false),rounds=0,errors=[];
      while((result.books.length<20||recommendationSources.every(s=>s.page===0))&&recommendationSources.some(s=>!s.done)&&rounds<4){
        errors=await extendRecommendationPool();if(ticket!==listTicket||tab!=='recommend'||identity!==recommendationIdentity())return;
        result=select(false);rounds++;if(errors.length&&result.books.length<20)break;
      }
      if(!result.books.length&&errors.length)throw errors[0];
      if(!recommendationSources.some(s=>!s.done))result=select(true);
      await warmCovers(result.books);if(ticket!==listTicket||tab!=='recommend'||identity!==recommendationIdentity())return;
      const shown=new Set(result.rotationReset?[]:rotation.shown);result.books.forEach(b=>{shown.delete(String(b.id));shown.add(String(b.id));});rotation.shown=Array.from(shown).slice(-10000);rotation.batch=batch;
      feed=result.books;page=1;hasMore=false;recommendationMeta=result;renderGrid(feed);rememberList();
    }catch(error){if(ticket===listTicket){listFailed=true;if(feed.length)toast(error.message);if(!feed.length)$('grid').textContent='';$('recommendBatchNote').textContent=error.message;visible('moreButton',true);$('moreButton').textContent='重新加载';}}
    finally{if(ticket===listTicket){listBusy=false;$('recommendNextBatch').disabled=false;if(!listFailed)renderRecommendationEnd();}}
  }

  async function loadList(append){
    if(tab==='recommend')return loadRecommendations(false);
    if(tab==='discover'&&tagFilters.length){if(append)tagLimit+=30;else tagLimit=30;return loadTags(true,false);}
    if(append&&(listBusy||!hasMore||listFailed))return;
    const ticket=append?listTicket:++listTicket,requestedPage=append?page+1:1,route=tab;listBusy=true;listFailed=false;
    if(!append){page=0;feed=[];hasMore=true;autoBudget=3;skeleton();}
    $('feedStatus').textContent='正在加载书目与封面…';visible('moreButton',false);
    try{
      const result=await native(query?(searchType==='author'?'author':'search'):'feed',{query,sort,page:requestedPage});if(ticket!==listTicket||tab!==route)return;const incoming=result.books||[];rememberBooks(incoming);const ids=new Set(feed.map(b=>String(b.id)));await warmCovers(incoming);if(ticket!==listTicket||tab!==route)return;const added=incoming.filter(b=>!ids.has(String(b.id)));feed=feed.concat(added);page=requestedPage;hasMore=incoming.length>0&&!(Number(result.pages)>0&&page>=Number(result.pages));renderGrid(feed);
    }catch(error){if(ticket!==listTicket)return;listFailed=true;if(!append)$('grid').textContent='';$('feedStatus').textContent=error.message;visible('moreButton',true);$('moreButton').textContent='重新加载';}
    finally{if(ticket===listTicket){listBusy=false;if(!listFailed)$('feedStatus').textContent=hasMore?'向下滑动，自动加载更多':'已经看到最后了';scheduleMore();}}
  }
  function scheduleMore(){clearTimeout(autoTimer);if(!booted)return;autoTimer=setTimeout(()=>{if(!isList()||tab==='recommend'||reading||!foreground||listBusy||listFailed||overlayOpen())return;if($('feedSentinel').getBoundingClientRect().top>=window.innerHeight+1000)return;if(tab==='discover'&&tagFilters.length){if(!tagBusy&&(tagStats.totalMatches||0)>feed.length){tagLimit+=30;loadTags(false,false);}return;}if(!hasMore||autoBudget<=0)return;autoBudget--;loadList(true);},160);}
  function renderSearchScope(){document.querySelectorAll('[data-search-scope]').forEach(b=>{b.classList.toggle('active',b.dataset.searchScope===searchType);b.setAttribute('aria-pressed',String(b.dataset.searchScope===searchType));});$('searchInput').placeholder=searchType==='author'?'输入作者名':searchType==='tags'?'输入标签，可用空格分隔多个':'输入书名';}
  function setTab(next,reset){
    renderSearchScope();rememberList();tab=next;++listTicket;listBusy=false;listFailed=false;autoBudget=3;activeListKey=listKey(next);const saved=!reset&&listBuffers.get(activeListKey);feed=saved?saved.feed:[];page=saved?saved.page:0;hasMore=saved?saved.hasMore:true;
    $('home').classList.toggle('recommend-page',next==='recommend');visible('recommendEnd',next==='recommend');visible('feedStatus',next!=='recommend');visible('feedSentinel',next!=='recommend');visible('refreshButton',next!=='recommend');visible('recommendNextBatch',next==='recommend');if(next==='recommend')recommendationMeta=saved?saved.meta:null;visible('home',next!=='mine');visible('accountScreen',next==='mine');window.scrollTo(0,0);document.querySelectorAll('[data-tab]').forEach(b=>b.classList.toggle('active',b.dataset.tab===next));
    if(next==='mine'){renderAccount();return;}
    visible('searchForm',next==='discover');visible('filterRow',next==='discover');visible('recommendActions',next==='recommend');visible('localBanner',next==='local');visible('tagIndexPanel',next==='discover'&&tagFilters.length>0);visible('moreButton',false);$('rankSelect').disabled=Boolean(tagFilters.length);$('searchInput').value=searchType==='tags'?tagFilters.join(' '):query;
    $('sectionTitle').textContent=next==='shelf'?'我的书架':next==='local'?'本地书房':next==='recommend'?'为你推荐':tagFilters.length?'全库标签 · '+tagFilters.join(filterMode==='all'?' ＋ ':' / '):query?(searchType==='author'?'作者：'+query:'搜索结果'):$('rankSelect').selectedOptions[0].textContent;
    $('sectionEyebrow').textContent=next==='shelf'?'MY LIBRARY':next==='local'?'YOUR OWN STORIES':next==='recommend'?'FOR YOU':searchType==='author'&&query?'AUTHOR WORKS':'EXPLORE';$('refreshButton').textContent=next==='local'?'导入 ＋':next==='shelf'?'去发现 ↗':'刷新 ↻';
    $('filterNote').textContent=tagFilters.length?'先展示已核实的标签匹配，再自动补全全库书目。未完成时不代表全部结果；可暂停、续查。':'榜单和书名搜索均可自动加载更多；标签支持全库多选。';$('feedStatus').textContent='';renderContinue();
    if(next==='discover'&&tagFilters.length){tagLimit=Math.max(30,feed.length);tagStats={};renderGrid(feed);tagStartWanted=true;loadTags(true,false);}
    else if(isList()){if(feed.length){renderGrid(feed);if(next==='recommend')renderRecommendationEnd();else scheduleMore();}else loadList(false);}
    else{renderGrid(next==='local'?localBooks:allBooks());if(next==='shelf')refreshCloud(false);}
  }
  function openAuthor(name){name=String(name||'').trim();if(!name)return;closeDetail();tagFilters=[];updateFilterLabel();query=name;searchType='author';setTab('discover',true);}
  function introText(text) { const doc=new DOMParser().parseFromString(String(text||''),'text/html');return (doc.body.textContent||'').trim(); }
  function renderHeading() {
    const book=currentBook;
    $('detailRefresh').textContent=book.source==='local'?'调整目录':'刷新';
    $('bookHeading').innerHTML=cover(book)+'<div><h2>'+esc(book.title)+'</h2><p class="author">'+(book.source!=='local'&&book.author?'<button class="author-link" data-author="'+esc(book.author)+'">'+esc(book.author)+'</button>':esc(book.author||'轻小说文库'))+'</p><p class="tags">'+esc(book.tags||book.publisher||(book.source==='local'?'本地导入 · '+(book.kind||'').toUpperCase():''))+'</p><p class="tags">'+esc(book.source==='local'?'原文件按需读取':book.updated?'更新于 '+book.updated:'')+'</p></div>';
    bindImageErrors($('bookHeading'));
    $('bookTags').innerHTML=library.tags(book).map(tag=>'<button class="tag-chip" data-tag="'+esc(tag)+'" title="'+esc(window.ReaderTaxonomy.descriptions[tag]||'来源标签')+'">'+esc(tag)+'</button>').join('');
    const attitude=(state.preference.feedback||{})[String(book.id)];$('likeBook').classList.toggle('selected',attitude==='like');$('likeBook').textContent=attitude==='like'?'已喜欢':'喜欢这本';$('hideBook').classList.toggle('selected',attitude==='hide');$('hideBook').textContent=attitude==='hide'?'已标记不感兴趣':'不感兴趣';
    visible('detailCacheButton',book.source!=='local');$('detailCacheButton').disabled=!chapters.length;visible('bookFeedback',book.source!=='local');visible('cacheDetailRow',book.source!=='local');$('cacheBookButton').disabled=!chapters.length;
    const included=book.source==='local'||state.shelf.some(item=>String(item.id)===String(book.id)&&item.favorite===true);
    const cloud=(accountState.books||[]).some(b=>String(b.id)===String(book.id));$('shelfButton').textContent=included||cloud?'移出书架':accountState.signedIn?'收藏到账号书架':'收藏到书架';
  }
  function renderCatalog() {
    $('chapterCount').textContent=chapters.length+' 章';$('cacheBookButton').disabled=!chapters.length;$('detailCacheButton').disabled=!chapters.length;
    if(!chapters.length){$('catalogList').innerHTML='<p class="grid-message">暂时没有可读目录</p>';return;}
    const groups=[];chapters.forEach((chapter,index)=>{const name=chapter.volume||'全部章节',edition=chapter.edition||'main';let group=groups.find(g=>g.name===name&&g.edition===edition);if(!group){group={name,edition,items:[]};groups.push(group);}group.items.push({chapter,index});});
    const progress=state.progress[String(currentBook.id)];
    const editionNames={main:'正篇',side:'外传',web:'Web'};let html='';['main','side','web'].forEach(edition=>{const selected=groups.filter(g=>g.edition===edition);if(!selected.length)return;html+='<div class="edition-heading">'+editionNames[edition]+'</div>';html+=selected.map((group,index)=>'<details class="volume" '+((index===0||group.items.some(item=>progress&&progress.index===item.index))?'open':'')+'><summary>'+esc(group.name)+'<span>'+group.items.length+'</span></summary>'+group.items.map(item=>'<button class="chapter-button '+(progress&&progress.index===item.index?'current':'')+'" data-chapter="'+item.index+'">'+esc(item.chapter.title)+'</button>').join('')+'</details>').join('');});$('catalogList').innerHTML=html;
    $('readButton').disabled=false;$('readButton').textContent=progress?'继续阅读':'开始阅读';
  }
  async function openBook(book, immediately, targetPosition) {
    const ticket=++detailTicket;currentBook=Object.assign({},book);chapters=[];
    detailDwell=0;lastInteraction=Date.now();lastHeartbeat=Date.now();rememberHistory(book,false,true);window.ReaderProfile.view(state.learning,String(book.id),false);save();
    visible('detail',true);$('detail').querySelector('.detail-scroll').scrollTop=0;
    $('bookIntro').textContent=book.summary||'正在展开故事…';$('bookIntro').classList.remove('expanded');$('expandIntro').textContent='展开简介';
    $('catalogList').innerHTML='<div class="grid-message"><div class="spinner" style="margin:auto"></div><p style="margin-top:15px">正在整理目录</p></div>';
    $('chapterCount').textContent='正在获取';$('readButton').disabled=true;$('readButton').textContent='正在准备目录';renderHeading();
    if(book.source==='local'){
      chapters=book.chapters||[];$('bookIntro').textContent=introText(book.summary)||'这本书来自你的本地文件。正文与插图按需读取，阅读位置会保留。';renderCatalog();if(immediately&&chapters.length)startReading(undefined,targetPosition);return;
    }
    // Independent requests: a slow introduction never holds up the chapter list.
    native('metadata',{id:book.id}).then(data=>{if(ticket!==detailTicket)return;currentBook=Object.assign(currentBook,data);rememberBooks([currentBook]);rememberHistory(currentBook,false);const saved=state.shelf.find(b=>String(b.id)===String(currentBook.id));if(saved){Object.assign(saved,currentBook);save();}renderHeading();}).catch(()=>{});
    native('intro',{id:book.id}).then(data=>{if(ticket!==detailTicket)return;currentBook.summary=introText(data.summary);$('bookIntro').textContent=currentBook.summary||'故事正在等你翻开。';}).catch(()=>{if(ticket===detailTicket)$('bookIntro').textContent='简介暂时未能载入，目录可正常使用。';});
    try {
      const result=await native('catalog',{id:book.id,refresh:false});if(ticket!==detailTicket)return;
      chapters=result.chapters||[];renderCatalog();if(immediately&&chapters.length)startReading(undefined,targetPosition);
    }catch(error){if(ticket!==detailTicket)return;$('chapterCount').textContent='未能载入';$('catalogList').innerHTML='<p class="grid-message">'+esc(error.message)+'</p>';$('readButton').textContent='请刷新目录';}
  }
  function addBook(book) {
    if(book.source==='local')return;
    if(book.favorite!==true)return;if(!state.shelf.some(item=>String(item.id)===String(book.id))){state.shelf.unshift(Object.assign({},book,{addedAt:Date.now()}));state.shelf=state.shelf.slice(0,300);save();}
  }
  async function toggleShelf() {
    if(!currentBook)return;
    if(currentBook.source==='local'){
      try{const ids=currentBook.fileIds||[currentBook.id];localBooks=prepareLocal(await native('removeLocal',{ids}));delete state.progress[currentBook.id];ids.forEach(id=>delete state.progress[id]);save();closeDetail();setTab('local');toast('已移出本地书架，原文件不受影响');}catch(error){toast(error.message);}return;
    }
    if(accountState.expired){toast('账号登录已失效，请重新登录后同步书架');showAccount();return;}
    if(accountState.signedIn){
      const cloud=(accountState.books||[]).some(b=>String(b.id)===String(currentBook.id));const local=state.shelf.some(b=>String(b.id)===String(currentBook.id)&&b.favorite===true);
      if(local&&!cloud){state.shelf=state.shelf.filter(b=>String(b.id)!==String(currentBook.id));save();renderHeading();renderGrid(allBooks());return;}
      const bookId=String(currentBook.id),generation=accountGeneration;$('shelfButton').disabled=true;try{const result=await native(cloud?'accountRemove':'accountAdd',accountArgs({id:bookId}));if(generation!==accountGeneration)return;applyAccount(result);state.shelf=state.shelf.filter(b=>String(b.id)!==bookId);save();renderAccount();if(currentBook)renderHeading();renderContinue();if(tab==='shelf')renderGrid(allBooks());toast(result.warning||(cloud?'已从账号书架移出':'已收藏并同步到 Wenku8'));}catch(error){toast(error.message);}finally{$('shelfButton').disabled=false;}return;
    }
    const index=state.shelf.findIndex(item=>String(item.id)===String(currentBook.id));
    if(index>=0&&state.shelf[index].favorite===true){state.shelf.splice(index,1);toast('已移出书架');}else{if(index>=0)state.shelf[index].favorite=true;else addBook(Object.assign({},currentBook,{favorite:true}));toast('已收藏到书架');}save();renderHeading();renderContinue();if(tab==='shelf')renderGrid(allBooks());
  }
  function closeDetail(){++detailTicket;visible('detail',false);currentBook=null;chapters=[];renderContinue();if(tab==='shelf')renderGrid(allBooks());else if(tab==='recommend')renderGrid(feed);}
  async function importFiles(folder){
    $('importButton').disabled=true;$('importButton').textContent='正在导入…';
    try{const result=await native(folder?'importFolder':'import',{});if(result.localBooks){localBooks=prepareLocal(result.localBooks);setTab('local');const count=(result.books||[]).length;if(count)toast('已导入 '+count+' 个文件，已按作品整理');if(result.errors&&result.errors.length)toast(result.errors[0]);}}
    catch(error){toast(error.message);}finally{$('importButton').disabled=false;$('importButton').textContent='导入书籍';}
  }
  function startReading(index, targetPosition){
    if(!currentBook||!chapters.length)return;
    readerBook=Object.assign({},currentBook);readerChapters=chapters.slice();rememberHistory(readerBook,true);reading=true;lastHeartbeat=Date.now();lastInteraction=Date.now();
    $('readerBookTitle').textContent=readerBook.title;visible('reader',true);if(readerControls)readerControls.set(false,true);applySettings(false);
    const progress=targetPosition||state.progress[String(readerBook.id)];let chosen=typeof index==='number'?index:progress?progress.index:0;
    if(typeof index!=='number'&&progress&&(progress.chapterId||progress.chapterKey)){const found=readerChapters.findIndex(c=>String(c.key||c.id)===String(progress.chapterKey)||progress.chapterId&&Number(c.id)===Number(progress.chapterId));if(found>=0)chosen=found;}
    currentIndex=Math.max(0,Math.min(readerChapters.length-1,chosen));
    loadChapter(currentIndex,typeof index==='number'?0:progress?progress.ratio:0,typeof index==='number'?null:progress&&progress.anchor,progress&&progress.imageHints);
  }
  function imageFigure(url,options) {
    options=options||{};
    const figure=document.createElement('figure'),image=document.createElement('img'),caption=document.createElement('figcaption');
    image.dataset.src=url.replace(/^http:\/\//,'https://');image.alt='书中插图';image.decoding='async';image.classList.add('image-pending');if(readingLocation)readingLocation.reserve(figure,image.dataset.src);else figure.style.minHeight='220px';caption.textContent='轻点查看插图';
    image.onload=()=>{if(!reading||!figure.isConnected)return;if(readingLocation)readingLocation.note(image.dataset.src,image.naturalWidth,image.naturalHeight);const change=()=>{image.classList.remove('image-pending');image.dataset.failed='';if(options.inlineContext&&image.naturalWidth<=64&&image.naturalHeight<=64){image.className='reader-inline-symbol';image.alt=options.alt||'注';if(imageObserver)imageObserver.unobserve(figure);figure.replaceWith(image);if(readingLocation)readingLocation.index();}else{figure.style.minHeight='';const title=(readerChapters[currentIndex]||{}).title||'';if(!/封面|卷首|彩页|插图|cover|illustration/i.test(title)&&((image.naturalWidth<=900&&image.naturalHeight<=360)||(image.naturalWidth/image.naturalHeight>=2.5&&image.naturalHeight<=600)||(image.naturalWidth<=320&&image.naturalHeight<=480)))figure.classList.add('reader-small-image');}if(settings().mode==='page')measurePage();};if(readingLocation)readingLocation.mutate(change);else change();updatePosition();if(!loading)rememberPosition();};
    image.onerror=()=>{if(image.hasAttribute('src')){image.dataset.failed='1';caption.textContent='图片未能载入，轻点重试';}};
    figure.appendChild(image);figure.appendChild(caption);figure.onclick=()=>{if(pageMotion&&pageMotion.suppressImages)return;if(image.dataset.failed==='1'){image.src=image.dataset.src;caption.textContent='正在读取图片…';}else openImage(image.dataset.src);};
    return figure;
  }
  function resourcePath(bookId,base,relative){
    try{if(relative.startsWith('data:image/'))return relative;const target=new URL(relative,new URL(base,'https://local.invalid/'));if(target.origin!=='https://local.invalid')return '';return origin+'/local/'+encodeURIComponent(bookId)+target.pathname.split('/').map(part=>encodeURIComponent(decodeURIComponent(part))).join('/');}catch(error){return '';}
  }
  function cleanEpub(payload){
    const documentSource=new DOMParser().parseFromString(payload.html,'text/html'),fragment=document.createDocumentFragment(),epubStyle=window.ReaderEpubPresentation,rules=epubStyle.rules((payload.styles||'')+'\n'+Array.from(documentSource.querySelectorAll('style')).map(n=>n.textContent).join('\n'));
    const allowed=new Set(['P','DIV','SECTION','H1','H2','H3','H4','SPAN','EM','STRONG','I','B','U','S','SUP','SUB','RUBY','RT','RP','BR','HR','UL','OL','LI','BLOCKQUOTE','TABLE','THEAD','TBODY','TR','TD','TH','FIGURE','FIGCAPTION','A']);
    function copy(node,parent){
      if(node.nodeType===3){parent.appendChild(document.createTextNode(node.nodeValue));return;}
      if(node.nodeType!==1)return;const tag=node.tagName.toUpperCase();
      if(tag==='SVG'){Array.from(node.querySelectorAll('image')).forEach(child=>{const address=resourcePath(payload.bookId,payload.entry,child.getAttribute('href')||child.getAttribute('xlink:href')||'');if(address)parent.appendChild(imageFigure(address,{inlineContext:Boolean(node.closest('p,li,dd,dt'))}));});return;}
      if(['SCRIPT','STYLE','IFRAME','OBJECT','EMBED','FORM','INPUT','BUTTON','LINK','META','SVG'].includes(tag))return;
      if(tag==='IMG'){const address=resourcePath(payload.bookId,payload.entry,node.getAttribute('src')||'');if(address){if(epubStyle.inlineImage(node,readingLocation&&readingLocation.dimensions(address))){const img=document.createElement('img');img.className='reader-inline-symbol';img.alt=node.getAttribute('alt')||'注';img.decoding='async';img.src=address;parent.appendChild(img);}else parent.appendChild(imageFigure(address,{inlineContext:Boolean(node.closest('p,li,dd,dt')),alt:node.getAttribute('alt')}));}return;}
      const target=allowed.has(tag)?document.createElement(tag.toLowerCase()):document.createElement('span');
      if(node.id)target.id='epub-'+node.id;epubStyle.style(node,target,rules);
      ['text-align','font-weight','font-style','text-decoration','vertical-align'].forEach(property=>{const value=node.style.getPropertyValue(property);if(value&&!/url|expression/i.test(value))target.style.setProperty(property,value);});
      if(tag==='A'&&String(node.getAttribute('href')||'').startsWith('#'))target.setAttribute('href','#epub-'+node.getAttribute('href').slice(1));
      Array.from(node.childNodes).forEach(child=>copy(child,target));parent.appendChild(target);
    }
    let source=documentSource.body;
    if(payload.from||payload.to){
      const from=payload.from?documentSource.getElementById(payload.from):null,to=payload.to?documentSource.getElementById(payload.to):null;
      if(payload.from&&!from&&payload.strictFrom)throw new Error('目录指向的页内位置不存在');
      if(payload.to&&!to&&payload.strictTo)throw new Error('下一章的页内位置不存在，无法安全分章');
      const range=documentSource.createRange();range.selectNodeContents(documentSource.body);if(from)range.setStartBefore(from);if(to)range.setEndBefore(to);source=range.cloneContents();
    }
    const container=document.createElement('section');epubStyle.style(documentSource.body,container,rules);Array.from(source.childNodes).forEach(node=>copy(node,container));fragment.appendChild(container);return fragment;
  }
  const frame=()=>new Promise(resolve=>{let done=false;const finish=()=>{if(!done){done=true;resolve();}};setTimeout(finish,60);requestAnimationFrame(finish);});
  async function renderContent(payload,ticket){
    const article=$('readerArticle');article.textContent='';
    if(payload.format==='epub'){const parts=payload.parts||[payload];for(const part of parts){if(ticket!==chapterTicket||!reading)return;article.appendChild(cleanEpub(Object.assign({},part,{bookId:payload.bookId})));await frame();}return;}
    const title=readerChapters[currentIndex].title,heading=document.createElement('h2');heading.textContent=title;article.appendChild(heading);
    const lines=String(payload.text||'').replace(/\r\n/g,'\n').split('\n');let count=0;
    for(let index=0;index<lines.length;index++){
      if(ticket!==chapterTicket||!reading)return;
      const line=lines[index].trim();if(!line||(index<3&&line===title))continue;
      const matches=[];const expression=/<!--image-->\s*(.*?)\s*<!--image-->/g;let match;while((match=expression.exec(line))!==null)matches.push(match);
      if(matches.length)matches.forEach(match=>article.appendChild(imageFigure(match[1])));
      else{const paragraph=document.createElement('p');paragraph.textContent=line;article.appendChild(paragraph);}
      if(++count%120===0){await frame();}
    }
    
  }
  function observeImages(){
    if(imageObserver)imageObserver.disconnect();const figures=Array.from($('readerArticle').querySelectorAll('figure')).filter(f=>f.querySelector('img[data-src]'));
    if(!window.IntersectionObserver){figures.forEach(f=>{const img=f.querySelector('img');img.src=img.dataset.src;});return;}
    imageObserver=new IntersectionObserver(entries=>entries.forEach(entry=>{const figure=entry.target,image=figure.querySelector('img[data-src]');if(!image)return;
      if(entry.isIntersecting){if(!image.getAttribute('src'))image.src=image.dataset.src;}
      else if(image.getAttribute('src')&&image.naturalWidth){const height=figure.getBoundingClientRect().height;const change=()=>{figure.style.minHeight=Math.max(1,height)+'px';image.classList.add('image-pending');image.removeAttribute('src');};if(readingLocation)readingLocation.mutate(change);else change();}
    }),{root:$('readerViewport'),rootMargin:'240px'});figures.forEach(figure=>imageObserver.observe(figure));
  }
  function memoryKey(chapter,index){return readerBook.source+':'+String(chapter.fileId||readerBook.id)+':'+String(chapter.key||chapter.id)+':'+index;}
  function keepChapter(key,payload){
    const size=2*(String(payload.text||'').length+String(payload.html||'').length+String(payload.styles||'').length+(payload.parts||[]).reduce((n,p)=>n+String(p.html||'').length+String(p.styles||'').length,0))+4096;
    if(size>6*1024*1024)return;if(chapterMemory.has(key)){chapterMemoryBytes-=chapterMemory.get(key).size;chapterMemory.delete(key);}chapterMemory.set(key,{payload,size});chapterMemoryBytes+=size;
    while(chapterMemory.size>2||chapterMemoryBytes>6*1024*1024){const first=chapterMemory.keys().next().value;chapterMemoryBytes-=chapterMemory.get(first).size;chapterMemory.delete(first);}
  }
  async function loadChapter(index,position,anchor,imageHints){
    if(pageMotion)pageMotion.reset();
    if(index<0||index>=readerChapters.length)return;
    if(reading&&currentIndex!==index&&!loading)rememberPosition();
    selectedQuote=null;const ticket=++chapterTicket;currentIndex=index;loading=true;if(readingLocation)readingLocation.reset(imageHints||[]);$('reader').dataset.ready='0';visible('readerLoading',!chapterMemory.has(memoryKey(readerChapters[index],index)));visible('readerError',false);
    if(imageObserver)imageObserver.disconnect();$('readerArticle').textContent='';$('readerViewport').scrollTop=0;$('readerViewport').scrollLeft=0;
    $('readerChapterTitle').textContent=readerChapters[index].title;$('readerPageNumber').textContent='准备中';$('readerChapterProgress').textContent='';$('readerProgressBar').style.transform='scaleX(0)';updateButtons();
    try{
      const chapter=readerChapters[index],key=memoryKey(chapter,index),recent=chapterMemory.get(key),payload=recent?recent.payload:await native(readerBook.source==='local'?'localChapter':'chapter',{id:chapter.fileId||readerBook.id,index:chapter.fileIndex==null?index:chapter.fileIndex,chapterId:Number(chapter.id)});
      if(ticket!==chapterTicket||!reading)return;
      $('readerChapterTitle').textContent=chapter.title+(payload.cached?' · 已缓存':'');
      await renderContent(payload,ticket);if(ticket!==chapterTicket||!reading)return;
      measurePage();readingLocation.index();await frame();await frame();if(ticket!==chapterTicket||!reading)return;measurePage();if(!anchor||!readingLocation.restore(anchor)){restoreRatio(position||0);const initial=readingLocation.capture();if(initial)readingLocation.restore(initial);}observeImages();loading=false;lastHeartbeat=Date.now();lastInteraction=Date.now();keepChapter(key,payload);$('reader').dataset.ready='1';visible('readerLoading',false);updatePosition();rememberPosition();
    }catch(error){if(ticket!==chapterTicket||!reading)return;loading=false;visible('readerLoading',false);visible('readerError',true);$('readerError').innerHTML='<p>'+esc(error.message)+'</p><button class="primary" id="retryChapter">重新读取</button>';$('retryChapter').onclick=()=>loadChapter(currentIndex,position||0,anchor,imageHints);}
    updateButtons();
  }
  function pageWidth(){return pagination?pagination.width():Math.max(1,$('readerViewport').getBoundingClientRect().width);}
  function measurePage(){if(pagination)pagination.measure();}
  function pages(){return pagination?pagination.pages():1;}
  function ratio(){const viewport=$('readerViewport');return viewport.classList.contains('mode-page')?Math.min(1,viewport.scrollLeft/Math.max(1,(pages()-1)*pageWidth())):Math.min(1,viewport.scrollTop/Math.max(1,viewport.scrollHeight-viewport.clientHeight));}
  function restoreRatio(value){const viewport=$('readerViewport');value=Math.max(0,Math.min(1,value));if(settings().mode==='page')viewport.scrollLeft=Math.round(value*(pages()-1))*pageWidth();else viewport.scrollTop=value*Math.max(0,viewport.scrollHeight-viewport.clientHeight);updatePosition();}
  function updatePosition(){if(!reading||loading)return;const view=$('readerViewport'),fraction=ratio(),paged=settings().mode==='page',total=paged?pages():Math.max(1,Math.ceil(view.scrollHeight/Math.max(1,view.clientHeight))),current=paged?Math.min(total,Math.round(view.scrollLeft/pageWidth())+1):fraction>=.999?total:Math.min(total,Math.floor(view.scrollTop/Math.max(1,view.clientHeight))+1);$('readerPageNumber').textContent=current+' / '+total+' 页';const shown=paged?current/total:total===1?1:fraction;$('readerChapterProgress').textContent=Math.round(shown*100)+'%';$('readerProgressBar').style.transform='scaleX('+shown+')';$('readerPosition').textContent=(currentIndex+1)+' / '+readerChapters.length+' 章';}

  function rememberPosition(){if(!reading||!readerBook||loading||pageMotion&&(pageMotion.busy||pageMotion.dragging))return;const id=String(readerBook.id),chapter=readerChapters[currentIndex],cid=readerBook.source==='local'?0:Number(chapter.id)||0;state.progress[id]={index:currentIndex,chapterKey:String(chapter.key||chapter.id),chapterId:cid,chapterTitle:chapter.title,ratio:ratio(),anchor:readingLocation&&readingLocation.capture(),imageHints:readingLocation?readingLocation.getHints():[],time:Date.now()};save();}

  function updateButtons(){$('prevButton').disabled=loading;$('nextButton').disabled=loading;}
  async function turn(direction,smooth=true,origin){
    if(!reading||loading||pageMotion&&pageMotion.busy)return;if(readingLocation)readingLocation.release();const viewport=$('readerViewport'),ticket=chapterTicket;
    if(settings().mode==='page'){
      const current=origin==null?Math.round(viewport.scrollLeft/pageWidth()):origin,target=current+direction;
      if(target>=0&&target<pages()){if(await pageMotion.scroll(target*pageWidth(),smooth)){if(!reading||ticket!==chapterTicket)return;updatePosition();rememberPosition();}return;}
      const next=currentIndex+direction;
      if(next>=0&&next<readerChapters.length){if(!await pageMotion.cross(direction,smooth)||!reading||ticket!==chapterTicket)return;pageMotion.reset();await loadChapter(next,direction<0?1:0);}
      else{await pageMotion.rebound();if(reading)toast(direction<0?'已经是第一章':'已经读到最后一章');}return;
    }
    const max=viewport.scrollHeight-viewport.clientHeight;
    if(direction>0&&viewport.scrollTop<max-5){viewport.scrollTop=Math.min(max,viewport.scrollTop+viewport.clientHeight*.87);updatePosition();rememberPosition();return;}
    if(direction<0&&viewport.scrollTop>5){viewport.scrollTop=Math.max(0,viewport.scrollTop-viewport.clientHeight*.87);updatePosition();rememberPosition();return;}
    const index=currentIndex+direction;if(index>=0&&index<readerChapters.length)loadChapter(index,direction<0?1:0);else toast(direction<0?'已经是第一章':'已经读到最后一章');
  }
  function closeReader(){if(readerControls)readerControls.set(false,true);chapterMemory.clear();chapterMemoryBytes=0;if(pageMotion)pageMotion.reset();heartbeat();rememberPosition();flush();reading=false;syncSystemUi();++chapterTicket;loading=false;if(imageObserver)imageObserver.disconnect();$('readerArticle').textContent='';visible('reader',false);renderContinue();if(currentBook)renderCatalog();scheduleMore();}
  function showSettings(){if(reading&&readerControls)readerControls.set(true);visible('sheetBackdrop',true);visible('settingsSheet',true);applySettings(false);native('storage',{}).then(result=>$('storageLabel').textContent='手动缓存 '+cacheSize(result.manualCacheBytes||0)).catch(()=>{});}
  function hideSettings(){visible('sheetBackdrop',false);visible('settingsSheet',false);}
  let imageScale=1,imageX=0,imageY=0,imagePoints=new Map(),pinchDistance=0,pinchScale=1;
  function imageTransform(){const image=$('imageStage').querySelector('img');if(image)image.style.transform='translate('+imageX+'px,'+imageY+'px) scale('+imageScale+')';}
  function openImage(url){imageScale=1;imageX=0;imageY=0;imagePoints.clear();$('imageStage').textContent='';const image=document.createElement('img');image.src=url;image.alt='插图';image.onerror=()=>toast('图片暂时未能读取，请稍后重试');$('imageStage').appendChild(image);visible('imageViewer',true);}
  function closeImage(){visible('imageViewer',false);$('imageStage').textContent='';imagePoints.clear();}
  function back(){if(!$('updateNoticeSheet').classList.contains('hidden')){aboutUi.deferNotice();return true;}if(!$('updateScreen').classList.contains('hidden')){visible('updateScreen',false);return true;}if(!$('licensesScreen').classList.contains('hidden')){visible('licensesScreen',false);return true;}if(!$('aboutScreen').classList.contains('hidden')){visible('aboutScreen',false);return true;}if(!$('imageViewer').classList.contains('hidden')){closeImage();return true;}if(!$('illustrationScreen').classList.contains('hidden')){illustrations.close();return true;}if(!$('bookmarkScreen').classList.contains('hidden')){visible('bookmarkScreen',false);return true;}if(!$('localOrderScreen').classList.contains('hidden'))return localOrderEditor.close();if(!$('imageViewer').classList.contains('hidden')){closeImage();return true;}for(const id of ['profileScreen','historyScreen','cacheScreen','tagFilterSheet','preferencesSheet'])if(!$(id).classList.contains('hidden')){visible(id,false);lastHeartbeat=Date.now();scheduleMore();return true;}if(!$('settingsSheet').classList.contains('hidden')){hideSettings();return true;}if(reading){if(readerControls&&readerControls.opened){readerControls.close();return true;}closeReader();return true;}if(!$('detail').classList.contains('hidden')){closeDetail();scheduleMore();return true;}if(tab==='discover'&&(query||tagFilters.length)){query='';searchType='title';tagFilters=[];updateFilterLabel();$('searchInput').value='';setTab('discover',true);return true;}if(tab!=='discover'){setTab('discover');return true;}return false;}
  function heartbeat(){
    const now=Date.now(),seconds=Math.min(20,Math.max(0,(now-lastHeartbeat)/1000));lastHeartbeat=now;if(!booted||!foreground||document.hidden)return;
    if(reading&&!loading&&readerBook&&now-lastInteraction<90000&&$('imageViewer').classList.contains('hidden')&&$('settingsSheet').classList.contains('hidden')&&$('cacheScreen').classList.contains('hidden')&&$('illustrationScreen').classList.contains('hidden')&&$('bookmarkScreen').classList.contains('hidden')&&Array.from($('readerArticle').querySelectorAll('p')).reduce((sum,p)=>sum+p.textContent.length,0)>100){window.ReaderProfile.read(state.learning,String(readerBook.id),seconds,now);save();}
    else if(!reading&&currentBook&&!$('detail').classList.contains('hidden')){detailDwell+=seconds;if(detailDwell>=10){window.ReaderProfile.view(state.learning,String(currentBook.id),true,now);save();}}
  }
  function setForeground(value){if(!value){if(pageMotion)pageMotion.reset();heartbeat();}foreground=Boolean(value);lastHeartbeat=Date.now();if(value){lastInteraction=Date.now();if(booted){refreshCloud(false);scheduleMore();if(aboutUi)aboutUi.autoCheck();}}else{if(aboutUi)aboutUi.pauseAuto();flush();}}
  function profileResult(){return window.ReaderProfile.calculate(Array.from(knownBooks.values()).concat(state.history,localBooks),state.preference,onlineFavorites(),state.learning);}
  function renderProfile(){
    const result=profileResult();$('profileSummary').innerHTML='<div><strong>'+(result.totalSeconds<600?(result.totalSeconds/60).toFixed(1):Math.floor(result.totalSeconds/60))+'</strong><span>近30天有效阅读分钟</span></div><div><strong>'+result.records.filter(b=>b.seconds>=90).length+'</strong><span>有效阅读作品</span></div>';
    const max=result.tags.length?result.tags[0].weight:1;$('profileTags').innerHTML=result.tags.length?result.tags.slice(0,30).map(t=>'<button class="profile-tag" data-profile-tag="'+esc(t.tag)+'"><span>'+esc(t.tag)+'</span><span class="interest-bar"><i style="width:'+Math.round(t.weight/max*100)+'%"></i></span><small>'+t.weight.toFixed(2)+'</small></button>').join(''):'<p class="profile-help">还没有足够的兴趣记录。阅读一会儿，或选择几个喜欢的标签。</p>';
    const labels={browse:'浏览',read:'有效阅读',returnRead:'回访阅读',sustained:'持续阅读',favorite:'收藏',like:'喜欢'};
    $('profileBooks').innerHTML=result.records.slice(0,100).map(book=>'<button class="profile-book" data-profile-book="'+esc(book.id)+'"><strong>'+esc(book.title)+'</strong><small>'+Object.keys(book.weights).filter(k=>book.weights[k]>0).map(k=>labels[k]+' '+book.weights[k].toFixed(2)).join(' · ')+'</small><span>'+(book.hidden?'已排除':book.score.toFixed(2)+' 分')+' · 阅读 '+Math.floor(book.seconds/60)+' 分钟</span></button>').join('');$('profileTagDetail').textContent='';
  }
  let accountGeneration=0;
  function applyAccount(result){const prior=recommendationIdentity();accountState=result;if(prior!==recommendationIdentity()){listBuffers.delete('recommend');recommendationMeta=null;if(booted&&tab==='recommend')setTab('recommend',true);}renderContinue();}
  function historyEntries(){const books=new Map();historyBooks().forEach(b=>{const id=String(b.id),old=books.get(id)||{};books.set(id,Object.assign({},b,old,state.history.find(h=>String(h.id)===id)||{}));});return Array.from(books.values()).filter(b=>{const p=state.progress[String(b.id)];return historyMode==='position'?Boolean(p):Boolean(b.viewedAt||b.seenAt||state.history.some(h=>String(h.id)===String(b.id)));}).sort((a,b)=>{const stamp=b=>historyMode==='position'?(state.progress[String(b.id)]||{}).time||0:b.viewedAt||b.seenAt||(state.progress[String(b.id)]||{}).time||0;return stamp(b)-stamp(a);});}
  function renderHistory(){
    const entries=historyEntries();document.querySelectorAll('[data-history-mode]').forEach(b=>b.classList.toggle('active',b.dataset.historyMode===historyMode));
    $('historyList').innerHTML=entries.length?entries.map(book=>{const p=state.progress[String(book.id)],stamp=historyMode==='position'?p&&p.time:book.viewedAt||book.seenAt||p&&p.time;const position=p?esc(p.chapterTitle||('第 '+((p.index||0)+1)+' 章'))+' · '+Math.round((p.ratio||0)*100)+'%':'仅浏览 · 尚未开始阅读';return '<button class="history-row" data-history-id="'+esc(book.id)+'"><span class="history-text"><strong>'+esc(book.title)+'</strong><small>'+position+'</small><small>'+esc(stamp?new Date(stamp).toLocaleString():'旧版浏览记录 · 无时间')+'</small></span><span>›</span></button>';}).join(''):'<p class="grid-message">'+(historyMode==='position'?'还没有阅读位置记录。':'还没有浏览记录。打开的故事会留在这里。')+'</p>';
  }
  function renderAccount(){
    const profile=accountState.profile||{},named=profile.nickname||profile.uname||'';
    $('accountSummary').innerHTML=named?'<h3>'+esc(named)+'</h3><p class="profile-help">'+(accountState.expired||accountState.requiresApiLogin?'请重新登录':accountState.signedIn?'已登录 Wenku8':'未登录')+' · '+(accountState.books||[]).length+' 本账号收藏</p>':'<h3>自己的阅读角落</h3><p class="profile-help">历史与画像，无需登录。</p>';
    visible('accountLogin',!accountState.signedIn);visible('accountRegister',!accountState.signedIn);$('accountLogin').textContent=accountState.requiresApiLogin?'重新登录 Wenku8':'登录 Wenku8';visible('accountLogout',Boolean(profile.uid));visible('accountUpload',accountState.signedIn&&state.shelf.some(b=>b.favorite===true));
    $('accountRefresh').disabled=!accountState.signedIn||accountBusy;
    ['accountLogin','accountRegister','accountUpload','accountLogout'].forEach(id=>$(id).disabled=accountBusy);
    const count=new Set(state.history.map(b=>String(b.id))).size;$('historyCount').textContent=count+' 本作品 · 无需登录';
  }
  function showAccount(){setTab('mine');}
  function accountArgs(extra){return Object.assign({expectedUid:(accountState.profile||{}).uid||''},extra||{});}
  async function refreshCloud(force){if(!force&&accountState.verificationRequired)return;if(!booted||!accountState.signedIn||accountBusy||!foreground||(!force&&Date.now()-lastCloudCheck<45000))return;const generation=accountGeneration;accountBusy=true;lastCloudCheck=Date.now();renderAccount();try{const result=await native('accountPull',accountArgs());if(generation!==accountGeneration)return;applyAccount(result);rememberBooks(onlineFavorites());renderAccount();if(tab==='shelf'&&!reading&&$('detail').classList.contains('hidden'))renderGrid(allBooks());if(currentBook)renderHeading();$('accountMessage').textContent='已同步 · '+new Date(result.syncedAt||Date.now()).toLocaleTimeString();}catch(error){if(generation===accountGeneration){$('accountMessage').textContent=error.message;const wasExpired=Boolean(accountState.expired);try{applyAccount(await native('accountStatus',{}));}catch(ignored){}if(force||accountState.expired&&!wasExpired)toast(error.message);}}finally{if(generation===accountGeneration){accountBusy=false;renderAccount();}}}
  async function loginAccount(){if(accountBusy)return;const generation=++accountGeneration;accountBusy=true;renderAccount();try{const result=await native('accountLogin',{});if(generation!==accountGeneration||!result.profile)return;applyAccount(result);lastCloudCheck=Date.now();rememberBooks(onlineFavorites());$('accountMessage').textContent=result.warning||'已登录并同步账号书架';if(tab==='shelf')renderGrid(allBooks());renderContinue();}catch(error){$('accountMessage').textContent=error.message;}finally{if(generation===accountGeneration){accountBusy=false;renderAccount();}}}
  function cacheSize(bytes){return (Number(bytes||0)/1048576).toFixed(1)+' MiB';}
  function renderCache(){
    $('cacheBytes').textContent=cacheSize(cacheState.bytes);$('cacheUsage').textContent=cacheSize(cacheState.bytes)+' · '+(cacheState.books||[]).length+' 本作品';$('cacheLimit').value=String(cacheState.limitMiB||200);
    const busy=Boolean(cacheState.running);visible('cacheTask',Boolean(cacheState.total||cacheState.message));visible('cacheCancel',busy);$('cacheStart').disabled=busy||!cacheTarget||!cacheChapters.length;$('cacheLimit').disabled=busy;$('cacheClear').disabled=busy;
    $('cacheTaskTitle').textContent=cacheState.title||'缓存任务';$('cacheProgress').value=cacheState.total?Math.round(cacheState.done/cacheState.total*100):0;$('cacheTaskMessage').textContent=(cacheState.message||'')+(cacheState.total?' · '+cacheState.done+'/'+cacheState.total+' 章':'')+(cacheState.failed?' · '+cacheState.failed+' 章未完成':'');
    $('cacheList').innerHTML=(cacheState.books||[]).length?cacheState.books.map(book=>'<div class="cache-book"><strong>'+esc(book.title)+'</strong><p>正文 '+book.cachedChapters+' / '+book.totalChapters+' 章 · 插图完整 '+book.completeChapters+' 章 · '+cacheSize(book.cacheBytes)+'</p><div><button class="secondary small" data-cache-read="'+esc(book.id)+'">打开阅读</button><button class="text-button" data-cache-delete="'+esc(book.id)+'" '+(busy?'disabled':'')+'>清理</button></div></div>').join(''):'<p class="grid-message">还没有手动缓存的作品。可在书籍详情选择“缓存阅读”。</p>';
  }
  async function refreshCache(quiet){if(cachePolling)return;cachePolling=true;try{cacheState=await native('cacheStatus',{});renderCache();}catch(error){if(!quiet)toast(error.message);}finally{cachePolling=false;}}
  function showCache(book,list){
    heartbeat();
    cacheTarget=book&&book.source!=='local'?Object.assign({},book):null;cacheChapters=list||[];visible('cacheSelection',Boolean(cacheTarget));
    if(cacheTarget){$('cacheBookTitle').textContent=cacheTarget.title;const volumes=Array.from(new Set(cacheChapters.map(c=>c.volume||'全部章节'))),sameReader=reading&&readerBook&&String(readerBook.id)===String(cacheTarget.id),progress=state.progress[String(cacheTarget.id)]||{},choice=(sameReader?readerChapters[currentIndex]:cacheChapters.find(c=>String(c.key||c.id)===String(progress.chapterKey)||progress.chapterId&&Number(c.id)===Number(progress.chapterId)))||cacheChapters[0];$('cacheVolume').innerHTML='<option value="">整本 · '+cacheChapters.length+' 章</option>'+volumes.map(v=>'<option value="'+esc(v)+'">'+esc(v)+' · '+cacheChapters.filter(c=>(c.volume||'全部章节')===v).length+' 章</option>').join('');if(choice)$('cacheVolume').insertAdjacentHTML('beforeend','<option value="@chapter:'+esc(choice.id)+'">'+(sameReader||progress.chapterKey?'仅上次阅读章':'仅第一章')+' · '+esc(choice.title)+'</option>');if(choice)$('cacheVolume').value=choice.volume||'全部章节';}

    renderCache();visible('cacheScreen',true);refreshCache(true);
  }
  function captureSelection(){if(!reading||loading)return false;const selection=window.getSelection();if(!selection||!selection.rangeCount||!String(selection).trim())return false;const range=selection.getRangeAt(0);if(!$('readerArticle').contains(range.commonAncestorContainer))return false;const chapter=readerChapters[currentIndex];selectedQuote={quote:String(selection).trim().slice(0,4000),bookId:String(readerBook.id),source:readerBook.source,bookTitle:readerBook.title,author:readerBook.author||'',cover:readerBook.cover||'',chapterTitle:chapter.title,chapterKey:String(chapter.key||chapter.id),chapterId:readerBook.source==='local'?0:Number(chapter.id)||0,index:currentIndex,ratio:ratio(),anchor:readingLocation.fromRange(range)};return true;}
  function selectionAction(action){captureSelection();if(!reading)return false;if(action==='illustrations'){illustrations.open({book:Object.assign({},readerBook),chapters:readerChapters.slice(),index:currentIndex});return true;}if(!selectedQuote){toast('请先长按选择文字');return false;}if(action==='copy')native('copyText',{text:selectedQuote.quote}).then(()=>toast('已复制')).catch(e=>toast(e.message));if(action==='bookmark')bookmarkUi.add(Object.assign({},selectedQuote));return true;}
  window.App={back,flush,setForeground,captureSelection,selectionAction};

  // Bind once: no inline handlers or remote scripts are permitted.
  $('searchGlyph').innerHTML=icon('search');$('detailBack').innerHTML=icon('back');$('readerBack').innerHTML=icon('back');$('settingsClose').innerHTML=icon('close');$('readerSettings').innerHTML=icon('settings');$('catalogGlyph').innerHTML=icon('catalog');
  document.querySelectorAll('[data-icon]').forEach(element=>element.innerHTML=icon(element.dataset.icon));
  document.querySelectorAll('[data-tab]').forEach(button=>button.onclick=()=>setTab(button.dataset.tab));
  $('rankSelect').onchange=()=>{sort=$('rankSelect').value;query='';searchType='title';$('searchInput').value='';$('filterNote').textContent='榜单和书名搜索可自动加载更多；标签支持全库筛选。';setTab('discover',true);};
  function renderTagFilters(){$('tagFilterGroups').innerHTML=window.ReaderTaxonomy.groups.map(group=>'<section class="preference-group"><h3>'+esc(group.name)+'</h3>'+group.tags.filter(tag=>!$('tagTerm').value||tag.toLowerCase().includes($('tagTerm').value.trim().toLowerCase())).map(tag=>'<button class="tag-chip '+(filterDraft.includes(tag)?'like':'')+'" data-filter-tag="'+esc(tag)+'">'+esc(tag)+'</button>').join('')+'</section>').join('');document.querySelectorAll('[data-filter-mode]').forEach(button=>button.classList.toggle('active',button.dataset.filterMode===filterDraftMode));}
  function updateFilterLabel(){$('tagFilter').textContent=tagFilters.length?tagFilters.slice(0,2).join('、')+(tagFilters.length>2?' +'+(tagFilters.length-2):''):'全部标签 · 多选';}
  $('tagFilter').onclick=()=>{filterDraft=tagFilters.slice();filterDraftMode=filterMode;$('tagTerm').value='';renderTagFilters();visible('tagFilterSheet',true);};$('tagFilterClose').onclick=()=>{visible('tagFilterSheet',false);scheduleMore();};$('tagFilterClear').onclick=()=>{filterDraft=[];renderTagFilters();};
  $('tagFilterGroups').onclick=event=>{const button=event.target.closest('[data-filter-tag]');if(!button)return;const tag=button.dataset.filterTag;filterDraft=filterDraft.includes(tag)?filterDraft.filter(t=>t!==tag):filterDraft.concat(tag);renderTagFilters();};document.querySelectorAll('[data-filter-mode]').forEach(button=>button.onclick=()=>{filterDraftMode=button.dataset.filterMode;renderTagFilters();});
  $('tagFilterApply').onclick=()=>{searchType='tags';tagFilters=filterDraft.slice();filterMode=filterDraftMode;updateFilterLabel();visible('tagFilterSheet',false);setTab('discover',true);};
  document.querySelectorAll('[data-search-scope]').forEach(button=>button.onclick=()=>{searchType=button.dataset.searchScope;tagFilters=[];query='';updateFilterLabel();renderSearchScope();});
  $('tagTerm').oninput=renderTagFilters;$('searchForm').onsubmit=event=>{event.preventDefault();const text=$('searchInput').value.trim();if(searchType==='tags'||/^[#＃]/.test(text)){if(!text){toast('请输入标签，或使用下方多选标签');return;}searchType='tags';tagFilters=Array.from(new Set(text.replace(/＃/g,'#').split(/[#,，、;；\s]+/).filter(Boolean)));query='';}else{tagFilters=[];query=text;}updateFilterLabel();setTab('discover',true);$('searchInput').blur();};

  $('refreshButton').onclick=()=>{if(tab==='recommend')return;if(tab==='local')importFiles();else if(tab==='shelf')setTab('discover');else if(tab==='discover'&&tagFilters.length){tagLimit=30;loadTags(true,true);}else loadList(false);};
  $('moreButton').onclick=()=>{listFailed=false;if(tab==='recommend')loadRecommendations(recommendationRetryChange);else loadList(page>0);};$('recommendNextBatch').onclick=()=>loadRecommendations(true);
  $('grid').onclick=event=>{const author=event.target.closest('[data-author]');if(author){event.stopPropagation();openAuthor(author.dataset.author);return;}const card=event.target.closest('[data-book]');if(card)openBook(displayBooks[Number(card.dataset.book)]);};
  $('grid').onkeydown=event=>{if(event.target.closest('[data-author]'))return;if(event.key==='Enter'){const card=event.target.closest('[data-book]');if(card)openBook(displayBooks[Number(card.dataset.book)]);}};
  $('detailBack').onclick=closeDetail;$('readerBack').onclick=closeReader;$('shelfButton').onclick=toggleShelf;$('readButton').onclick=()=>startReading();$('importButton').onclick=()=>importFiles(false);$('importFolderButton').onclick=()=>importFiles(true);
  $('bookHeading').onclick=event=>{const author=event.target.closest('[data-author]');if(author)openAuthor(author.dataset.author);};
  $('bookTags').onclick=event=>{const button=event.target.closest('[data-tag]');if(!button)return;const tag=button.dataset.tag;if(!tagFilters.includes(tag))tagFilters.push(tag);updateFilterLabel();closeDetail();query='';searchType='tags';setTab('discover',true);};
  function feedback(value){if(!currentBook)return;const key=String(currentBook.id),previous=state.preference.feedback[key];if(previous===value)delete state.preference.feedback[key];else state.preference.feedback[key]=value;const keys=Object.keys(state.preference.feedback);keys.slice(0,Math.max(0,keys.length-300)).forEach(id=>delete state.preference.feedback[id]);rememberBooks([currentBook]);save();renderHeading();}
  $('likeBook').onclick=()=>feedback('like');$('hideBook').onclick=()=>feedback('hide');
  function renderPreferences(){
    $('preferenceGroups').innerHTML=window.ReaderTaxonomy.groups.map(group=>'<section class="preference-group"><h3>'+esc(group.name)+'</h3><div>'+group.tags.map(tag=>{const selected=preferenceDraft.likes.includes(tag)?'like':preferenceDraft.avoid.includes(tag)?'avoid':'';return '<button class="tag-chip '+selected+'" data-preference="'+esc(tag)+'" title="'+esc(window.ReaderTaxonomy.descriptions[tag]||'')+'">'+(selected==='like'?'＋ ':selected==='avoid'?'− ':'')+esc(tag)+'</button>';}).join('')+'</div></section>').join('');
  }
  $('preferencesButton').onclick=()=>{preferenceDraft=JSON.parse(JSON.stringify(state.preference));renderPreferences();visible('preferencesSheet',true);};
  $('preferencesClose').onclick=()=>visible('preferencesSheet',false);
  $('preferenceGroups').onclick=event=>{const button=event.target.closest('[data-preference]');if(!button)return;const tag=button.dataset.preference,liked=preferenceDraft.likes.includes(tag),avoided=preferenceDraft.avoid.includes(tag);preferenceDraft.likes=preferenceDraft.likes.filter(t=>t!==tag);preferenceDraft.avoid=preferenceDraft.avoid.filter(t=>t!==tag);if(liked)preferenceDraft.avoid.push(tag);else if(!avoided)preferenceDraft.likes.push(tag);$('preferenceDescription').textContent=tag+'：'+(window.ReaderTaxonomy.descriptions[tag]||'来源标签');renderPreferences();};
  $('clearPreferences').onclick=()=>{preferenceDraft.likes=[];preferenceDraft.avoid=[];renderPreferences();};
  $('applyPreferences').onclick=()=>{state.preference=preferenceDraft;save();visible('preferencesSheet',false);setTab('recommend',true);};
  $('cacheButton').onclick=()=>showCache(null,[]);$('cacheBookButton').onclick=()=>showCache(currentBook,chapters);$('detailCacheButton').onclick=()=>showCache(currentBook,chapters);
  $('cacheClose').onclick=()=>{visible('cacheScreen',false);lastHeartbeat=Date.now();scheduleMore();};$('cacheRefresh').onclick=()=>refreshCache(false);
  $('cacheStart').onclick=async()=>{if(!cacheTarget)return;$('cacheStart').disabled=true;try{cacheState=await native('cacheStart',{id:cacheTarget.id,book:cacheTarget,volume:$('cacheVolume').value.startsWith('@chapter:')?'':$('cacheVolume').value,chapterId:$('cacheVolume').value.startsWith('@chapter:')?Number($('cacheVolume').value.slice(9)):0,images:$('cacheImages').checked});renderCache();}catch(error){toast(error.message);renderCache();}};
  $('cacheCancel').onclick=async()=>{try{cacheState=await native('cacheCancel',{});renderCache();}catch(error){toast(error.message);}};
  $('cacheLimit').onchange=async()=>{try{cacheState=await native('cacheLimit',{limitMiB:Number($('cacheLimit').value)});renderCache();}catch(error){toast(error.message);renderCache();}};
  async function removeCache(id){if(!window.confirm(id?'清理这本作品的离线缓存？阅读位置和收藏会保留。':'清空全部离线缓存？阅读位置、收藏和本地原文件会保留。'))return;try{cacheState=await native('cacheRemove',{id:Number(id)});renderCache();toast('缓存已清理');}catch(error){toast(error.message);}}
  $('cacheClear').onclick=()=>removeCache(0);$('cacheList').onclick=event=>{const read=event.target.closest('[data-cache-read]'),remove=event.target.closest('[data-cache-delete]');if(remove)removeCache(remove.dataset.cacheDelete);if(read){const book=(cacheState.books||[]).find(b=>String(b.id)===read.dataset.cacheRead);if(book){visible('cacheScreen',false);openBook(book,true);}}};
  setInterval(()=>{if(cacheState.running||!$('cacheScreen').classList.contains('hidden'))refreshCache(true);},2000);
  document.querySelectorAll('[data-history-mode]').forEach(button=>button.onclick=()=>{historyMode=button.dataset.historyMode;renderHistory();});
  $('historyButton').onclick=()=>{renderHistory();visible('historyScreen',true);};$('historyClose').onclick=()=>visible('historyScreen',false);$('historyList').onclick=event=>{const button=event.target.closest('[data-history-id]');if(!button)return;const book=byId(button.dataset.historyId);if(!book)return;visible('historyScreen',false);openBook(book,Boolean(state.progress[String(book.id)]));};
  $('profileButton').onclick=()=>{heartbeat();renderProfile();visible('profileScreen',true);};$('profileClose').onclick=()=>{visible('profileScreen',false);scheduleMore();};
  $('profileClear').onclick=()=>{if(!window.confirm('清除浏览和阅读学习记录？收藏、主动偏好和阅读位置会保留。'))return;state.learning={books:{}};lastHeartbeat=Date.now();save();flush();renderProfile();toast('已清空浏览与阅读学习记录');};
  $('profileTags').onclick=event=>{const button=event.target.closest('[data-profile-tag]');if(!button)return;const tag=button.dataset.profileTag,entry=profileResult().tags.find(t=>t.tag===tag);if(entry)$('profileTagDetail').innerHTML='<h3>'+esc(tag)+' · 来源</h3>'+entry.sources.map(s=>'<p>'+esc(s.title)+' <strong>'+s.weight.toFixed(2)+'</strong></p>').join('');};
  $('profileBooks').onclick=event=>{const button=event.target.closest('[data-profile-book]');if(!button)return;const book=byId(button.dataset.profileBook);if(!book||(book.source==='local'&&!localBooks.some(b=>b.id===book.id))){toast('本地原文件已移出，请重新导入');return;}visible('profileScreen',false);openBook(book);};
  $('accountRegister').onclick=()=>native('openRegistration',{}).catch(error=>toast(error.message));$('accountLogin').onclick=()=>loginAccount();$('accountRefresh').onclick=()=>refreshCloud(true);
  $('accountLogout').onclick=async()=>{if(accountBusy)return;accountBusy=true;const args=accountArgs(),generation=++accountGeneration;renderAccount();try{const result=await native('accountLogout',args);if(generation!==accountGeneration)return;applyAccount(result);$('accountMessage').textContent='已退出账号，本机收藏和阅读记录保留';if(tab==='shelf')renderGrid(allBooks());renderContinue();}catch(error){toast(error.message);}finally{if(generation===accountGeneration){accountBusy=false;renderAccount();}}};
  $('accountUpload').onclick=async()=>{const books=state.shelf.filter(b=>b.favorite===true&&b.source!=='local');if(accountBusy||!accountState.signedIn||!books.length||!window.confirm('把这 '+books.length+' 本本机收藏加入当前 Wenku8 账号？网站原有收藏会保留。'))return;const generation=accountGeneration;accountBusy=true;renderAccount();let done=0;try{for(const book of books){if(generation!==accountGeneration)break;if(!(accountState.books||[]).some(b=>String(b.id)===String(book.id)))applyAccount(await native('accountAdd',accountArgs({id:book.id})));state.shelf=state.shelf.filter(b=>String(b.id)!==String(book.id));save();done++;$('accountMessage').textContent='已同步 '+done+' / '+books.length+' 本';}if(tab==='shelf')renderGrid(allBooks());}catch(error){$('accountMessage').textContent='已同步 '+done+' 本；'+error.message;}finally{if(generation===accountGeneration){accountBusy=false;renderAccount();}}};
  $('expandIntro').onclick=()=>{$('bookIntro').classList.toggle('expanded');$('expandIntro').textContent=$('bookIntro').classList.contains('expanded')?'收起简介':'展开简介';};
  $('catalogList').onclick=event=>{const button=event.target.closest('[data-chapter]');if(button)startReading(Number(button.dataset.chapter));};
  localOrderEditor=window.ReaderLocalOrderEditor({library,toast,closed:()=>scheduleMore(),save:async(book,keys,reset)=>{const result=await native('localOrder',{id:book.id,fileIds:book.fileIds,keys,reset});localOrders=result.orders||{};localBooks=prepareLocal(result.localBooks||rawLocalBooks);if(currentBook&&currentBook.id===book.id){currentBook=localBooks.find(b=>b.id===book.id)||currentBook;chapters=currentBook.chapters;renderHeading();renderCatalog();}save();renderContinue();if(tab==='local'||tab==='shelf')renderGrid(tab==='local'?localBooks:allBooks());}});
  $('detailRefresh').onclick=async()=>{if(!currentBook)return;if(currentBook.source==='local'){localOrderEditor.open(currentBook,library.groupBooks(rawLocalBooks).find(b=>b.id===currentBook.id));return;}const id=currentBook.id;try{const result=await native('catalog',{id,refresh:true});if(currentBook&&String(currentBook.id)===String(id)){chapters=result.chapters||[];renderCatalog();toast('目录已更新');}}catch(error){toast(error.message);}};
  $('readerCatalog').onclick=()=>{closeReader();const target=$('detail').querySelector('.catalog-heading');if(target)target.scrollIntoView({block:'start'});};
  $('prevButton').onclick=()=>turn(-1);$('nextButton').onclick=()=>turn(1);
  $('settingsButton').onclick=showSettings;$('readerSettings').onclick=showSettings;$('settingsClose').onclick=hideSettings;$('sheetBackdrop').onclick=hideSettings;
  $('hideStatusBar').onchange=()=>{settings().hideStatusBar=$('hideStatusBar').checked;syncSystemUi();save();};
  $('fontSize').oninput=()=>{settings().fontSize=Number($('fontSize').value);applySettings(true);save();};
  $('fontMinus').onclick=()=>{settings().fontSize=Math.max(16,settings().fontSize-1);applySettings(true);save();};
  $('fontPlus').onclick=()=>{settings().fontSize=Math.min(32,settings().fontSize+1);applySettings(true);save();};
  $('lineHeight').oninput=()=>{settings().lineHeight=Number($('lineHeight').value);applySettings(true);save();};
  document.querySelectorAll('[data-theme]').forEach(button=>button.onclick=()=>{settings().theme=button.dataset.theme;applySettings(true);save();});
  document.querySelectorAll('[data-mode]').forEach(button=>button.onclick=()=>{settings().mode=button.dataset.mode;applySettings(true);save();if(reading)observeImages();});
  document.querySelectorAll('[data-font]').forEach(button=>button.onclick=()=>{settings().font=button.dataset.font;applySettings(true);save();});
  let scrollTimer;$('readerViewport').onscroll=()=>{if(readingLocation)readingLocation.onScroll();if(!reading||loading||pageMotion&&(pageMotion.busy||pageMotion.dragging))return;updatePosition();clearTimeout(scrollTimer);scrollTimer=setTimeout(rememberPosition,350);};
  pagination=window.ReaderPagination($('readerViewport'),$('readerArticle'));
  readingLocation=window.ReaderLocation($('readerViewport'),$('readerArticle'));
  ['touchstart','pointerdown','wheel','keydown'].forEach(name=>$('readerViewport').addEventListener(name,()=>readingLocation.release(),{passive:true}));
  pageMotion=window.ReaderPageMotion({viewport:$('readerViewport'),stage:$('readerStage'),neighbor:$('readerNeighbor'),pages,width:pageWidth,active:()=>reading&&!loading&&settings().mode==='page'&&$('settingsSheet').classList.contains('hidden')&&$('imageViewer').classList.contains('hidden')&&$('cacheScreen').classList.contains('hidden')&&$('illustrationScreen').classList.contains('hidden')&&$('bookmarkScreen').classList.contains('hidden'),neighborFor:dir=>readerChapters[currentIndex+dir],turn,onPosition:()=>{updatePosition();rememberPosition();}});
  aboutUi=window.ReaderAbout({native,toast,canNotify:()=>booted&&foreground&&!reading&&!overlayOpen()});$('aboutButton').onclick=()=>aboutUi.open();
  bookmarkUi=window.ReaderBookmarks({native,toast,records:()=>bookmarks,changed:value=>{bookmarks=value;},open:record=>{let book=record.source==='local'?localBooks.find(b=>String(b.id)===record.bookId):byId(record.bookId)||{id:record.bookId,source:'online',title:record.bookTitle,author:record.author,cover:record.cover};if(!book){toast('本地原文件已移出，请重新导入');return false;}openBook(book,true,record);return true;}});$('bookmarkButton').onclick=()=>bookmarkUi.open();
  illustrations=window.ReaderIllustrations({resource:resourcePath,view:openImage,fetch:async(book,chapter,index)=>{const key=book.source+':'+String(chapter.fileId||book.id)+':'+String(chapter.key||chapter.id)+':'+index,recent=chapterMemory.get(key);return recent?recent.payload:native(book.source==='local'?'localChapter':'chapter',{id:chapter.fileId||book.id,index:chapter.fileIndex==null?index:chapter.fileIndex,chapterId:Number(chapter.id)});}});
  readerControls=window.ReaderControls({reader:$('reader'),viewport:$('readerViewport'),active:()=>reading&&!loading&&!(pageMotion&&(pageMotion.busy||pageMotion.dragging))&&$('settingsSheet').classList.contains('hidden')&&$('imageViewer').classList.contains('hidden')&&$('cacheScreen').classList.contains('hidden')&&$('illustrationScreen').classList.contains('hidden')&&$('bookmarkScreen').classList.contains('hidden'),mode:()=>settings().mode,turn});
  $('imageClose').onclick=closeImage;$('imageReset').onclick=()=>{imageScale=1;imageX=0;imageY=0;imageTransform();};
  $('imageStage').ondblclick=()=>{imageScale=imageScale>1?1:2;imageX=0;imageY=0;imageTransform();};
  $('imageStage').addEventListener('pointerdown',event=>{imagePoints.set(event.pointerId,{x:event.clientX,y:event.clientY});$('imageStage').setPointerCapture(event.pointerId);if(imagePoints.size===2){const values=Array.from(imagePoints.values());pinchDistance=Math.hypot(values[0].x-values[1].x,values[0].y-values[1].y);pinchScale=imageScale;}});
  $('imageStage').addEventListener('pointermove',event=>{const previous=imagePoints.get(event.pointerId);if(!previous)return;imagePoints.set(event.pointerId,{x:event.clientX,y:event.clientY});if(imagePoints.size===2){const values=Array.from(imagePoints.values());imageScale=Math.max(1,Math.min(5,pinchScale*Math.hypot(values[0].x-values[1].x,values[0].y-values[1].y)/Math.max(1,pinchDistance)));}else if(imageScale>1){imageX+=event.clientX-previous.x;imageY+=event.clientY-previous.y;}imageTransform();});
  ['pointerup','pointercancel'].forEach(name=>$('imageStage').addEventListener(name,event=>imagePoints.delete(event.pointerId)));
  $('imageStage').addEventListener('wheel',event=>{event.preventDefault();imageScale=Math.max(1,Math.min(5,imageScale*(event.deltaY<0?1.15:.85)));imageTransform();},{passive:false});
  ['pointerdown','touchstart','keydown'].forEach(name=>document.addEventListener(name,()=>{lastInteraction=Date.now();},{passive:true}));
  $('readerViewport').addEventListener('scroll',()=>{lastInteraction=Date.now();},{passive:true});
  $('recommendPreferences').onclick=()=>$('preferencesButton').click();$('recommendProfile').onclick=()=>$('profileButton').click();$('tagIndexControl').onclick=async()=>{try{if(tagStats.running){await native('tagPause',{});loadTags(false,false);}else loadTags(true,Boolean(tagStats.complete));}catch(error){toast(error.message);}};setInterval(()=>{if(tab==='discover'&&tagFilters.length&&foreground&&!reading&&!overlayOpen()&&(tagStats.running||tagStartWanted||!tagStats.initialized))loadTags(false,false);},1000);
  window.addEventListener('scroll',()=>{if(Math.abs(window.scrollY-lastWindowY)>10){autoBudget=3;lastWindowY=window.scrollY;}scheduleMore();},{passive:true});
  if(window.IntersectionObserver)new IntersectionObserver(()=>scheduleMore(),{rootMargin:'1000px'}).observe($('feedSentinel'));
  setInterval(heartbeat,5000);setInterval(()=>{if(tab==='shelf'&&!reading)refreshCloud(false);},60000);
  document.addEventListener('visibilitychange',()=>{if(document.hidden){heartbeat();lastHeartbeat=Date.now();flush();}else{lastHeartbeat=Date.now();lastInteraction=Date.now();refreshCloud(false);scheduleMore();}});
  window.addEventListener('resize',()=>{if(pageMotion)pageMotion.reset();const previous=reading?ratio():0,anchor=reading&&readingLocation?readingLocation.capture():null;measurePage();if(reading)requestAnimationFrame(()=>{measurePage();if(!anchor||!readingLocation.restore(anchor))restoreRatio(previous);});});
  document.addEventListener('keydown',event=>{if(event.key==='Escape')back();if(reading&&!event.target.closest('input,textarea,select,button')&&$('settingsSheet').classList.contains('hidden')&&!String(window.getSelection())){if(event.key==='ArrowRight'){event.preventDefault();turn(1);}if(event.key==='ArrowLeft'){event.preventDefault();turn(-1);}}});

  async function boot(){
    try{const result=await native('bootstrap',{}),stored=result.state||{};bookmarks=result.bookmarks||[];bookmarkUi.render();state.settings=Object.assign(state.settings,stored.settings||{});state.progress=stored.progress||{};state.history=stored.history||[];const oldShelf=stored.shelf||[];state.shelf=oldShelf.filter(b=>b.source!=='local'&&b.favorite===true);oldShelf.filter(b=>b.source!=='local'&&b.favorite!==true).forEach(book=>{if(!state.history.some(b=>String(b.id)===String(book.id)))rememberHistory(Object.assign({},book,{seenAt:(state.progress[String(book.id)]||{}).time||book.addedAt||0}),false);});state.preference=Object.assign(state.preference,stored.preference||{});state.learning=stored.learning||{books:{}};window.ReaderProfile.trim(state.learning,Date.now());localOrders=result.localOrders||{};localBooks=prepareLocal(result.localBooks||[]);cacheState=result.offlineCache||{books:[],bytes:0,limitMiB:200};renderCache();applyAccount(result.account||{signedIn:false,books:[]});rememberBooks(onlineFavorites().concat(state.history));applySettings(false);renderAccount();await warmCovers(allBooks());booted=true;setTab('shelf');save();refreshCloud(false);aboutUi.autoCheck();}
    catch(error){applySettings(false);$('gridMessage').innerHTML='<p>'+esc(error.message)+'</p>';}
  }
  boot();
})();

(function(root){
  'use strict';
  const DAY=86400000,HOUR=3600000;
  function day(now){return new Date(now+8*HOUR).toISOString().slice(0,10);}
  function ensure(state,id){const books=state.books||(state.books={});return books[id]||(books[id]={views:{},reads:{},hours:{},lastRead:0,touched:0});}
  function trim(state,now){
    const books=state.books||(state.books={}),cutoff=day(now-30*DAY),hour=Math.floor((now-72*HOUR)/HOUR);
    Object.keys(books).forEach(id=>{const b=books[id];for(const key of Object.keys(b.views||{}))if(key<cutoff)delete b.views[key];for(const key of Object.keys(b.reads||{}))if(key<cutoff)delete b.reads[key];for(const key of Object.keys(b.hours||{}))if(Number(key)<hour)delete b.hours[key];});
    Object.keys(books).sort((a,b)=>(books[b].touched||0)-(books[a].touched||0)).slice(150).forEach(id=>delete books[id]);
  }
  function view(state,id,qualified,now){now=now||Date.now();const b=ensure(state,String(id)),key=day(now),previous=b.views[key];b.views[key]={weight:Math.max(previous?previous.weight:0,qualified?.2:.1),at:now};b.touched=now;trim(state,now);}
  function read(state,id,seconds,now){now=now||Date.now();seconds=Math.max(0,Math.min(20,seconds));if(!seconds)return;const b=ensure(state,String(id)),key=day(now),hour=String(Math.floor(now/HOUR));b.reads[key]=Math.min(86400,(b.reads[key]||0)+seconds);b.hours[hour]=Math.min(3600,(b.hours[hour]||0)+seconds);b.lastRead=now;b.touched=now;trim(state,now);}
  function calculate(books,preference,shelf,state,now){
    now=now||Date.now();const L=root.ReaderLibrary,profile={},sources={},records=[],avoid=new Set(preference.avoid||[]),feedback=preference.feedback||{},unique=new Map();
    books.concat(shelf||[]).forEach(book=>unique.set(String(book.id),book));
    const explicit=L.features((preference.likes||[]).filter(t=>!avoid.has(t)));Object.keys(explicit).forEach(t=>{profile[t]=6*explicit[t];sources[t]=[{title:'主动标签偏好',weight:6*explicit[t]}];});
    let evidence=Object.keys(explicit).length?6:0,totalSeconds=0;
    unique.forEach(book=>{const id=String(book.id),b=(state.books||{})[id]||{},decay=t=>t?Math.pow(2,-Math.max(0,now-t)/(14*DAY)):0;
      const views=Object.keys(b.views||{}).reduce((sum,k)=>{const v=b.views[k];return sum+(k>=day(now-30*DAY)?v.weight*decay(v.at):0);},0);
      const seconds=Object.keys(b.reads||{}).reduce((sum,k)=>sum+(k>=day(now-30*DAY)?b.reads[k]:0),0),days=Object.values(b.reads||{}).filter(n=>n>=90).length;
      const recent=Object.keys(b.hours||{}).reduce((sum,h)=>sum+(Number(h)>=Math.floor((now-72*HOUR)/HOUR)?b.hours[h]:0),0);
      const sustained=recent>=3600?3:recent>=1800?2:recent>=600?1:0;
      const weights={browse:Math.min(1,views),read:seconds>=90?decay(b.lastRead):0,returnRead:days>=2?2*decay(b.lastRead):0,sustained,favorite:(shelf||[]).some(s=>String(s.id)===id&&s.favorite===true)?4:0,like:feedback[id]==='like'?6:0};
      const hidden=feedback[id]==='hide',raw=Object.values(weights).reduce((a,b)=>a+b,0),score=hidden?0:Math.min(10,raw);totalSeconds+=seconds;
      if(!raw&&!seconds)return;records.push({id,title:book.title,seconds,score,weights,hidden});evidence+=score;
      const f=L.features(L.tags(book));Object.keys(f).forEach(t=>{if(avoid.has(t))return;profile[t]=(profile[t]||0)+score*f[t];if(score>0)(sources[t]||(sources[t]=[])).push({id,title:book.title,weight:score*f[t]});});
    });
    return{vector:profile,sources,records:records.sort((a,b)=>b.score-a.score),totalSeconds,evidence,tags:Object.keys(profile).filter(t=>profile[t]>0).sort((a,b)=>profile[b]-profile[a]).map(tag=>({tag,weight:profile[tag],sources:(sources[tag]||[]).sort((a,b)=>b.weight-a.weight)}))};
  }
  root.ReaderProfile={view,read,trim,calculate};
})(typeof window!=='undefined'?window:globalThis);
